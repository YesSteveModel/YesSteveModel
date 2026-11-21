package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ConvertedScope;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadableModelCatalogTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private AtomicSharedCache convertedCache;

    private static Path defaultFixture;
    private static Path firstFixture;
    private static Path secondFixture;

    @BeforeAll
    static void createFixtures() throws Exception {
        defaultFixture = parseFixture("/assets/ysm/builtin/default/ysm.json", "default", true);
        firstFixture = parseFixture("/assets/ysm/builtin/misc/1_alex/ysm.json", "first", false);
        secondFixture = parseFixture("/assets/ysm/builtin/misc/2_steve/ysm.json", "second", false);
    }

    @Test
    void concurrentActivationAdmitsOnlyOnce() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var coordinators = new CopyOnWriteArrayList<Thread>();
        try (var fixture = open(root, runnable -> {
            var thread = new Thread(runnable);
            coordinators.add(thread);
            return thread;
        })) {
            var callers = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var start = new CountDownLatch(1);
                var requests = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (var index = 0; index < 8; index++) {
                    requests.add(callers.submit(() -> {
                        start.await();
                        fixture.catalog().activate();
                        return null;
                    }));
                }
                start.countDown();
                for (var request : requests) {
                    request.get(10, TimeUnit.SECONDS);
                }
            } finally {
                callers.shutdownNow();
            }
            assertEquals(1, coordinators.size());
            coordinators.get(0).join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinators.get(0).isAlive());
            assertTrue(fixture.catalog().busy());
            fixture.catalog().tick();
            assertFalse(fixture.catalog().busy());
            fixture.catalog().activate();
            assertEquals(1, coordinators.size());
        }
    }

    @Test
    void scanningRequiresActivationAndBusyRequestsAreIgnored() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            assertEquals(ReloadStatus.FAILED, fixture.catalog().reload().join().status());

            fixture.catalog().activate();
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fixture.catalog().current().byModelId().size() != 1 && System.nanoTime() < deadline) {
                fixture.catalog().tick();
                Thread.onSpinWait();
            }
            assertEquals(1, fixture.catalog().current().byModelId().size());

            fixture.finish();
            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().index().entries().size());
            Files.copy(secondFixture, root.resolve("second.mxc"));
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(2, fixture.catalog().index().entries().size());
        }
    }

    @Test
    void successfulItemsPublishBeforeTheScanTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            fixture.catalog().activate();
            workers.awaitFirstBatch();
            fixture.awaitModelCount(2);

            assertTrue(fixture.catalog().busy());
            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().index().entries().size());
            assertEquals(new CatalogScanProgress(1, CatalogScanProgress.Phase.PROCESSING, 1, 2),
                fixture.catalog().scanProgress());
            workers.release.countDown();
            fixture.finish();
            assertEquals(2, fixture.catalog().current().byModelId().size());
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void conflictingCandidatesPublishOnlyTheAcceptedCandidateInEveryIndex() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("a.mxc"));
        Files.copy(firstFixture, root.resolve("b.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().index().entries().size());
            var indexed = fixture.catalog().index().entries().get(0);
            var published = fixture.catalog().current().byModelId().get(indexed.modelId());
            assertEquals(indexed.location(), published.location());
            assertEquals(indexed.identity(), published.binding().content().representation().identity());
            assertEquals(1, fixture.catalog().current().report().errorCount());
        }
    }

    @Test
    void customCandidateCannotEnterTheIndexUnderTheIntrinsicDefaultIdentity() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(defaultFixture, root.resolve("replacement.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            assertEquals(0, fixture.catalog().current().byModelId().size());
            assertTrue(fixture.catalog().index().entries().isEmpty());
            assertEquals(1, fixture.catalog().current().report().errorCount());
        }
    }

    @Test
    void idleFileCreationModificationAndDeletionNeedExplicitReload() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var coordinators = new AtomicInteger();
        try (var fixture = open(root, runnable -> {
            coordinators.incrementAndGet();
            return new Thread(runnable);
        })) {
            fixture.activateAndFinish();
            var initial = fixture.catalog().currentCandidate();
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            var file = Files.copy(firstFixture, root.resolve("first.mxc"));
            assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            Files.copy(secondFixture, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            Files.delete(file);
            assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            Files.copy(secondFixture, file);

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(2, coordinators.get());
            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertEquals(identity(secondFixture).modelId(), fixture.catalog().index().entries().get(0).modelId());
        }
    }

    private static void assertIdleUnchanged(ReloadableModelCatalog catalog, CatalogCandidate expected,
        AtomicInteger coordinators, AtomicInteger observations) {
        for (var index = 0; index < 1_000; index++) {
            catalog.tick();
        }
        assertFalse(catalog.busy());
        assertEquals(1, coordinators.get());
        assertEquals(1, observations.get());
        assertSame(expected, catalog.currentCandidate());
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX,
        org.junit.jupiter.api.condition.OS.MAC})
    void unixInvalidEntrySegmentsCannotAliasHealthyModelsOrPacks() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var healthy = Files.createDirectories(root.resolve("a"));
        Files.copy(firstFixture, root.resolve("a\\b.mxc"));
        Files.copy(secondFixture, healthy.resolve("b.mxc"));
        var invalidPack = Files.createDirectories(root.resolve("a\\b"));
        var healthyPack = Files.createDirectories(healthy.resolve("b"));
        Files.writeString(invalidPack.resolve("ysm-pack.json"), "{\"name\":\"Invalid pack\"}");
        Files.writeString(healthyPack.resolve("ysm-pack.json"), "{\"name\":\"Healthy pack\"}");
        var originalInvalid = Files.readAllBytes(root.resolve("a\\b.mxc"));
        var originalHealthy = Files.readAllBytes(healthy.resolve("b.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var snapshot = fixture.catalog().current();
            assertEquals(1, snapshot.byModelId().size());
            assertFalse(snapshot.byModelId().containsKey(identity(firstFixture).modelId()));
            var published = snapshot.byModelId().get(identity(secondFixture).modelId());
            assertEquals("a/b.mxc", published.location().path().value());
            assertEquals(healthy.resolve("b.mxc"), ((ManagedContainer)published.binding().content()).file());
            assertEquals(1, fixture.catalog().index().entries().size());
            assertEquals(identity(secondFixture).modelId(), fixture.catalog().index().entries().get(0).modelId());
            assertEquals(List.of("Healthy pack"), snapshot.packs().stream().map(pack -> pack.name()).toList());
            assertTrue(snapshot.packs().get(0).hierarchy().endsWith("/a/b"));
            var invalid = snapshot.report().errors().stream()
                .filter(error -> error.code().equals("INVALID_SOURCE_LOCATION")).toList();
            assertTrue(invalid.size() >= 2, snapshot.report().errors().toString());
            for (var error : invalid) {
                assertFalse(error.source().contains("a/b"), error.source());
                assertFalse(error.source().contains(root.toString()), error.source());
                assertFalse(error.message().contains(root.toString()), error.message());
            }
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalInvalid,
            Files.readAllBytes(root.resolve("a\\b.mxc")));
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalHealthy,
            Files.readAllBytes(healthy.resolve("b.mxc")));
    }

    @Test
    void completeInventoryRetainsObservedPacksAtScanTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.writeString(root.resolve("ysm-pack.json"), "{\"name\":\"Observed pack\",\"description\":\"kept\"}");
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            assertEquals(1, fixture.catalog().current().packs().size());
            assertEquals("Observed pack", fixture.catalog().current().packs().get(0).name());
        }
    }

    @Test
    void failedRootInventoryKeepsPublishedAuthority() throws Exception {
        var root = Files.writeString(temp.resolve("models"), "not a directory");
        try (var fixture = open(root)) {
            fixture.activateAndFinish();

            assertEquals(0, fixture.catalog().current().byModelId().size());
            assertTrue(fixture.catalog().current().report().errorCount() > 0);
            assertFalse(fixture.catalog().busy());
        }
    }

    @Test
    void observerFailureCannotRollbackCommitOrBlockAnotherObserver() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            fixture.catalog().subscribe(ignored -> {
                throw new IllegalStateException("observer failed");
            });
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            Files.copy(firstFixture, root.resolve("first.mxc"));

            var reload = fixture.catalog().reload();
            fixture.finish(reload);

            assertEquals(ReloadStatus.COMMITTED, reload.join().status());
            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertEquals(2, observations.get());
        }
    }

    @Test
    void unchangedReloadKeepsTheCompleteCandidateAndContentGraph() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var previous = fixture.catalog().currentCandidate();
            var old = previous.snapshot().byModelId().values().stream()
                .filter(record -> record.location().rootKind() == CatalogRootKind.MODELS).findFirst().orElseThrow();
            var notifications = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> notifications.incrementAndGet());

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());

            assertSame(previous, fixture.catalog().currentCandidate());
            var current = fixture.catalog().current().byModelId().get(old.binding().modelId());
            assertSame(old, current);
            assertSame(old.binding().content().representation(), current.binding().content().representation());
            assertSame(old.binding().content().modelFile().getMetadata(),
                current.binding().content().modelFile().getMetadata());
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void movingTheSourceReusesMetadataButUpdatesItsBinding() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var file = Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var previous = fixture.catalog().current().byModelId().values().stream()
                .filter(record -> record.location().rootKind() == CatalogRootKind.MODELS).findFirst().orElseThrow();
            var moved = Files.move(file, root.resolve("moved.mxc"));

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());

            var current = fixture.catalog().current().byModelId().get(previous.binding().modelId());
            assertNotSame(previous, current);
            assertEquals("moved.mxc", current.location().path().value());
            assertEquals(moved, ((ManagedContainer)current.binding().content()).file());
            assertSame(previous.binding().content().modelFile(), current.binding().content().modelFile());
            assertSame(previous.binding().content().modelFile().getFileView().getAssetView(),
                current.binding().content().modelFile().getFileView().getAssetView());
        }
    }

    @Test
    void pausedMainThreadReceivesOnlyTheLatestCompleteTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            var initial = fixture.catalog().current();
            var transitions = new CopyOnWriteArrayList<com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition>();
            fixture.catalog().subscribe(transitions::add);
            fixture.catalog().activate();
            workers.awaitFirstBatch();
            assertSame(initial, fixture.catalog().current());
            workers.release.countDown();
            workers.awaitCoordinator();
            assertTrue(fixture.catalog().busy());

            fixture.catalog().tick();

            assertFalse(fixture.catalog().busy());
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(2, transitions.size());
            assertSame(initial, transitions.get(1).previous());
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void indexPersistenceFailureRetainsOldMissingAndNewSuccessfulItems() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var first = root.resolve("first.zip");
        zipRaw(copyRawFixture(temp.resolve("raw-first")), first);
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            Files.delete(first);
            Files.copy(secondFixture, root.resolve("second.mxc"));
            try (
                var jdbc =
                    java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("cache/f/catalog.sqlite"));
                var statement = jdbc.createStatement()) {
                // Readers keep the obsolete relation visible while finalization cannot retire it.
                statement.execute("BEGIN IMMEDIATE");
                assertEquals(Set.of("models/first.zip"), convertedIndexes("catalog-owner-test").read().keySet());

                assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());

                assertEquals(2, fixture.catalog().current().byModelId().size());
                assertFalse(fixture.catalog().busy());
                assertTrue(fixture.catalog().current().report().errors().stream()
                    .anyMatch(error -> error.code().equals("CATALOG_FINALIZATION_FAILED")));
                statement.execute("ROLLBACK");
            }
        }
    }

    @Test
    void completionCallbackCanImmediatelyStartTheNextScan() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var first = fixture.catalog().reload();
            var next = new AtomicReference<CompletableFuture<ReloadResult>>();
            var callback = first.thenRun(() -> {
                next.set(fixture.catalog().reload());
                assertEquals(3, fixture.catalog().scanProgress().scanId());
            });
            fixture.finish(first);
            callback.join();
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(next.get()).status());
        }
    }

    @Test
    void closeRejectsLatePublicationAndJoinsBothExecutionCapacities() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            fixture.activateAndFinish();
            Files.copy(firstFixture, root.resolve("first.mxc"));
            Files.copy(secondFixture, root.resolve("second.mxc"));
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            var completion = fixture.catalog().reload();
            workers.awaitFirstBatch();
            fixture.catalog().close();
            fixture.catalog().close();
            assertEquals(ReloadStatus.FAILED, completion.join().status());
            assertTrue(workers.threads.stream().noneMatch(Thread::isAlive));
            fixture.catalog().tick();
            assertTrue(fixture.catalog().current().byModelId().isEmpty());
            assertEquals(1, observations.get());
            assertEquals(ReloadStatus.FAILED, fixture.catalog().reload().join().status());
        } finally {
            workers.release.countDown();
        }
    }

    private CatalogFixture open(Path root) throws Exception {
        return open(root, (ThreadFactory)Thread::new);
    }

    private CatalogFixture open(Path root, ThreadFactory threads) throws Exception {
        return open(root,
            (reconciler, initial) -> threads instanceof ControlledThreads controlled
                ? new ReloadableModelCatalog(reconciler, initial, threads, controlled::newExecutor)
                : new ReloadableModelCatalog(reconciler, initial, threads));
    }

    private CatalogFixture open(Path root,
        BiFunction<CatalogReconciler, CatalogCandidate, ReloadableModelCatalog> factory) throws Exception {
        return open(List.of(new ModelCatalogSource(CatalogRootKind.MODELS, root, false)), factory);
    }

    private CatalogFixture open(List<ModelCatalogSource> roots,
        BiFunction<CatalogReconciler, CatalogCandidate, ReloadableModelCatalog> factory) throws Exception {
        var indexes = convertedIndexes("catalog-owner-test");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), indexes,
            new ConvertedObjectStore(convertedCache));
        var intrinsicDefault =
            openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        var reconciler = new CatalogReconciler(roots, resolver, Set.of(intrinsicDefault.modelId()), intrinsicDefault,
            CatalogIndexSnapshot.empty());
        var initial = reconciler.materializeBuiltins();
        var catalog = factory.apply(reconciler, initial);
        return new CatalogFixture(catalog);
    }

    private ConvertedSourceIndexStore convertedIndexes(String version) {
        if (convertedCache == null)
            convertedCache =
                com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(temp.resolve("cache")));
        return new ConvertedSourceIndexStore(ConvertedScope.of(temp, version), convertedCache);
    }

    @Test
    void linkedModelsRootsPublishCompleteContainersForBothCreationPolicies() throws Exception {
        var expected = identity(firstFixture).modelId();
        var original = Files.readAllBytes(firstFixture);
        for (var kind : List.of(CatalogRootKind.MODELS)) {
            for (var create : new boolean[] {false, true}) {
                var suffix = kind + "-" + create;
                var target = Files.createDirectories(temp.resolve("outside-" + suffix));
                Files.createSymbolicLink(target.resolve("alias.mxc"), firstFixture);
                var root = temp.resolve("root-" + suffix);
                createDirectoryLink(root, target);
                try (var fixture = open(List.of(new ModelCatalogSource(kind, root, create)),
                    (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new))) {
                    fixture.activateAndFinish();
                    assertEquals(0, fixture.catalog().current().report().errorCount(),
                        fixture.catalog().current().report().errors().toString());
                    assertEquals(new CatalogLocation(kind, new ModelPath("alias.mxc")),
                        fixture.catalog().current().byModelId().get(expected).location());
                    assertEquals("alias.mxc", fixture.catalog().index().entries().get(0).location().path().value());
                    assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
                    assertEquals(firstFixture, fixture.catalog().index().entries().get(0).backingFile());
                }
                assertEquals(target.toRealPath(), root.toRealPath());
                assertTrue(Files.isSymbolicLink(target.resolve("alias.mxc")));
                Files.delete(root);
            }
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(firstFixture));
    }

    @Test
    void linkedGroupRawEntryPackAndArchivePublishTheSameLogicalHierarchy() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var physical = Files.createDirectories(temp.resolve("auth-storage/deep"));
        var raw = copyRawFixture(temp.resolve("work/physical-name"));
        Files.createSymbolicLink(physical.resolve("hero"), raw);
        var manifest = Files.writeString(temp.resolve("pack-manifest.json"), "{\"name\":\"Linked pack\"}");
        var cover = Files.write(temp.resolve("pack-cover.data"), Files.readAllBytes(raw.resolve("textures/gsl.png")));
        Files.createSymbolicLink(physical.resolve("ysm-pack.json"), manifest);
        Files.createSymbolicLink(physical.resolve("ysm-pack.png"), cover);
        Files.createSymbolicLink(root.resolve("intermediate"), Path.of("../auth-storage"));
        Files.createSymbolicLink(root.resolve("team"), Path.of("intermediate"));
        var archive = temp.resolve("physical-archive.data");
        zipRaw(copyRawFixture(temp.resolve("zip-source")), archive);
        // The duplicate raw identity still reaches the candidate boundary in the separate archive scan.
        var archiveRoot = Files.createDirectory(temp.resolve("archive-root"));
        Files.createSymbolicLink(archiveRoot.resolve("different-name.zip"), archive);
        var expected = identity(firstFixture).modelId();
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var record = fixture.catalog().current().byModelId().get(expected);
            assertTrue(record.location().path().value().equals("team/deep/hero")
                || record.location().path().value().equals("intermediate/deep/hero"));
            assertEquals(CatalogRootKind.MODELS, record.location().rootKind());
            assertEquals(1, fixture.catalog().index().entries().size());
            assertEquals(Set.of("local:team/deep", "local:intermediate/deep"),
                fixture.catalog().current().packs().stream()
                    .map(com.elfmcys.ysm.model.domain.ModelPackDescriptor::hierarchy)
                    .collect(java.util.stream.Collectors.toSet()));
            assertTrue(fixture.catalog().current().packs().stream().allMatch(
                pack -> pack.coverHash().equals(com.elfmcys.ysm.model.storage.ModelHashing.blake3(readBytes(cover)))));
            var index = convertedIndexes("catalog-owner-test").read();
            assertEquals(Set.of("models/" + record.location().path().value()), index.keySet());
            assertEquals(expected, index.values().iterator().next().modelId());
        }
        try (var fixture = open(archiveRoot)) {
            fixture.activateAndFinish();
            assertEquals(0, fixture.catalog().current().report().errorCount(),
                fixture.catalog().current().report().errors().toString());
            assertEquals(new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("different-name.zip")),
                fixture.catalog().current().byModelId().get(expected).location());
        }
    }

    @Test
    void retargetedEntryFailsItsOwnObservationAndNextExplicitReloadUsesTheNewTarget() throws Exception {
        for (var mutation : List.of("retarget", "delete", "replace")) {
            verifyAcceptedObservationMutation(mutation);
        }
    }

    private void verifyAcceptedObservationMutation(String mutation) throws Exception {
        var scenario = Files.createDirectory(temp.resolve(mutation));
        var root = Files.createDirectory(scenario.resolve("models"));
        var physical = Files.copy(firstFixture, scenario.resolve("physical.mxc"));
        var link = Files.createSymbolicLink(root.resolve("alias.mxc"), physical);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = open(root,
            (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new,
                (sequence, policy) -> new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>()) {
                    @Override
                    protected void beforeExecute(Thread thread, Runnable runnable) {
                        if (sequence == 2) {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }))) {
            fixture.activateAndFinish();
            var reload = fixture.catalog().reload();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            switch (mutation) {
                case "retarget" -> {
                    Files.delete(link);
                    Files.createSymbolicLink(link, secondFixture);
                }
                case "delete" -> Files.delete(physical);
                case "replace" ->
                    Files.copy(secondFixture, physical, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                default -> throw new IllegalArgumentException(mutation);
            }
            release.countDown();
            assertEquals(ReloadStatus.FAILED, fixture.finish(reload).status(), mutation);
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
            assertFalse(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            if (mutation.equals("delete"))
                Files.copy(secondFixture, physical);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status(), mutation);
            assertFalse(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            System.out.println("ACCEPTED_OBSERVATION_MUTATION " + mutation + " rejected-then-recovered");
        } finally {
            release.countDown();
        }
    }

    @Test
    void firstCompleteAdmissionReplacesOldCurrentAndLaterRegionCannotStealIt() throws Exception {
        for (var globalWins : new boolean[] {false, true}) {
            var scenario = Files.createDirectory(temp.resolve(globalWins ? "global-first" : "local-first"));
            var global = Files.createDirectory(scenario.resolve("global"));
            var local = Files.createDirectory(scenario.resolve("local"));
            var original = Files.copy(firstFixture, local.resolve("same.mxc"));
            var entered = new CountDownLatch(2);
            var gates = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
            var roots =
                List.of(new ModelCatalogSource(com.elfmcys.ysm.model.catalog.source.CatalogRoot.GLOBAL, global, false),
                    new ModelCatalogSource(CatalogRootKind.MODELS, local, false));
            try (var fixture = open(roots,
                (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new,
                    (sequence, policy) -> new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>()) {
                        private final AtomicInteger inputs = new AtomicInteger();

                        @Override
                        public void execute(Runnable runnable) {
                            var index = inputs.getAndIncrement();
                            super.execute(() -> {
                                if (sequence == 2) {
                                    entered.countDown();
                                    try {
                                        gates[index].await();
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                runnable.run();
                            });
                        }
                    }))) {
                fixture.activateAndFinish();
                var id = identity(firstFixture).modelId();
                var old = fixture.catalog().current().byModelId().get(id);
                var source = (ManagedContainer)old.binding().content();
                try (var preview = TestPreviews.blank()) {
                    ModelExporter.export(source, preview, global.resolve("same.mxc"), "global-second-round");
                    ModelExporter.export(source, preview, original, "local-second-round");
                }
                var expectedPath = globalWins ? "global:same.mxc" : "local:same.mxc";
                var expectedIdentity = identity(globalWins ? global.resolve("same.mxc") : original);
                var reload = fixture.catalog().reload();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                fixture.catalog().tick();
                assertSame(old, fixture.catalog().current().byModelId().get(id));
                gates[globalWins ? 0 : 1].countDown();
                await(() -> {
                    fixture.catalog().tick();
                    return fixture.catalog().current().byModelId().get(id).binding().content().representation()
                        .identity().equals(expectedIdentity);
                });
                assertEquals(expectedPath, fixture.catalog().current().byModelId().get(id).entry().path().value());
                gates[globalWins ? 1 : 0].countDown();
                assertEquals(ReloadStatus.COMMITTED, fixture.finish(reload).status());
                assertEquals(expectedPath, fixture.catalog().current().byModelId().get(id).entry().path().value());
                assertEquals(CatalogAccess.PUBLIC, fixture.catalog().current().byModelId().get(id).entry().access());
                assertEquals(1,
                    fixture.catalog().current().report().errors().stream()
                        .filter(
                            error -> error.category() == com.elfmcys.ysm.model.domain.ModelScanError.Category.CONFLICT)
                        .count());
            } finally {
                gates[0].countDown();
                gates[1].countDown();
            }
        }
    }

    @Test
    void globalAndInstanceRootsKeepFileExtensionsPacksAndActualStorageBindings() throws Exception {
        var shared = temp.resolve("PHYSICAL_SECRET_shared");
        var paths = com.elfmcys.ysm.AssetPaths.discover(temp.resolve("game-one"), temp.resolve("config"),
            temp.resolve("builtin"), Map.of("YSM_HOME", shared.toString()),
            com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        var roots = com.elfmcys.ysm.model.catalog.source.ModelCatalogSources.reloadableSources(paths);
        assertEquals(2, roots.size());
        for (int i = 0; i < roots.size(); i++) {
            var root = roots.get(i);
            Files.createDirectories(root.path().resolve("team"));
            Files.writeString(root.path().resolve("team/ysm-pack.json"), "{\"name\":\"Pack " + i + "\"}");
            var content = com.elfmcys.ysm.model.service.ExportTestFixture.create(firstFixture,
                root.path().resolve("team/same.mxc"), "team/same.mxc", 41 + i,
                com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.RAW, root.rootKind());
            content.representation().close();
        }
        var globalModels = paths.global().orElseThrow().modelsRoot();
        var raw = copyRawFixture(globalModels.resolve("same"));
        var manifest = raw.resolve("ysm.json");
        Files.writeString(manifest, Files.readString(manifest).replace("height_scale\": 1", "height_scale\": 1.25"));
        Files.copy(firstFixture, globalModels.resolve("same.mxc"));
        Files.copy(secondFixture, globalModels.resolve("same.ysm"));
        var archiveRaw = copyRawFixture(temp.resolve("archive-raw"));
        var archiveManifest = archiveRaw.resolve("ysm.json");
        Files.writeString(archiveManifest,
            Files.readString(archiveManifest).replace("height_scale\": 1", "height_scale\": 1.5"));
        zipRaw(archiveRaw, globalModels.resolve("same.zip"));
        try (var storage = com.elfmcys.ysm.model.storage.ModelStorageInfra.open(paths)) {
            var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), storage);
            var intrinsic =
                openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            var reconciler = new CatalogReconciler(roots, resolver, Set.of(intrinsic.modelId()), intrinsic,
                CatalogIndexSnapshot.empty());
            storage.registerCaches();
            try (var fixture = new CatalogFixture(
                new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins(), Thread::new))) {
                fixture.activateAndFinish();
                assertEquals(0, fixture.catalog().current().report().errorCount(),
                    fixture.catalog().current().report().errors().toString());
                var expected = Set.of("global:team/same.mxc", "local:team/same.mxc", "global:same", "global:same.mxc",
                    "global:same.ysm", "global:same.zip");
                assertEquals(expected, fixture.catalog().current().byPath().keySet().stream().map(HierarchyPath::value)
                    .collect(java.util.stream.Collectors.toSet()));
                assertEquals(Set.of("global:team", "local:team"),
                    fixture.catalog().current().packs().stream()
                        .map(com.elfmcys.ysm.model.domain.ModelPackDescriptor::hierarchy)
                        .collect(java.util.stream.Collectors.toSet()));
                Map.of("global:team/same.mxc", "29", "local:team/same.mxc", "2a")
                    .forEach((address, marker) -> assertEquals(ModelId.parse("hash:" + marker + "00".repeat(31)),
                        fixture.catalog().current().byPath().get(new HierarchyPath(address))));
                assertEquals(Map.of("global:team", "Pack 0", "local:team", "Pack 1"),
                    fixture.catalog().current().packs().stream()
                        .collect(java.util.stream.Collectors.toMap(
                            com.elfmcys.ysm.model.domain.ModelPackDescriptor::hierarchy,
                            com.elfmcys.ysm.model.domain.ModelPackDescriptor::name)));
                for (var record : fixture.catalog().current().byModelId().values()) {
                    if (record.location().rootKind() == CatalogRootKind.BUILTIN)
                        continue;
                    var binding = record.binding().content().storageBinding();
                    assertEquals(record.location(), binding.localSource().orElseThrow().key().root()
                        .location(binding.localSource().orElseThrow().key().relativePath()));
                    assertSame(record.location().value().startsWith("global:") ? storage.remote() : storage.local(),
                        binding.region());
                }
                var server = new com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog(fixture.catalog().current());
                assertEquals(com.elfmcys.ysm.model.catalog.source.ModelSelector.Status.AMBIGUOUS,
                    server.resolvePath("team/same.mxc").status());
                assertTrue(server.findPath("same").isPresent());
                assertTrue(server.findPath("same.mxc").isPresent());
                assertTrue(server.findPath("same.ysm").isPresent());
                assertTrue(server.findPath("same.zip").isPresent());
                var rawId = server.findPath("same").orElseThrow().modelId();
                var object =
                    ((ManagedContainer)fixture.catalog().current().binding(rawId).orElseThrow().content()).file();
                var before = sha256(object);
                var modified = Files.getLastModifiedTime(object);
                var secondPaths = com.elfmcys.ysm.AssetPaths.discover(temp.resolve("game-two"),
                    temp.resolve("config-two"), temp.resolve("builtin"), Map.of("YSM_HOME", shared.toString()),
                    com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
                try (var secondStorage = com.elfmcys.ysm.model.storage.ModelStorageInfra.open(secondPaths)) {
                    var secondResolver =
                        new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), secondStorage);
                    var secondRoots =
                        com.elfmcys.ysm.model.catalog.source.ModelCatalogSources.reloadableSources(secondPaths);
                    var secondIntrinsic = openIndexed(defaultFixture,
                        new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
                    var secondReconciler = new CatalogReconciler(secondRoots, secondResolver,
                        Set.of(secondIntrinsic.modelId()), secondIntrinsic, CatalogIndexSnapshot.empty());
                    secondStorage.registerCaches();
                    try (var second = new CatalogFixture(new ReloadableModelCatalog(secondReconciler,
                        secondReconciler.materializeBuiltins(), Thread::new))) {
                        second.activateAndFinish();
                        var adopted = second.catalog().current().byModelId().get(rawId);
                        assertEquals("global:same", adopted.entry().path().value());
                        assertEquals(object, ((ManagedContainer)adopted.binding().content()).file());
                        assertEquals(before, sha256(object));
                        assertEquals(modified, Files.getLastModifiedTime(object));
                        assertTrue(second.catalog().current().byPath().keySet().stream()
                            .noneMatch(path -> path.value().startsWith("local:")));
                    }
                }
            }
        }
    }

    private static Path copyRawFixture(Path target) throws Exception {
        var source = Path
            .of(Objects.requireNonNull(
                ReloadableModelCatalogTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI())
            .getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    @Test
    void userSourcesCannotOccupyReservedBuiltinIdentityButMayReuseItsRelativeName() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("forbidden-identity.mxc"));
        Files.copy(secondFixture, root.resolve("reserved.mxc"));
        var builtinLocation = new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("reserved.mxc"));
        var builtinIndex = new CatalogIndexSnapshot(
            List.of(new CatalogIndexEntry(identity(firstFixture), builtinLocation, firstFixture)), List.of(),
            ModelScanReport.empty());
        var indexes = convertedIndexes("test");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), indexes,
            new ConvertedObjectStore(convertedCache));
        var intrinsic =
            openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        var reconciler = new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.MODELS, root, false)),
            resolver, Set.of(intrinsic.modelId(), identity(firstFixture).modelId()), intrinsic, builtinIndex);
        try (var fixture =
            new CatalogFixture(new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins(), Thread::new))) {
            fixture.activateAndFinish();
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(builtinLocation,
                fixture.catalog().current().byModelId().get(identity(firstFixture).modelId()).location());
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            assertEquals(1, fixture.catalog().current().report().errorCount());
            assertEquals(
                Set.of(builtinLocation, new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("reserved.mxc"))),
                Set.copyOf(fixture.catalog().index().entries().stream().map(CatalogIndexEntry::location).toList()));
        }
    }

    @Test
    void deniedBranchKeepsOldContentWhileHealthySiblingPublishes() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var branch = Files.createDirectory(root.resolve("denied"));
        Files.copy(firstFixture, branch.resolve("old.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            try (var denied = denyDirectoryRead(branch)) {
                org.junit.jupiter.api.Assertions.assertThrows(java.nio.file.AccessDeniedException.class, () -> {
                    try (var ignored = Files.newDirectoryStream(branch)) {
                    }
                });
                Files.copy(secondFixture, root.resolve("healthy.mxc"));
                assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
                assertEquals(2, fixture.catalog().current().byModelId().size());
                assertTrue(fixture.catalog().current().report().errors().stream()
                    .anyMatch(error -> error.source().contains("denied")));
            }
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
        }
    }

    private static AutoCloseable denyDirectoryRead(Path directory) throws Exception {
        var acl = Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var original = acl.getAcl();
            var denied = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.DENY).setPrincipal(Files.getOwner(directory))
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build();
            var entries = new java.util.ArrayList<>(original);
            entries.add(0, denied);
            acl.setAcl(entries);
            return () -> acl.setAcl(original);
        }
        var original = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, Set.of());
        return () -> Files.setPosixFilePermissions(directory, original);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.links.junctionRoot", matches = ".+")
    void precreatedWindowsJunctionPublishesCompleteContentOnTheCurrentProvider() throws Exception {
        var root = Path.of(System.getProperty("ysm.links.junctionRoot"));
        var entry = Files.copy(firstFixture, root.resolve("junction-hero.mxc"));
        var expected = ModelId.parse("hash:f033b14236c5610e4868a3f829fbec1fc773a092290d04f8e53618a8d4e7daf7");
        var before = sha256(entry);
        try {
            for (var kind : List.of(CatalogRootKind.MODELS)) {
                for (var create : new boolean[] {false, true}) {
                    try (var fixture = open(List.of(new ModelCatalogSource(kind, root, create)),
                        (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new))) {
                        fixture.activateAndFinish();
                        assertEquals(0, fixture.catalog().current().report().errorCount(),
                            fixture.catalog().current().report().errors().toString());
                        var record = Objects.requireNonNull(fixture.catalog().current().byModelId().get(expected));
                        assertEquals(new CatalogLocation(kind, new ModelPath("junction-hero.mxc")), record.location());
                        assertEquals("junction-hero.mxc", record.entry().path().value());
                        var content = record.binding().content();
                        long count = 0;
                        for (var chunk : content.modelFile().getFileView().getAssetView().getChunkTable().values()) {
                            if (chunk.type().equals(
                                com.elfmcys.ysm.format.container.AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                                continue;
                            {
                                var bytes =
                                    content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                                count += bytes.size();
                            }
                        }
                        assertTrue(count > 0);
                        assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
                        assertEquals(record.location(), fixture.catalog().index().entries().get(0).location());
                        assertEquals(before, sha256(entry));
                        System.out.printf(
                            "WINDOWS_JUNCTION kind=%s create=%s path=junction-hero.mxc modelId=%s verifiedBytes=%d%n",
                            kind, create, expected, count);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(entry);
        }
        var nested = root.resolve("nested-junction");
        assertTrue(Files.isDirectory(nested), "Windows must create the nested junction before this run");
        copyRawFixture(root.toRealPath());
        var rawRoot = Files.createDirectory(temp.resolve("cross-provider-raw"));
        Files.createSymbolicLink(rawRoot.resolve("raw-entry"), root);
        try (var fixture = open(rawRoot)) {
            fixture.activateAndFinish();
            assertFalse(fixture.catalog().current().byModelId().containsKey(expected));
            assertTrue(fixture.catalog().current().report().errorCount() > 0);
            Files.delete(nested);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals("raw-entry", fixture.catalog().current().byModelId().get(expected).location().path().value());
            System.out.printf("WINDOWS_JUNCTION raw-entry=accepted nested-junction=rejected modelId=%s%n", expected);
        }
    }

    @Test
    void rootFailureKeepsPublishedContentAndRecoversOnlyOnExplicitReload() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("old.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var saved = Files.move(root, temp.resolve("saved"));
            Files.writeString(root, "not a directory");
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(1, fixture.catalog().current().byModelId().size());
            Files.delete(root);
            Files.move(saved, root);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
        }
    }

    @Test
    void linkedLegacyFixturePublishesAndReadsCompleteContentWithItsLogicalLocation() throws Exception {
        var configured = System.getenv("YSM_LEGACY_LINK_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null && !configured.isBlank());
        var source = Path.of(configured).toRealPath();
        var expectedModelId = ModelId.parse(Objects.requireNonNull(System.getenv("YSM_LEGACY_LINK_MODEL_ID")));
        var expectedSourceHash = Objects.requireNonNull(System.getenv("YSM_LEGACY_LINK_SHA256"));
        assertEquals(expectedSourceHash, sha256(source));
        assertEquals(com.elfmcys.ysm.format.legacy.LegacyContainerHeader.Version.V3_ENCRYPTED,
            com.elfmcys.ysm.format.legacy.LegacyContainerHeader.probe(source));
        for (var kind : List.of(CatalogRootKind.MODELS)) {
            var root = Files.createDirectory(temp.resolve("models-" + kind));
            var externalGroup = Files.createDirectory(temp.resolve("group-" + kind));
            var physical = Files.copy(source, temp.resolve("physical-" + kind + ".data"));
            var entry = Files.createSymbolicLink(externalGroup.resolve("logical.ysm"), physical);
            var group = root.resolve("team");
            createDirectoryLink(group, externalGroup);
            try (var fixture = open(List.of(new ModelCatalogSource(kind, root, false)),
                (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new))) {
                fixture.activateAndFinish();
                assertEquals(0, fixture.catalog().current().report().errorCount(),
                    fixture.catalog().current().report().errors().toString());
                var expectedLocation = new CatalogLocation(kind, new ModelPath("team/logical.ysm"));
                var record = Objects.requireNonNull(fixture.catalog().current().byModelId().get(expectedModelId));
                assertEquals(expectedLocation, record.location());
                assertEquals(new HierarchyPath("local:team/logical.ysm"), record.entry().path());
                assertEquals(CatalogAccess.PUBLIC, record.entry().access());
                assertEquals(expectedModelId, fixture.catalog().current().byLocation().get(expectedLocation));
                var index = fixture.catalog().index().entries().get(0);
                assertEquals(expectedLocation, index.location());
                assertEquals(expectedModelId, index.modelId());
                var converted = convertedIndexes("catalog-owner-test").read();
                assertEquals(index.identity(), converted.get(kind.namespace() + "/team/logical.ysm").identity());
                var content = record.binding().content();
                long verifiedBytes = 0;
                for (var chunk : content.modelFile().getFileView().getAssetView().getChunkTable().values()) {
                    if (chunk.type()
                        .equals(com.elfmcys.ysm.format.container.AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                        continue;
                    }
                    {
                        var bytes = content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                        verifiedBytes += bytes.size();
                    }
                }
                assertTrue(verifiedBytes > 0);
                assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
                assertEquals(expectedModelId, fixture.catalog().current().byLocation().get(expectedLocation));
                System.out.printf("LEGACY_LINK rootKind=%s path=%s modelId=%s containerId=%s verifiedBytes=%d%n", kind,
                    record.entry().path(), expectedModelId, index.identity().containerId(), verifiedBytes);
            } finally {
                Files.delete(group);
            }
            assertEquals(expectedSourceHash, sha256(physical));
            assertTrue(Files.isSymbolicLink(entry));
            assertEquals(physical, Files.readSymbolicLink(entry));
        }
        assertEquals(expectedSourceHash, sha256(source));
    }

    private static String sha256(Path path) throws Exception {
        return java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void zipRaw(Path source, Path target) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(target));
            var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(source.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path parseFixture(String resource, String name, boolean builtin) throws Exception {
        var manifest = ReloadableModelCatalogTest.class.getResource(resource);
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            var output = Files.createDirectories(fixtureTemp.resolve(name));
            var parsed = builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
            if (builtin)
                return parsed;
            var location = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath(name));
            var content = ManagedContainer.openDirect(parsed, location);
            try {
                return ModelExporter.export(content, TestPreviews.blank(), output.resolve(name + "-portable.mxc"),
                    null);
            } finally {
                content.representation().close();
            }
        }
    }

    private static ManagedContainer openIndexed(Path file, CatalogLocation location) throws Exception {
        return ManagedContainer.openIndexed(new CatalogIndexEntry(identity(file), location, file));
    }

    private static ModelFileIdentity identity(Path file) throws Exception {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }

    private record CatalogFixture(ReloadableModelCatalog catalog) implements AutoCloseable {
        private void activateAndFinish() throws Exception {
            catalog.activate();
            finish();
        }

        private void finish() throws Exception {
            await(() -> {
                catalog.tick();
                return !catalog.busy();
            });
        }

        private void awaitModelCount(int count) throws Exception {
            await(() -> {
                catalog.tick();
                return catalog.current().byModelId().size() == count - 1;
            });
        }

        private ReloadResult finish(CompletableFuture<ReloadResult> completion) throws Exception {
            await(() -> {
                catalog.tick();
                return completion.isDone();
            });
            return completion.join();
        }

        @Override
        public void close() {
            catalog.close();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "Timed out awaiting catalog progress");
    }

    private static final class ControlledThreads implements ThreadFactory {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CopyOnWriteArrayList<Thread> threads = new CopyOnWriteArrayList<>();
        private final AtomicInteger completed = new AtomicInteger();
        private Thread coordinator;

        @Override
        public Thread newThread(Runnable runnable) {
            coordinator = new Thread(runnable);
            threads.add(coordinator);
            return coordinator;
        }

        private java.util.concurrent.ExecutorService newExecutor(long sequence,
            com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy policy) {
            var started = new AtomicInteger();
            return new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
                    var thread = new Thread(runnable);
                    threads.add(thread);
                    return thread;
                }) {
                @Override
                protected void beforeExecute(Thread thread, Runnable runnable) {
                    if (started.incrementAndGet() != 2) {
                        return;
                    }
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                protected void afterExecute(Runnable runnable, Throwable failure) {
                    completed.incrementAndGet();
                }
            };
        }

        private void awaitFirstBatch() throws Exception {
            assertTrue(blocked.await(10, TimeUnit.SECONDS));
            await(() -> completed.get() == 1);
        }

        private void awaitCoordinator() throws Exception {
            coordinator.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinator.isAlive());
        }
    }
}
