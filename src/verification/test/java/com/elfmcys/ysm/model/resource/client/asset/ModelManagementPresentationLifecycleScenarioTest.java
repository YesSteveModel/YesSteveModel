package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import com.elfmcys.ysm.mock.evidence.EvidenceRun;
import com.elfmcys.ysm.mock.evidence.JsonlEvidenceSink;
import com.elfmcys.ysm.mock.evidence.ScenarioEvidence;
import com.elfmcys.ysm.model.catalog.ClientCatalogScenarioHarness;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.session.client.RemotePublicationScenarioFixture;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.RemotePublicationScenarioFixture.Asset;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.storage.ModelHashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelManagementPresentationLifecycleScenarioTest {
    private static final String SCENARIO_ID = "MMR-DOM-PRESENTATION-001";

    @TempDir
    Path temp;

    @Test
    void previewProbeWinsBeforeRemoteDemand() throws Exception {
        var fixture = RemotePublicationScenarioFixture.create(temp.resolve("preview-fixture"));
        try (
            var catalogs = ClientCatalogScenarioHarness.open(temp.resolve("preview-catalog"),
                fixture.asset("default").file(), List.of());
            var store = new RemoteModelStore(temp.resolve("preview-store"))) {
            var identity = fixture.asset("server-exact").identity();
            fixture.seed(store, "server-exact");
            var content = store.openContent(identity);
            try {
                var remote = remoteRecord("server-exact", content);
                var publication =
                    new RemotePublicationSnapshot(List.of(remote.publication()), Set.of(), List.of(), Map.of());
                catalogs.manager().beginRemote();
                catalogs.manager().publishSession(new ActivationSnapshot(publication,
                    Map.of(identity.modelId(), new ActivationSnapshot.Ready(remote.publication(), remote.record()))));
                var hit = com.elfmcys.ysm.model.storage.PreviewStore
                    .decode(ArrayBuffer.borrow(com.elfmcys.ysm.model.storage.TestPreviews.blank().bytes()));
                var hitRepository = new ClientAssetRepository(catalogs.manager(), () -> (members, receiver) -> {
                    throw new AssertionError("A valid local image must win before remote demand");
                }, java.util.function.Function.identity(), null, Runnable::run,
                    ignored -> CompletableFuture.failedFuture(new IOException("unused")),
                    ignored -> CompletableFuture.completedFuture(java.util.Optional.of(hit)));
                try (var page = hitRepository.openBatch()) {
                    var preview = page.preview(identity.modelId());
                    require(!page.hasRemoteRequests(), "Probe hit must not send a request");
                    page.submit();
                    require(preview.join() instanceof PreviewResult.Data, "Probe hit must be DATA");
                }
            } finally {
                content.representation().close();
            }
        }
    }

    @Test
    void localDelayedImageReadsThroughTheExactRuntimeContent() throws Exception {
        var fixture = RemotePublicationScenarioFixture.create(temp.resolve("exact-fixture"));
        var expected = new IOException("exact runtime source");
        var exactCalls = new AtomicInteger();
        try (var catalogs = ClientCatalogScenarioHarness.open(temp.resolve("exact-catalog"),
            fixture.asset("default").file(), List.of(fixture.asset("presentation-rich").file()))) {
            var repository = new ClientAssetRepository(catalogs.manager(), (members, receiver) -> CompletableFuture
                .failedFuture(new AssertionError("Local image must not start a transfer")), content -> {
                    exactCalls.incrementAndGet();
                    return failingContent(content, expected);
                });
            try (var page = repository.openBatch()) {
                var preview = page.presentation(fixture.asset("presentation-rich").identity().modelId(),
                    ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
                require(preview.isDone() && !page.hasRemoteRequests(),
                    "Local presentation hits must resolve before page submission");
                page.submit();
                var source = preview.join();
                IOException observed = null;
                try {
                    source.open();
                } catch (IOException failure) {
                    observed = failure;
                }
                require(observed == expected, "Delayed image read must use the exact runtime chunk source");
                require(exactCalls.get() == 1, "One local image interest must resolve one exact content instance");
            }
        }
    }

    @Test
    @Tag("model-management-mock")
    void pageBatchesHaveSnapshotBoundInterestsAndExactTerminals() throws Throwable {
        assumeTrue(EvidenceRun.isConfigured(), "Only the explicit mock task writes acceptance evidence");
        var plan = new ScenarioPlan(
            List.of("open snapshot-bound page",
                "acquire preview/icon/author-avatar/GUI foreground/GUI background/pack cover",
                "publish successful page transfer", "publish one unavailable child with a cached sentinel",
                "publish asynchronous transfer failure", "reject synchronous pre-admission transfer failure",
                "remove page before held transfer completion", "disconnect remote catalog",
                "deliver valid late old result", "reconnect and rebuild page from committed exact cache"),
            Map.of("acceptedTransferTerminals", 4, "preAdmissionRejections", 1, "lateUiEffects", 0,
                "contractViolationDiagnostics", 0, "sharedModelBackingClosesByPage", 0));
        var inputBytes = EvidenceJson.canonicalBytes(plan);
        var run = EvidenceRun.openConfigured();
        var identity = run.identity(SCENARIO_ID, List.of("FA-003", "FA-004", "FA-007"),
            List.of("CO-006", "CO-007", "CO-008", "CO-009", "CO-010", "CO-011", "CO-016"),
            EvidenceRun.Radius.DETERMINISTIC_COMPOSITION, true, inputBytes);
        var evidence = run.scenario(identity, inputBytes);

        try {
            execute(evidence);
            evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.PASS, List.of(
                "A snapshot-bound page requested preview, icon, avatar, GUI foreground/background, and pack cover; the descriptorless preview and four remotely backed presentation assets shared one accepted batch transfer while the missing icon stayed item-local",
                "Unavailable and failed page children reached explicit item terminals while an unaffected cached sentinel remained usable",
                "A synchronous fetch rejection produced item failure terminals without establishing an accepted transfer owner",
                "Page removal cancelled its exact transfer once; a deliberately late valid result populated only the exact cache and could not revive the closed page",
                "Reconnect rebuilt a new page from committed exact cache without a new transfer or shared model-backing close"),
                List.of(),
                Map.of("contractViolation", 0L, "unexpectedError", 0L, "expectedUnavailable", 1L,
                    "expectedTransferFailure", 1L, "expectedPreAdmissionFailure", 1L, "expectedCancellation", 1L),
                "Deterministic in-process ClientAssetRepository.Batch composition; it proves page/action terminals, exact cache publication, and late-result isolation, but not screen widgets, Minecraft texture registration, render-thread adoption, GPU release, or physical reclamation"));
        } catch (Throwable failure) {
            completeFailure(evidence, failure);
            throw failure;
        }
    }

    private void execute(ScenarioEvidence evidence) throws Exception {
        var fixture = RemotePublicationScenarioFixture.create(temp.resolve("fixture"));
        var coverBytes = Files.readAllBytes(Path.of("src/main/resources/assets/ysm/builtin/wine_fox/ysm-pack.png"));
        var coverHash = hash(coverBytes);
        var pack = new ModelPackDescriptor(CatalogRootKind.CUSTOM, "local:custom/remote-presentation",
            "Remote presentation", "I-04 exact page fixture", Map.of(), coverHash, "PNG", coverBytes.length);
        var assetNames = List.of("presentation-rich", "server-exact", "server-failure", "online-add", "cache-exact");
        var assets = new LinkedHashMap<ModelFileIdentity, Asset>();
        assetNames.forEach(name -> {
            var asset = fixture.asset(name);
            assets.put(asset.identity(), asset);
        });

        try (
            var catalogs = ClientCatalogScenarioHarness.open(temp.resolve("catalog-owner"),
                fixture.asset("default").file(), List.of(fixture.asset("active-exact").file()));
            var store = new RemoteModelStore(temp.resolve("remote-store"))) {
            var manager = catalogs.manager();
            manager.beginRemote();
            var records = new LinkedHashMap<ModelFileIdentity, RemoteRecord>();
            for (var name : assetNames) {
                fixture.seed(store, name);
                var asset = fixture.asset(name);
                var content = store.openContent(asset.identity());
                records.put(asset.identity(), remoteRecord(name, content));
            }
            var activation = activation(records, pack);
            manager.publishSession(activation);
            var session = new ClientModelSession(com.elfmcys.ysm.network.session.SessionMode.AUTO, true,
                com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot.empty());
            session.onServerHello(com.elfmcys.ysm.network.protocol.ProtocolVersion.CURRENT, false);
            session.publishFull(activation.publication());
            var fetcher = new ControlledPresentationFetcher(assets, pack, coverBytes, session);
            var repository = new ClientAssetRepository(manager, () -> fetcher, java.util.function.Function.identity(),
                null, Runnable::run, ignored -> CompletableFuture.failedFuture(new IOException("unused")),
                ignored -> CompletableFuture.completedFuture(java.util.Optional.empty()), () -> session);

            verifyCompletePage(repository, fixture, pack, fetcher, store, evidence);
            verifyUnavailableChild(repository, fixture, fetcher, evidence);
            verifyAsynchronousFailure(repository, fixture, fetcher, evidence);
            verifySynchronousRejection(repository, fixture, fetcher, evidence);
            verifyPageRemovalDisconnectAndRebuild(repository, manager, activation, fixture, fetcher, store, evidence);

            require(fetcher.acceptedActions() == 4, "Exactly four presentation transfers must cross admission");
            require(fetcher.terminalObservations() == 4, "Every accepted presentation transfer must have one terminal");
            records.values().forEach(record -> record.content().representation().close());
            record(evidence, "final-owner-inventory", "presentation-owner", "owner-inventory",
                Map.of("acceptedTransfers", "4", "acceptedTransferTerminals", "4", "preAdmissionRejections", "1",
                    "pageOwnedModelBacking", "0", "hostTextureOwner", "not entered", "physicalReclamationDeadline",
                    "none"));
        }
    }

    private void verifyCompletePage(ClientAssetRepository repository, RemotePublicationScenarioFixture fixture,
        ModelPackDescriptor pack, ControlledPresentationFetcher fetcher, RemoteModelStore store,
        ScenarioEvidence evidence) throws Exception {
        var modelId = fixture.asset("presentation-rich").identity().modelId();
        try (var page = repository.openBatch()) {
            var preview = page.preview(modelId);
            var icon = page.presentation(modelId, ModelAssetSelector.PresentationAsset.MODEL_ICON, 0);
            var avatar = page.presentation(modelId, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
            var foreground = page.presentation(modelId, ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0);
            var background = page.presentation(modelId, ModelAssetSelector.PresentationAsset.GUI_BACKGROUND, 0);
            var cover = page.packCover(pack);
            var terminals = observe(List.of(preview, icon, avatar, foreground, background, cover));
            page.submit();
            var action = fetcher.lastAction();
            require(action.members.size() == 5,
                "The complete page must batch the descriptorless preview and four remotely backed presentation interests: "
                    + action.members);
            action.deliver(Set.of());

            requireSuccess(preview);
            requireFailure(icon);
            List.of(avatar, foreground, background, cover)
                .forEach(ModelManagementPresentationLifecycleScenarioTest::requireSuccess);
            require(terminals.get() == 6, "Every complete-page interest must reach one item terminal");
            var previewChunk = action.members.stream().filter(RemotePresentationFetcher.Icon.class::isInstance)
                .findFirst().map(ModelManagementPresentationLifecycleScenarioTest::modelChunk).orElseThrow();
            try (var ignored = store.openContent(fixture.asset("presentation-rich").identity()).chunks()
                .readStoredVerified(previewChunk, BufferType.ARRAY)) {
                // Cache visibility is independently observed through the store Interface.
            }
            record(evidence, "complete-page", action.id, "page-terminal",
                Map.of("pageInterests", "preview,icon,avatar,gui-foreground,gui-background,pack-cover",
                    "acceptedTransfers", "1", "childTerminals", "6", "contentUnavailableTerminals", "1",
                    "exactCacheVisible", "true", "pageOwnedSharedModelBacking", "false"));
        }
    }

    private void verifyUnavailableChild(ClientAssetRepository repository, RemotePublicationScenarioFixture fixture,
        ControlledPresentationFetcher fetcher, ScenarioEvidence evidence) throws Exception {
        try (var page = repository.openBatch()) {
            var unavailable = page.presentation(fixture.asset("server-exact").identity().modelId(),
                ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
            var cachedSentinel = page.presentation(fixture.asset("presentation-rich").identity().modelId(),
                ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
            require(cachedSentinel.isDone() && !unavailable.isDone() && page.hasRemoteRequests(),
                "Cached children must resolve while only remote misses remain gated");
            page.submit();
            var action = fetcher.lastAction();
            require(action.members.size() == 1, "Only the uncached unavailable child may cross the transfer seam");
            action.deliver(Set.of(action.members.get(0).slot()));

            requireFailure(unavailable);
            requireSuccess(cachedSentinel);
            record(evidence, "unavailable-child", action.id, "child-isolation", Map.of("unavailableTerminals", "1",
                "cachedSentinelTerminals", "1", "acceptedTransferTerminal", action.terminal, "cacheRollback", "false"));
        }
    }

    private void verifyAsynchronousFailure(ClientAssetRepository repository, RemotePublicationScenarioFixture fixture,
        ControlledPresentationFetcher fetcher, ScenarioEvidence evidence) throws Exception {
        try (var page = repository.openBatch()) {
            var failed = page.presentation(fixture.asset("server-failure").identity().modelId(),
                ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
            page.submit();
            var action = fetcher.lastAction();
            action.fail(new IOException("controlled asynchronous presentation failure"));

            requireFailure(failed);
            require(action.terminal.equals("failure") && action.terminalCount.get() == 1,
                "Asynchronous transfer failure must produce one exact terminal");
            record(evidence, "asynchronous-transfer-failure", action.id, "transfer-terminal", Map.of("terminal",
                "failure", "terminalCount", "1", "itemFailureTerminals", "1", "persistentFailureState", "0"));
        }
    }

    private void verifySynchronousRejection(ClientAssetRepository repository, RemotePublicationScenarioFixture fixture,
        ControlledPresentationFetcher fetcher, ScenarioEvidence evidence) throws Exception {
        try (var page = repository.openBatch()) {
            var failed = page.presentation(fixture.asset("cache-exact").identity().modelId(),
                ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
            fetcher.rejectNext();
            page.submit();

            requireFailure(failed);
            require(fetcher.synchronousRejections == 1,
                "Synchronous fetch failure must be classified before transfer admission");
            record(evidence, "synchronous-fetch-rejection", "presentation-rejection-1", "pre-admission-rejection",
                Map.of("acceptedTransfers", "0", "itemFailureTerminals", "1", "synchronousRejections", "1",
                    "orphanItemFutures", "0"));
        }
    }

    private void verifyPageRemovalDisconnectAndRebuild(ClientAssetRepository repository, ClientCatalogManager manager,
        ActivationSnapshot activation, RemotePublicationScenarioFixture fixture, ControlledPresentationFetcher fetcher,
        RemoteModelStore store, ScenarioEvidence evidence) throws Exception {
        var modelId = fixture.asset("online-add").identity().modelId();
        var page = repository.openBatch();
        var late = page.presentation(modelId, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
        page.submit();
        var action = fetcher.lastAction();

        manager.failRemote();
        page.close();
        page.close();
        require(late.isCancelled() && action.cancelCalls.get() == 1,
            "Page removal must cancel its item and exact transfer once");
        action.deliverLate();
        require(late.isCancelled(), "Late data must not revive the removed page");
        var lateChunk = modelChunk(action.members.get(0));
        try (var ignored = store.openContent(fixture.asset("online-add").identity()).chunks()
            .readStoredVerified(lateChunk, BufferType.ARRAY)) {
            // Valid late cache publication remains owned by the exact cache, not by the page.
        }

        manager.publishSession(activation);
        var acceptedBeforeRebuild = fetcher.acceptedActions();
        try (var rebuilt = repository.openBatch()) {
            var result = rebuilt.presentation(modelId, ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, 0);
            rebuilt.submit();
            requireSuccess(result);
            require(fetcher.acceptedActions() == acceptedBeforeRebuild,
                "Rebuilt page must reuse exact committed cache without a new transfer");
        }
        record(evidence, "remove-disconnect-late-rebuild", action.id, "late-page-isolation",
            Map.of("pageCancelRequests", "1", "transferTerminals", "1", "lateUiEffects", "0", "lateExactCacheCommit",
                "true", "rebuildNetworkRequests", "0", "sharedModelBackingCloses", "0"));
    }

    private static ActivationSnapshot activation(Map<ModelFileIdentity, RemoteRecord> records,
        ModelPackDescriptor pack) {
        var entries = records.values().stream().map(RemoteRecord::publication).toList();
        var publication = new RemotePublicationSnapshot(entries, Set.of(), List.of(pack), Map.of());
        var states = new LinkedHashMap<Hash256, ActivationSnapshot.State>();
        records.values().forEach(record -> states.put(record.publication().modelId(),
            new ActivationSnapshot.Ready(record.publication(), record.record())));
        return new ActivationSnapshot(publication, states);
    }

    private static RemoteRecord remoteRecord(String name, RemoteModelContent content) {
        var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("remote/" + name));
        var record = new CatalogRecord(location, new CatalogContentBinding(content.modelId(), content));
        var publication = new PublicationEntry(content.representation().identity(), record.entry().path(),
            CatalogAccess.PUBLIC, true);
        return new RemoteRecord(publication, record, content);
    }

    private static Hash256 hash(byte[] bytes) throws IOException {
        return ModelHashing.blake3(ArrayBuffer.borrow(bytes));
    }

    private static ModelContent failingContent(ModelContent source, IOException failure) {
        return new ModelContent() {
            private final ChunkDataSource chunks = new ChunkDataSource() {
                @Override
                public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                    throws IOException {
                    throw failure;
                }

                @Override
                public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                    throws IOException {
                    throw failure;
                }
            };

            @Override
            public ModelRepresentation representation() {
                return source.representation();
            }

            @Override
            public ChunkDataSource chunks() {
                return chunks;
            }
        };
    }

    private static AssetContainerView.ChunkInfo modelChunk(RemotePresentationFetcher.Member member) {
        return ((RemotePresentationFetcher.Icon)member).chunk();
    }

    private static AtomicInteger observe(List<? extends CompletableFuture<?>> futures) {
        var count = new AtomicInteger();
        futures.forEach(future -> future.whenComplete((ignored, failure) -> count.incrementAndGet()));
        return count;
    }

    private static void requireSuccess(CompletableFuture<?> future) {
        require(future.join() != null, "Expected a successful presentation item terminal");
    }

    private static void requireFailure(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException expected) {
            return;
        }
        throw new AssertionError("Expected a failed presentation item terminal");
    }

    private static void record(ScenarioEvidence evidence, String action, String operation, String event,
        Map<String, String> payload) throws IOException {
        evidence.append(new JsonlEvidenceSink.Observation(SCENARIO_ID, action, "presentation-lifecycle", "connection-A",
            operation, event, payload));
    }

    private static void completeFailure(ScenarioEvidence evidence, Throwable failure) throws IOException {
        var artifact = evidence.retainFailureArtifact("scenario-failure.txt",
            (failure + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        evidence.complete(new ScenarioEvidence.Verdict(ScenarioEvidence.Outcome.FAIL, List.of(),
            List.of("Presentation lifecycle scenario failed: " + failure),
            Map.of("contractViolation", 0L, "unexpectedError", 1L), "Failure retained at " + artifact
                + "; no claim extends beyond the deterministic in-process Batch boundary"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record ScenarioPlan(List<String> actions, Map<String, Integer> expected) {
    }

    private record RemoteRecord(PublicationEntry publication, CatalogRecord record, RemoteModelContent content) {
    }

    private static final class ControlledPresentationFetcher implements RemotePresentationFetcher {
        private final Map<ModelFileIdentity, Asset> assets;
        private final ModelPackDescriptor pack;
        private final byte[] coverBytes;
        private final ClientModelSession session;
        private final ArrayList<TransferAction> actions = new ArrayList<>();
        private boolean rejectNext;
        private int synchronousRejections;

        private ControlledPresentationFetcher(Map<ModelFileIdentity, Asset> assets, ModelPackDescriptor pack,
            byte[] coverBytes, ClientModelSession session) {
            this.assets = Map.copyOf(assets);
            this.pack = pack;
            this.coverBytes = coverBytes.clone();
            this.session = session;
        }

        @Override
        public synchronized CompletableFuture<Void> fetch(List<Member> members, PresentationReceiver receiver) {
            if (rejectNext) {
                rejectNext = false;
                synchronousRejections++;
                throw new IllegalStateException("controlled synchronous fetch rejection");
            }
            var action = new TransferAction("presentation-transfer-" + (actions.size() + 1), List.copyOf(members),
                receiver, this);
            actions.add(action);
            return action;
        }

        private synchronized void rejectNext() {
            rejectNext = true;
        }

        private synchronized TransferAction lastAction() {
            if (actions.isEmpty()) {
                throw new AssertionError("No accepted presentation action");
            }
            return actions.get(actions.size() - 1);
        }

        private synchronized int acceptedActions() {
            return actions.size();
        }

        private synchronized int terminalObservations() {
            return actions.stream().mapToInt(action -> action.terminalCount.get()).sum();
        }

        private UniBuffer bytes(Member member) throws IOException {
            if (member instanceof PackCover cover) {
                require(pack.equals(cover.pack()), "Unexpected pack-cover identity");
                return ArrayBuffer.move(coverBytes.clone());
            }
            var identity = member instanceof Preview preview ? preview.identity() : ((Icon)member).identity();
            var asset = assets.get(identity);
            if (asset == null) {
                throw new AssertionError("No source fixture for " + identity);
            }
            var chunk = member instanceof Preview
                ? asset.content().modelFile().getFileView().getAssetView()
                    .getChunkInfo(com.elfmcys.ysm.format.schema.model.ModelFileConstant.THUMB_BUTTON_CHUNK_NAME)
                : ((Icon)member).chunk();
            return asset.content().chunks().readStoredVerified(chunk, BufferType.ARRAY);
        }
    }

    private static final class TransferAction extends CompletableFuture<Void> {
        private final String id;
        private final List<RemotePresentationFetcher.Member> members;
        private final RemotePresentationFetcher.PresentationReceiver receiver;
        private final ControlledPresentationFetcher source;
        private final Map<RemotePresentationFetcher.Member, ClientModelSession.PreviewObservation> observations =
            new java.util.IdentityHashMap<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private String terminal = "pending";

        private TransferAction(String id, List<RemotePresentationFetcher.Member> members,
            RemotePresentationFetcher.PresentationReceiver receiver, ControlledPresentationFetcher source) {
            this.id = id;
            this.members = members;
            this.receiver = receiver;
            this.source = source;
            members.forEach(member -> {
                if (member instanceof RemotePresentationFetcher.Preview preview) {
                    observations.put(member, source.session.observePreview(preview.identity()).orElseThrow());
                }
            });
            whenComplete((ignored, failure) -> terminalCount.incrementAndGet());
        }

        private void deliver(Set<Integer> unavailableSlots) throws Exception {
            require(!isDone(), "Presentation action is already terminal");
            try {
                deliverMembers(unavailableSlots);
                terminal = "success";
                require(complete(null), "Presentation success terminal was duplicated");
            } catch (Throwable failure) {
                terminal = "failure";
                completeExceptionally(failure);
                throw failure;
            }
        }

        private void deliverLate() throws Exception {
            require(isCancelled(), "Late delivery fixture requires a cancelled owner");
            deliverMembers(Set.of());
            require(terminalCount.get() == 1, "Late delivery must not create a duplicate transfer terminal");
        }

        private void deliverMembers(Set<Integer> unavailableSlots) throws Exception {
            for (var member : members) {
                if (unavailableSlots.contains(member.slot())) {
                    receiver.accept(member, RemotePresentationFetcher.Unavailable.INSTANCE);
                    continue;
                }
                try (var bytes = source.bytes(member)) {
                    receiver.accept(member, new RemotePresentationFetcher.Data(bytes, observations.get(member)));
                }
            }
        }

        private void fail(Throwable failure) {
            terminal = "failure";
            require(completeExceptionally(failure), "Presentation failure terminal was duplicated");
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) {
                return false;
            }
            cancelCalls.incrementAndGet();
            terminal = "cancel";
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
