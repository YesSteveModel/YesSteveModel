package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelManagementLocalCatalogScenarioTest {
    private static final String SCENARIO_ID = "MMR-DOM-LOCAL-001";
    // The 2,000-file corpus checks semantics, so cold filesystem work has no 30-second latency requirement.
    private static final long DEADLOCK_GUARD_SECONDS = 120;

    @TempDir
    Path temp;

    @Test
    @Tag("model-management-mock")
    void completeLocalCatalogTransitionHasOneAtomicAuthority() throws Throwable {
        assumeTrue(EvidenceRun.isConfigured(), "Only the explicit mock task writes acceptance evidence");
        var corpus = LocalCatalogCorpus.create(temp.resolve("corpus"));
        var inputBytes = EvidenceJson.canonicalBytes(corpus.input());
        var run = EvidenceRun.openConfigured();
        var identity = run.identity(SCENARIO_ID, List.of("FA-001", "FA-007"),
            List.of("CO-001", "CO-002", "CO-009", "CO-011", "CO-016"), EvidenceRun.Radius.DETERMINISTIC_COMPOSITION,
            true, inputBytes);
        var evidence = run.scenario(identity, inputBytes);

        try {
            execute(corpus, evidence);
            evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, List.of(
                "2,000 distinct valid ModelIds and one duplicate source were opened through production discovery and container validation",
                "Incremental transitions formed one immutable chain ending in the manifest-defined inventory",
                "Invalid content and root inventory failure remained local while the prior authority stayed current",
                "Reload overlap returned BUSY and observer failure did not roll back or block publication",
                "Retired content remained byte-readable across source replacement and after one consumer reference was released",
                "BUSY requests were ignored and owner close rejected the late candidate",
                "Filesystem changes kept admission and publication idle until explicit reload"), List.of(),
                Map.of("contractViolation", 0L, "unexpectedError", 0L, "expectedInvalidSource", 1L,
                    "expectedObserverFailure", 1L, "expectedRootInventoryFailure", 1L),
                "Deterministic Java composition on the recorded Windows/filesystem/JVM configuration; no Forge transport, long soak, arbitrary larger cardinality, fixed reload latency, or physical reclamation claim"));
        } catch (Throwable failure) {
            completeFailure(evidence, failure);
            throw failure;
        }
    }

    private void execute(LocalCatalogCorpus corpus, ScenarioEvidence evidence) throws Exception {
        var transitions = new CopyOnWriteArrayList<CatalogTransition>();
        var reconciler = reconciler(corpus);
        var admissions = new AtomicInteger();
        try (var catalog = new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins(), runnable -> {
            admissions.incrementAndGet();
            return new Thread(runnable);
        })) {
            catalog.subscribe(transitions::add);
            requireTransition(transitions.get(0), List.of(), corpus.expected("startup-default"), "subscription");

            var before = transitions.size();
            catalog.activate();
            finishBusy(catalog);
            requireTransitionRange(transitions, before, corpus.expected("startup-default"),
                corpus.expected("initial-discovery"), "initial-discovery");
            requireInventory(catalog.current(), corpus.expected("initial-discovery"), "initial-discovery");
            var invalidErrors = catalog.current().report().errors().stream()
                .filter(error -> error.source().equals("local:custom/" + LocalCatalogCorpus.INVALID_PATH)).count();
            require(invalidErrors == 1, "invalid source must produce one identity-correlated diagnostic");
            require(catalog.index().findCandidates(corpus.identityAt("model-0000.mxc")).size() == 1,
                "identity index must contain only the selected conflict winner");
            record(evidence, "initial-discovery", "catalog-transition",
                Map.of("actualInventory", inventoryJson(catalog.current()), "distinctValidCustomModels",
                    Integer.toString(catalog.current().byModelId().size() - 1), "invalidDiagnosticCount",
                    Long.toString(invalidErrors), "sourceCandidateCount",
                    Integer.toString(catalog.index().entries().size())));

            var failObserver = new AtomicBoolean();
            var failingObserverCalls = new AtomicInteger();
            var healthyObserverCalls = new AtomicInteger();
            catalog.subscribe(transition -> {
                failingObserverCalls.incrementAndGet();
                if (failObserver.compareAndSet(true, false)) {
                    throw new IllegalStateException("expected observer failure");
                }
            });
            catalog.subscribe(transition -> healthyObserverCalls.incrementAndGet());

            corpus.add();
            failObserver.set(true);
            var add = catalog.reload();
            var busy = catalog.reload().join();
            require(busy.status() == ReloadStatus.BUSY, "ordinary overlapping reload must return BUSY");
            before = transitions.size();
            var failingBefore = failingObserverCalls.get();
            var healthyBefore = healthyObserverCalls.get();
            finish(catalog, add);
            require(add.join().status() == ReloadStatus.COMMITTED, "add reload must commit");
            requireTransitionRange(transitions, before, corpus.expected("initial-discovery"),
                corpus.expected("add-and-overlap"), "add-and-overlap");
            require(healthyObserverCalls.get() > healthyBefore,
                "observer failure must not block the independent observer");
            require(failingObserverCalls.get() - failingBefore == healthyObserverCalls.get() - healthyBefore,
                "all observers must receive the same publication attempts");
            record(evidence, "add-and-overlap", "reload-outcome",
                Map.of("committed", add.join().status().name(), "overlap", busy.status().name(), "healthyObserverCalls",
                    Integer.toString(healthyObserverCalls.get()), "failingObserverCalls",
                    Integer.toString(failingObserverCalls.get()), "actualInventory", inventoryJson(catalog.current())));

            var retiredIdentity = corpus.identityAt(LocalCatalogCorpus.MODIFIED_PATH);
            var firstConsumer = catalog.current().binding(retiredIdentity.modelId()).orElseThrow().content();
            var secondConsumer = firstConsumer;
            var retiredBytes = readManifestBytes(firstConsumer);
            corpus.modify();
            before = transitions.size();
            var modify = catalog.reload();
            finish(catalog, modify);
            require(modify.join().status() == ReloadStatus.COMMITTED, "modify reload must commit");
            requireTransitionRange(transitions, before, corpus.expected("add-and-overlap"),
                corpus.expected("modify-and-held-content"), "modify-and-held-content");
            require(catalog.current().binding(retiredIdentity.modelId()).isEmpty(),
                "replaced ModelId must leave current authority");
            require(Arrays.equals(retiredBytes, readManifestBytes(secondConsumer)),
                "retired immutable metadata must survive source replacement");
            firstConsumer = null;
            Reference.reachabilityFence(firstConsumer);
            require(Arrays.equals(retiredBytes, readManifestBytes(secondConsumer)),
                "one consumer release must not invalidate retained metadata");
            record(evidence, "modify-and-held-content", "held-handle",
                Map.of("retiredIdentity", identity(retiredIdentity), "stillCurrent", "false",
                    "readableAfterReplacement", "true", "readableAfterPeerRelease", "true", "actualInventory",
                    inventoryJson(catalog.current())));

            corpus.delete();
            before = transitions.size();
            var deleted = catalog.reload();
            finish(catalog, deleted);
            require(deleted.join().status() == ReloadStatus.COMMITTED, "delete reload must commit");
            requireTransitionRange(transitions, before, corpus.expected("modify-and-held-content"),
                corpus.expected("delete"), "delete");
            record(evidence, "delete", "catalog-transition",
                Map.of("actualInventory", inventoryJson(catalog.current())));

            var idleCandidate = catalog.currentCandidate();
            var admittedBefore = admissions.get();
            before = transitions.size();
            corpus.fileBurst();
            for (var index = 0; index < 1_000; index++) {
                catalog.tick();
            }
            require(!catalog.busy(), "filesystem changes must not admit a scan");
            require(admissions.get() == admittedBefore, "filesystem changes must not create a coordinator");
            require(catalog.currentCandidate() == idleCandidate, "filesystem changes must retain the candidate");
            require(transitions.size() == before, "filesystem changes must not publish");
            var burst = catalog.reload();
            finish(catalog, burst);
            require(burst.join().status() == ReloadStatus.COMMITTED, "explicit burst reload must commit");
            require(admissions.get() == admittedBefore + 1, "explicit reload must admit exactly one scan");
            requireInventory(catalog.current(), corpus.expected("explicit-burst"), "explicit-burst");
            requireTransitionRange(transitions, before, corpus.expected("delete"), corpus.expected("explicit-burst"),
                "explicit-burst");
            record(evidence, "explicit-burst", "explicit-reload-transition",
                Map.of("actualInventory", inventoryJson(catalog.current()), "automaticAdmissionCount", "0",
                    "automaticPublicationCount", "0", "explicitAdmissionCount", "1", "publishedTransitionCount",
                    Integer.toString(transitions.size() - before)));

            var backup = corpus.root().resolveSibling("models-before-root-failure");
            Files.move(corpus.root(), backup, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(corpus.root(), "not a directory", StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
            ReloadResult rootFailure;
            before = transitions.size();
            try {
                var reload = catalog.reload();
                rootFailure = finish(catalog, reload);
            } finally {
                Files.deleteIfExists(corpus.root());
                Files.move(backup, corpus.root(), StandardCopyOption.ATOMIC_MOVE);
            }
            require(rootFailure.status() == ReloadStatus.FAILED, "root inventory failure must fail the candidate");
            require(rootFailure.message().equals("Source is inaccessible"),
                "root failure must expose the public access category");
            require(
                catalog.current().report().errors().stream().anyMatch(
                    error -> error.code().equals("ROOT_INVENTORY_INCOMPLETE") && error.source().equals("local:custom")),
                "root failure must retain typed provenance and its logical root");
            require(transitions.size() >= before, "root failure must not remove prior transitions");
            requireInventory(catalog.current(), corpus.expected("root-inventory-failure"), "root-inventory-failure");
            record(evidence, "root-inventory-failure", "reload-outcome",
                Map.of("status", rootFailure.status().name(), "message", rootFailure.message(), "observerTransitions",
                    Integer.toString(transitions.size() - before), "actualInventory",
                    inventoryJson(catalog.current())));

            corpus.addLateCandidate();
            var late = catalog.reload();
            before = transitions.size();
            catalog.close();
            require(late.join().status() == ReloadStatus.FAILED, "owner close must terminal the accepted build");
            require(catalog.current().byModelId().isEmpty(), "closed owner must expose no current catalog");
            require(transitions.size() == before, "late candidate must not publish after owner close");
            require(catalog.current().byModelId().isEmpty(), "late candidate must not repopulate current authority");
            record(evidence, "owner-close-late-candidate", "owner-close",
                Map.of("acceptedBuildTerminal", late.join().status().name(), "ownerBarrier", "closed",
                    "latePublicationCount", "0", "actualInventory", inventoryJson(catalog.current())));

            Reference.reachabilityFence(secondConsumer);
        }
    }

    private CatalogReconciler reconciler(LocalCatalogCorpus corpus) throws Exception {
        var cacheRoot = temp.resolve("cache");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(cacheRoot, "local-catalog-scenario"), new ConvertedObjectStore(cacheRoot));
        var defaultIdentity = readIdentity(corpus.defaultFile());
        var intrinsicDefault = ManagedContainer.openIndexed(new CatalogIndexEntry(defaultIdentity,
            new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")), corpus.defaultFile()));
        return new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, corpus.root(), false)),
            resolver, Set.of(defaultIdentity.modelId()), intrinsicDefault, CatalogIndexSnapshot.empty());
    }

    private static ModelFileIdentity readIdentity(Path file) throws Exception {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }

    private static byte[] readManifestBytes(ModelContent content) throws Exception {
        var chunk =
            content.modelFile().getFileView().getAssetView().getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
        {
            var prefix = content.representation().metadataPrefix().orElseThrow();
            try (var array = prefix.copyArray()) {
                return Arrays.copyOfRange(array.array(), array.arrayOffset() + chunk.offset(),
                    array.arrayOffset() + chunk.offset() + chunk.size());

            }
        }
    }

    private static void requireTransition(List<CatalogTransition> transitions, int index,
        List<LocalCatalogCorpus.InventoryEntry> previous, List<LocalCatalogCorpus.InventoryEntry> current,
        String action) {
        require(transitions.size() == index + 1, action + " must publish exactly one observer transition");
        requireTransition(transitions.get(index), previous, current, action);
    }

    private static void requireTransitionRange(List<CatalogTransition> transitions, int first,
        List<LocalCatalogCorpus.InventoryEntry> previous, List<LocalCatalogCorpus.InventoryEntry> current,
        String action) {
        require(transitions.size() > first, action + " must publish at least one incremental transition");
        requireInventory(transitions.get(first).previous(), previous, action + " initial previous");
        for (var index = first + 1; index < transitions.size(); index++) {
            require(
                inventory(transitions.get(index - 1).current()).equals(inventory(transitions.get(index).previous())),
                action + " transition chain broke at " + index);
        }
        requireInventory(transitions.get(transitions.size() - 1).current(), current, action + " terminal current");
    }

    private static void requireTransition(CatalogTransition transition,
        List<LocalCatalogCorpus.InventoryEntry> previous, List<LocalCatalogCorpus.InventoryEntry> current,
        String action) {
        requireInventory(transition.previous(), previous, action + " previous");
        requireInventory(transition.current(), current, action + " current");
    }

    private static void requireInventory(CatalogSnapshot snapshot, List<LocalCatalogCorpus.InventoryEntry> expected,
        String action) {
        var actual = inventory(snapshot);
        require(actual.equals(expected),
            action + " inventory mismatch expectedHash=" + EvidenceJson.sha256(EvidenceJson.canonicalBytes(expected))
                + " actualHash=" + EvidenceJson.sha256(EvidenceJson.canonicalBytes(actual)) + " expectedCount="
                + expected.size() + " actualCount=" + actual.size());
    }

    private static List<LocalCatalogCorpus.InventoryEntry> inventory(CatalogSnapshot snapshot) {
        return snapshot.byModelId().values().stream()
            .map(record -> new LocalCatalogCorpus.InventoryEntry(record.entry().modelId().toString(),
                record.binding().content().representation().containerId().toString(),
                record.location().rootKind().name(), record.location().path().value()))
            .sorted(Comparator.comparing(LocalCatalogCorpus.InventoryEntry::modelId)).toList();
    }

    private static String inventoryJson(CatalogSnapshot snapshot) {
        return new String(EvidenceJson.canonicalBytes(inventory(snapshot)), StandardCharsets.UTF_8);
    }

    private static ReloadResult finish(ReloadableModelCatalog catalog, CompletableFuture<ReloadResult> completion)
        throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLOCK_GUARD_SECONDS);
        while (!completion.isDone() && System.nanoTime() < deadline) {
            catalog.tick();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return completion.get(1, TimeUnit.SECONDS);
    }

    private static void finishBusy(ReloadableModelCatalog catalog) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLOCK_GUARD_SECONDS);
        while (catalog.busy() && System.nanoTime() < deadline) {
            catalog.tick();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        require(!catalog.busy(), "catalog scan did not reach its physical terminal");
    }

    private static void record(ScenarioEvidence evidence, String actionId, String eventKind,
        Map<String, String> payload) throws Exception {
        evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, actionId, "local-catalog", null,
            "local-catalog-owner", eventKind, payload));
    }

    private static String identity(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void completeFailure(ScenarioEvidence evidence, Throwable failure) throws Exception {
        var text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        evidence.retainFailureArtifact("scenario-failure.txt", text.toString().getBytes(StandardCharsets.UTF_8));
        evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL,
            List.of("The local catalog scenario terminated at a machine assertion or production boundary"),
            List.of(failure.getClass().getName() + ": " + Objects.toString(failure.getMessage(), "(no message)")),
            Map.of("contractViolation", 1L, "unexpectedError", 1L),
            "Failure is scoped to deterministic Java/filesystem composition on the recorded platform"));
    }
}
