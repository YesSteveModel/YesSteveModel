package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.format.schema.model.RenderTargetChunkPlan;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.ProtoMessage;
import static org.junit.jupiter.api.Assertions.*;

class ServerPreviewRepairTest {
    @TempDir
    static Path fixtures;
    @TempDir
    Path temp;
    private static Path missingFile;
    private static Path authoredFile;
    private ManagedContainer content;
    private ServerResourceRuntime chunks;
    private ServerPreviewWork work;
    private PreviewStore store;
    private CatalogSnapshot catalog;
    private final List<Harness> owners = new ArrayList<>();

    @BeforeAll
    static void fixtures() throws Exception {
        var root = Path.of(Objects
            .requireNonNull(ServerPreviewRepairTest.class.getResource("/assets/ysm/builtin/default/ysm.json")).toURI())
            .getParent();
        try (var vfs = new Directory(root)) {
            missingFile = ModelParser.parseBuiltinDefault(vfs, Files.createDirectories(fixtures.resolve("missing")));
        }
        var configuration =
            Files.readString(root.resolve("ysm.json")).replace("\"width_scale\": 0.7", "\"width_scale\": 0.6");
        try (var vfs = new Directory(root);
            var changed = NativeBuffer.allocate(configuration.getBytes(StandardCharsets.UTF_8).length)) {
            changed.nio().put(configuration.getBytes(StandardCharsets.UTF_8));
            var overlay = new VirtualFileSystem() {
                @Override
                public String[] listFiles(String path) {
                    return vfs.listFiles(path);
                }

                @Override
                public String[] listDirectories(String path) {
                    return vfs.listDirectories(path);
                }

                @Override
                public boolean hasFile(String name) {
                    return vfs.hasFile(name);
                }

                @Override
                public NativeBuffer getFile(String name) {
                    return name.equals("ysm.json") ? changed.borrow() : vfs.getFile(name);
                }
            };
            var variant =
                ModelParser.parseBuiltinDefault(overlay, Files.createDirectories(fixtures.resolve("variant")));
            var model = ManagedContainer.openDirect(variant, location("authored"));
            try (var preview = TestPreviews.blank()) {
                authoredFile = ModelExporter.export(model, preview, fixtures.resolve("authored.mxc"), null);
            } finally {
                model.representation().close();
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        content = ManagedContainer.openDirect(Files.copy(missingFile, temp.resolve("model.mxc")), location("missing"));
        catalog = catalog(record(content));
        store = new PreviewStore(temp.resolve("cache"), id -> chunks.invalidatePreview(id));
        chunks = new ServerResourceRuntime(store, ignored -> {
        });
        work = new ServerPreviewWork();
    }

    @AfterEach
    void close() {
        owners.forEach(Harness::close);
        work.close();
        chunks.close();
        content.representation().close();
    }

    @Test
    void cancelledQueryKeepsItsQuotaUntilItsCompletedOwnerFactIsDisposed() throws Exception {
        var owner = harness(catalog);
        assertTrue(owner.transfers.accept(MissingPreviewQueryProto.newBuilder().setDataTransferId(1).build()));
        var workerPassedQuery = new CountDownLatch(1);
        try (var barrier = work.acquire(false, 0)) {
            assertNotNull(barrier);
            barrier.execute(workerPassedQuery::countDown);
            assertTrue(workerPassedQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
            owner.transfers.cancel(1);
            assertEquals(2, work.activeRequests());
            var other = harness(catalog);
            other.transfers.accept(MissingPreviewQueryProto.newBuilder().setDataTransferId(1).build());
            assertEquals(ResourceFailureReason.BUSY, other.failures.get(0).reason());
            owner.transfers.tick();
        }
        await(() -> work.activeRequests() == 0);
        assertTrue(owner.port.parts.isEmpty());
    }

    @Test
    void independentUploadPersistsAndOrdinaryRestartReadPreservesSourceIdentity() throws Exception {
        var owner = harness(catalog);
        var before = Files.readAllBytes(temp.resolve("model.mxc"));
        var identity = content.representation().identity();
        try (var preview = TestPreviews.blank()) {
            admit(owner, 1, preview);
            fragment(owner, 1, preview.bytes(), 0, true);
            await(() -> owner.results.stream().anyMatch(message -> message instanceof PreviewRepairResultProto));
            assertEquals(PreviewRepairStatus.COMMITTED, result(owner).status());
            assertArrayEquals(preview.bytes(),
                new PreviewStore(temp.resolve("cache")).load(identity.containerId()).orElseThrow().bytes());
            store = new PreviewStore(temp.resolve("cache"));
            var reopened = harness(catalog);
            reopened.authorized.set(false);
            reopened.transfers
                .accept(com.elfmcys.ysm.proto.network.PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                    .addPreviews(com.elfmcys.ysm.proto.network.PreviewMemberProto.newBuilder().setSlot(0)
                        .setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
                        .setContainerId(ByteBuffer.wrap(identity.containerId().bytes())).build())
                    .build());
            await(() -> !reopened.port.previewBytes.isEmpty());
            assertArrayEquals(preview.bytes(), reopened.port.previewBytes.get(0));
            assertTrue(reopened.failures.isEmpty());
            assertEquals(List.of(identity), owner.notifications);
            assertArrayEquals(before, Files.readAllBytes(temp.resolve("model.mxc")));
            assertEquals(identity, owner.session.findModel(identity.modelId()).orElseThrow().binding().content()
                .representation().identity());
            fragment(owner, 1, preview.bytes(), 0, true);
            assertEquals(1, owner.notifications.size());
            assertEquals(0, owner.transfers.activeCount());
            await(() -> work.activeRequests() == 0);
            assertEquals(0, work.retainedEncodedBytes());
            assertTrue(owner.transfers.accept(upload(2, preview.bytes().length)));
            await(() -> owner.results.stream().filter(PreviewRepairResultProto.class::isInstance).count() == 2);
            assertEquals(PreviewRepairStatus.ALREADY_AVAILABLE, result(owner).status());
        }
    }

    @Test
    void notificationFailurePreservesCommittedReplacementAndInvalidation() throws Exception {
        var owner = harness(catalog);
        var identity = content.representation().identity();
        var previousBytes = new byte[] {1, 2, 3};
        var root = Files.createDirectories(temp.resolve("cache/previews"));
        Files.write(root.resolve(identity.containerId() + ".image"), previousBytes);
        try (var previous = acquirePreview(); var preview = TestPreviews.blank()) {
            owner.afterCommitNotification = () -> {
                throw new IllegalStateException("Injected notification failure");
            };
            admit(owner, 1, preview);
            fragment(owner, 1, preview.bytes(), 0, true);
            await(() -> owner.results.stream().anyMatch(PreviewRepairResultProto.class::isInstance));

            assertEquals(PreviewRepairStatus.COMMITTED, result(owner).status());
            assertEquals(List.of(identity), owner.notifications);
            assertTrue(owner.failures.isEmpty());
            try (var refreshed = acquirePreview(); var stored = store.probe(identity.containerId()).orElseThrow()) {
                assertArrayEquals(preview.bytes(), copy(refreshed));
                assertArrayEquals(preview.bytes(), stored.bytes());
            }
            assertArrayEquals(previousBytes, copy(previous));
            await(() -> work.activeRequests() == 0);
            assertEquals(0, owner.transfers.activeCount());
        }
    }

    @Test
    void disconnectAfterCommitPreservesReplacementAndInvalidation() throws Exception {
        var owner = harness(catalog);
        var identity = content.representation().identity();
        var previousBytes = new byte[] {4, 5, 6};
        var root = Files.createDirectories(temp.resolve("cache/previews"));
        Files.write(root.resolve(identity.containerId() + ".image"), previousBytes);
        try (var previous = acquirePreview(); var preview = TestPreviews.blank()) {
            owner.afterCommitNotification = () -> {
                owner.transfers.close();
                owner.session.close();
            };
            admit(owner, 1, preview);
            fragment(owner, 1, preview.bytes(), 0, true);
            await(() -> !owner.notifications.isEmpty() && work.activeRequests() == 0);

            assertEquals(List.of(identity), owner.notifications);
            assertTrue(owner.results.stream().noneMatch(PreviewRepairResultProto.class::isInstance));
            assertTrue(owner.failures.isEmpty());
            try (var refreshed = acquirePreview(); var stored = store.probe(identity.containerId()).orElseThrow()) {
                assertArrayEquals(preview.bytes(), copy(refreshed));
                assertArrayEquals(preview.bytes(), stored.bytes());
            }
            assertArrayEquals(previousBytes, copy(previous));
            assertEquals(0, owner.transfers.activeCount());
        }
    }

    @Test
    void authorizationIsIndependentAndAcceptedUploadSurvivesRevocation() throws Exception {
        var owner = harness(catalog);
        try (var preview = TestPreviews.blank()) {
            owner.authorized.set(false);
            assertTrue(owner.transfers.accept(MissingPreviewQueryProto.newBuilder().setDataTransferId(1).build()));
            assertEquals(ResourceFailureReason.UNAUTHORIZED, owner.failures.get(0).reason());
            owner.authorized.set(true);
            admit(owner, 2, preview);
            owner.authorized.set(false);
            fragment(owner, 2, preview.bytes(), 0, true);
            await(() -> !owner.notifications.isEmpty());
            await(() -> owner.transfers.activeCount() == 0);
            assertTrue(owner.transfers.accept(upload(3, preview.bytes().length)));
            assertEquals(ResourceFailureReason.UNAUTHORIZED, owner.failures.get(owner.failures.size() - 1).reason());
            assertEquals(ServerModelSession.SelectionResult.UNAUTHORIZED,
                owner.session.select(new Selection.Model(content.modelId(), "")));
        }
    }

    @Test
    void twoAlreadyAdmittedRequestsMayCommitButPhysicalDuplicatesDoNot() throws Exception {
        var first = harness(catalog);
        var second = harness(catalog);
        try (var preview = TestPreviews.blank()) {
            admit(first, 1, preview);
            admit(second, 1, preview);
            assertEquals(2, work.activeRequests());
            assertTrue(first.transfers.accept(upload(1, preview.bytes().length)));
            assertEquals(1, first.results.stream().filter(PreviewUploadAdmissionProto.class::isInstance).count());
            fragment(first, 1, preview.bytes(), 0, true);
            fragment(first, 1, preview.bytes(), 0, true);
            await(() -> !first.notifications.isEmpty());
            fragment(second, 1, preview.bytes(), 0, true);
            await(() -> !second.notifications.isEmpty());
            await(() -> work.activeRequests() == 0);
            assertEquals(1, first.notifications.size());
            assertEquals(1, second.notifications.size());
        }
    }

    @Test
    void completedUploadRetainsBytesForDuplicateChecksWhileCommitIsQueued() throws Exception {
        var owner = harness(catalog);
        try (var preview = TestPreviews.blank()) {
            admit(owner, 1, preview);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var blocker = work.acquire(false, 0)) {
                blocker.execute(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                fragment(owner, 1, preview.bytes(), 0, true);
                fragment(owner, 1, preview.bytes(), 0, true);
                assertTrue(owner.failures.isEmpty());
                release.countDown();
                await(() -> !owner.notifications.isEmpty());
                assertEquals(1, owner.notifications.size());
            } finally {
                release.countDown();
            }
            await(() -> work.activeRequests() == 0);
        }
    }

    @Test
    void removeAndReaddSameIdentityBeforeAtomicGateMakesOldUploadStale() throws Exception {
        var owner = harness(catalog);
        try (var preview = TestPreviews.blank()) {
            admit(owner, 1, preview);
            var release = new CountDownLatch(1);
            try (var blocker = work.acquire(false, 0)) {
                blocker.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                });
                fragment(owner, 1, preview.bytes(), 0, true);
                owner.session.commitCatalog(CatalogSnapshot.empty());
                owner.session.commitCatalog(catalog);
                release.countDown();
            } finally {
                release.countDown();
            }
            await(() -> owner.results.stream().anyMatch(PreviewRepairResultProto.class::isInstance));
            assertEquals(PreviewRepairStatus.STALE, result(owner).status());
            assertTrue(owner.notifications.isEmpty());
            assertTrue(store.load(content.representation().containerId()).isEmpty());
        }
    }

    @Test
    void requestCancellationBeforeWorkerCommitReleasesAssemblyAndSealsEffect() throws Exception {
        var owner = harness(catalog);
        try (var preview = TestPreviews.blank()) {
            admit(owner, 1, preview);
            var release = new CountDownLatch(1);
            try (var blocker = work.acquire(false, 0)) {
                blocker.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                });
                fragment(owner, 1, preview.bytes(), 0, true);
                assertTrue(owner.transfers.cancel(1));
                release.countDown();
            } finally {
                release.countDown();
            }
            await(() -> work.activeRequests() == 0);
            assertTrue(owner.notifications.isEmpty());
            assertTrue(store.load(content.representation().containerId()).isEmpty());
        }
    }

    @Test
    void decodingAndFilesystemFailuresNeverCommitOrNotify() throws Exception {
        var owner = harness(catalog);
        assertTrue(owner.transfers.accept(upload(1, 3)));
        await(() -> owner.results.stream().anyMatch(PreviewUploadAdmissionProto.class::isInstance));
        fragment(owner, 1, new byte[] {1, 2, 3}, 0, true);
        await(() -> !owner.failures.isEmpty());
        assertTrue(owner.notifications.isEmpty());
        try (var preview = TestPreviews.blank()) {
            admit(owner, 2, preview);
            Files.createDirectories(temp.resolve("cache"));
            Files.write(temp.resolve("cache/previews"), new byte[] {7});
            fragment(owner, 2, preview.bytes(), 0, true);
            await(() -> owner.failures.size() == 2);
            assertTrue(owner.notifications.isEmpty());
            assertEquals(0, owner.results.stream().filter(PreviewRepairResultProto.class::isInstance).count());
            assertArrayEquals(new byte[] {7}, Files.readAllBytes(temp.resolve("cache/previews")));
        }
    }

    @Test
    void gapConflictAndWrongFinalRetireOnlyTheUploadedRequest() throws Exception {
        for (var mode = 0; mode < 3; mode++) {
            var owner = harness(catalog);
            assertTrue(owner.transfers.accept(upload(1, 4)));
            await(() -> owner.results.stream().anyMatch(PreviewUploadAdmissionProto.class::isInstance));
            if (mode == 0)
                fragment(owner, 1, new byte[] {1}, 1, false);
            if (mode == 1) {
                fragment(owner, 1, new byte[] {1}, 0, false);
                fragment(owner, 1, new byte[] {2}, 0, false);
            }
            if (mode == 2)
                fragment(owner, 1, new byte[] {1}, 0, true);
            assertEquals(ResourceFailureReason.INVALID_REQUEST, owner.failures.get(0).reason());
            assertTrue(owner.session.active());
            assertEquals(0, owner.transfers.activeCount());
        }
    }

    @Test
    void uploadRejectsTheFirstFragmentBeyondTheContractRecordLimit() throws Exception {
        var owner = harness(catalog);
        var recordLimit = 16_384;
        assertTrue(owner.transfers.accept(upload(1, recordLimit + 2)));
        await(() -> owner.results.stream().anyMatch(PreviewUploadAdmissionProto.class::isInstance));
        for (var offset = 0; offset < recordLimit; offset++) {
            fragment(owner, 1, new byte[] {1}, offset, false);
        }
        assertTrue(owner.failures.isEmpty());
        assertEquals(1, owner.transfers.activeCount());

        fragment(owner, 1, new byte[] {1}, recordLimit, false);

        assertEquals(1, owner.failures.size(), "W02 limits one upload to 16,384 fragment records");
        assertEquals(0, owner.transfers.activeCount());
        assertEquals(0, work.activeRequests());
        assertEquals(0, work.retainedEncodedBytes());
        assertTrue(owner.session.active());
        assertTrue(owner.notifications.isEmpty());
        assertTrue(store.load(content.representation().containerId()).isEmpty());
    }

    @Test
    void queryReturnsCompleteMissingAndFailedSetWithoutLeavingReceivingState() throws Exception {
        var authored = ManagedContainer.openDirect(authoredFile, location("failed"));
        try {
            assertNotEquals(content.modelId(), authored.modelId());
            ModelContent inaccessible = new ModelContent() {
                @Override
                public com.elfmcys.ysm.model.domain.ModelRepresentation representation() {
                    return authored.representation();
                }

                @Override
                public com.elfmcys.ysm.format.schema.file.ChunkDataSource chunks() {
                    return new com.elfmcys.ysm.format.schema.file.ChunkDataSource() {
                        @Override
                        public com.elfmcys.ysm.buffer.SharedBuffer readPayload(
                            com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk,
                            com.elfmcys.ysm.buffer.BufferType type) throws IOException {
                            throw new IOException("Injected source access failure");
                        }

                        @Override
                        public com.elfmcys.ysm.buffer.SharedBuffer readStoredVerified(
                            com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk,
                            com.elfmcys.ysm.buffer.BufferType type) throws IOException {
                            throw new IOException("Injected source access failure");
                        }
                    };
                }
            };
            var owner = harness(catalog(record(content), new CatalogRecord(location("failed"),
                new CatalogContentBinding(inaccessible.modelId(), inaccessible))));
            assertTrue(owner.transfers.accept(MissingPreviewQueryProto.newBuilder().setDataTransferId(1).build()));
            await(
                () -> !owner.port.parts.isEmpty() && owner.port.parts.get(owner.port.parts.size() - 1).finalFragment());
            var entries = owner.port.parts.stream().flatMap(part -> part.entries().stream()).toList();
            assertEquals(2, entries.size());
            assertEquals(1,
                entries.stream().filter(entry -> entry.probeFailure() == ResourceFailureReason.UNAVAILABLE).count());
            assertEquals(1,
                entries.stream().filter(entry -> entry.probeFailure() == ResourceFailureReason.UNSPECIFIED).count());
            await(() -> work.activeRequests() == 0);
            assertEquals(0, owner.transfers.activeCount());
        } finally {
            authored.representation().close();
        }
    }

    @Test
    void restrictedManagementOnlyAllowsCurrentPrimaryClosure() throws Exception {
        var owner = harness(catalog);
        var closure = RenderTargetChunkPlan.chunks(content.modelFile(), RenderTargetIds.PLAYER, "");
        var chunk = closure.get(0);
        var request = ModelChunkRequestProto.newBuilder().setDataTransferId(1)
            .setModelId(ByteBuffer.wrap(content.modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(content.representation().containerId().bytes())).addChunks(member(chunk))
            .build();
        assertTrue(owner.transfers.accept(request));
        assertEquals(ResourceFailureReason.UNAUTHORIZED, owner.failures.get(0).reason());
        assertTrue(owner.transfers
            .accept(request.toBuilder().setDataTransferId(2).setPurpose(ModelChunkPurpose.GEN_PREVIEW_MAIN).build()));
        await(() -> owner.transfers.activeCount() == 0);
        assertTrue(owner.port.chunks >= 1);
        assertEquals(ServerModelSession.SelectionResult.UNAUTHORIZED,
            owner.session.select(new Selection.Model(content.modelId(), "")));
        var unrelated = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(candidate -> closure.stream().noneMatch(allowed -> allowed.type().equals(candidate.type())))
            .filter(candidate -> candidate.size() > 0).findFirst().orElseThrow();
        assertTrue(owner.transfers.accept(request.toBuilder().setDataTransferId(3).clearChunks()
            .addChunks(member(unrelated)).setPurpose(ModelChunkPurpose.GEN_PREVIEW_MAIN).build()));
        await(() -> owner.failures.size() == 2);
        assertEquals(ResourceFailureReason.INVALID_REQUEST, owner.failures.get(1).reason());
    }

    @Test
    void restrictedPresentationServesDeclaredImagesWithoutSelectionOrRuntimeAccess() throws Exception {
        var owner = harness(catalog);
        var view = content.modelFile();
        var info = view.getManifest().info();
        var images = new ArrayList<com.elfmcys.ysm.proto.mixel.common.ImageProto>();
        images.add(info.settings().guiBackgroundUnsafe());
        images.add(info.settings().guiForegroundUnsafe());
        for (var author : info.metadataUnsafe().authors())
            if (author.hasAvatar())
                images.add(author.avatarUnsafe());
        assertEquals(8, images.size());
        var identity = content.representation().identity();
        var request = PresentationPageRequestProto.newBuilder().setDataTransferId(1).addPreviews(
            PreviewMemberProto.newBuilder().setSlot(0).setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
                .setContainerId(ByteBuffer.wrap(identity.containerId().bytes())).build());
        var expected = new ArrayList<byte[]>();
        for (var image : images) {
            var chunk = view.getFileView().getAssetView()
                .getChunkInfo(com.elfmcys.ysm.format.schema.file.AssetFileConstant.BLOB_CHUNK_PREFIX + image.blobId());
            request.addIcons(imageMember(expected.size(), chunk));
            {
                var bytes = content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                var stored = new byte[bytes.size()];
                bytes.nio().get(stored);
                expected.add(stored);
            }
        }
        assertTrue(owner.transfers.accept(request.build()));
        assertTrue(owner.failures.isEmpty(), "Declared presentation images must not reject their mixed preview batch");
        await(() -> owner.transfers.activeCount() == 0);
        assertTrue(owner.failures.isEmpty());
        assertEquals(1, owner.port.previewBytes.size());
        assertEquals(images.size(), owner.port.icons.size());
        assertEquals(images.size(), owner.port.completedIcons.size());
        for (var slot = 0; slot < images.size(); slot++) {
            assertArrayEquals(expected.get(slot), owner.port.icons.get(slot).toByteArray());
        }
        assertEquals(ServerModelSession.SelectionResult.UNAUTHORIZED,
            owner.session.select(new Selection.Model(content.modelId(), "")));
        var runtimeChunk = RenderTargetChunkPlan.chunks(view, RenderTargetIds.PLAYER, "").get(0);
        assertTrue(owner.transfers.accept(ModelChunkRequestProto.newBuilder().setDataTransferId(2)
            .setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(identity.containerId().bytes())).addChunks(member(runtimeChunk)).build()));
        assertEquals(ResourceFailureReason.UNAUTHORIZED, owner.failures.get(0).reason());
    }

    @Test
    void presentationRejectsRuntimeTextureEvenWithItsExactDescriptor() throws Exception {
        var owner = harness(catalog);
        var view = content.modelFile();
        var texture = view.requireRenderTarget(RenderTargetIds.PLAYER).textureDescriptor("default").uv();
        var chunk = view.getFileView().getAssetView()
            .getChunkInfo(com.elfmcys.ysm.format.schema.file.AssetFileConstant.BLOB_CHUNK_PREFIX + texture.blobId());
        assertTrue(owner.transfers.accept(
            PresentationPageRequestProto.newBuilder().setDataTransferId(1).addIcons(imageMember(0, chunk)).build()));
        assertEquals(ResourceFailureReason.INVALID_REQUEST, owner.failures.get(0).reason());
        assertEquals(0, owner.transfers.activeCount());
        assertEquals(0, owner.dispatch.queued(owner.session));
        assertTrue(owner.port.icons.isEmpty());
    }

    @Test
    void presentationRejectsChangedDescriptorBeforeServingAnyMixedMember() throws Exception {
        var owner = harness(catalog);
        var view = content.modelFile();
        var image = view.getManifest().info().settings().guiBackgroundUnsafe();
        var chunk = view.getFileView().getAssetView()
            .getChunkInfo(com.elfmcys.ysm.format.schema.file.AssetFileConstant.BLOB_CHUNK_PREFIX + image.blobId());
        var valid = imageMember(0, chunk);
        assertTrue(owner.transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1).addIcons(valid)
            .addIcons(valid.toBuilder().setSlot(1).setStoredSize(chunk.size() + 1).build()).build()));
        assertEquals(ResourceFailureReason.INVALID_REQUEST, owner.failures.get(0).reason());
        assertEquals(0, owner.transfers.activeCount());
        assertEquals(0, owner.dispatch.queued(owner.session));
        assertTrue(owner.port.icons.isEmpty());
    }

    private IconMemberProto imageMember(int slot, com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk) {
        return IconMemberProto.newBuilder().setSlot(slot).setModelId(ByteBuffer.wrap(content.modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(content.representation().containerId().bytes())).setName(chunk.type())
            .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
            .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build();
    }

    private Harness harness(CatalogSnapshot snapshot) {
        var value = new Harness(snapshot);
        owners.add(value);
        return value;
    }

    private ServerResourceRuntime.BytesLease acquirePreview() throws Exception {
        var acquisition = chunks.acquirePreview(content.representation().containerId());
        await(acquisition::isDone);
        return acquisition.join().orElseThrow();
    }

    private static byte[] copy(ServerResourceRuntime.BytesLease lease) {
        var bytes = ByteBuffer.allocate(lease.size());
        lease.copyTo(0, bytes);
        return bytes.array();
    }

    private void admit(Harness owner, long id, PreviewStore.EncodedPreview preview) throws Exception {
        assertTrue(owner.transfers.accept(upload(id, preview.bytes().length)));
        await(() -> owner.results.stream().anyMatch(
            message -> message instanceof PreviewUploadAdmissionProto admission && admission.dataTransferId() == id));
    }

    private PreviewUploadRequestProto upload(long id, int size) {
        var identity = content.representation().identity();
        return PreviewUploadRequestProto.newBuilder().setDataTransferId(id)
            .setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(identity.containerId().bytes())).setEncodedSize(size).build();
    }

    private static void fragment(Harness owner, long id, byte[] bytes, long offset, boolean last) {
        try (var data = ArrayBuffer.move(bytes.clone())) {
            assertTrue(owner.transfers.accept(PreviewUploadFragmentProto.newBuilder().setDataTransferId(id)
                .setOffset(offset).setFinalFragment(last).build(), data));
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            chunks.tick();
            for (var owner : owners) {
                owner.transfers.tick();
                owner.dispatch.dispatchOnce();
                owner.transfers.tick();
            }
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "Request did not reach its expected terminal");
    }

    private static PreviewRepairResultProto result(Harness owner) {
        return owner.results.stream().filter(PreviewRepairResultProto.class::isInstance)
            .map(PreviewRepairResultProto.class::cast).reduce((first, last) -> last).orElseThrow();
    }

    private static ChunkMemberProto member(com.elfmcys.ysm.format.container.AssetContainerView.ChunkInfo chunk) {
        return ChunkMemberProto.newBuilder().setName(chunk.type()).setExpectedHash(ByteBuffer.wrap(chunk.hash()))
            .setStoredSize(chunk.size()).setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build();
    }

    private static CatalogLocation location(String path) {
        return new CatalogLocation(CatalogRootKind.AUTH, new ModelPath(path));
    }

    private static CatalogRecord record(ManagedContainer content) {
        return new CatalogRecord(content.location(), new CatalogContentBinding(content.modelId(), content));
    }

    private static CatalogSnapshot catalog(CatalogRecord... records) {
        var map = new java.util.LinkedHashMap<com.elfmcys.ysm.model.domain.Hash256, CatalogRecord>();
        for (var record : records)
            map.put(record.entry().modelId(), record);
        return new CatalogSnapshot(map, List.of(), ModelScanReport.empty());
    }

    private final class Harness implements AutoCloseable {
        final ServerModelSession session;
        final ResourceDispatchWorker dispatch = new ResourceDispatchWorker(1, 48, 0);
        final Port port = new Port();
        final AtomicBoolean authorized = new AtomicBoolean(true);
        final List<ResourceTransferFailureProto> failures = new ArrayList<>();
        final List<ProtoMessage<?>> results = new ArrayList<>();
        final List<ModelFileIdentity> notifications = new CopyOnWriteArrayList<>();
        volatile Runnable afterCommitNotification = () -> {
        };
        final ServerAssetTransfers transfers;

        Harness(CatalogSnapshot snapshot) {
            session = new ServerModelSession(() -> snapshot, true);
            session.activate();
            session.commitCatalog(snapshot);
            transfers = new ServerAssetTransfers(session, dispatch, port, () -> true, failures::add, chunks, store,
                new ServerAssetTransfers.Management(work, authorized::get, results::add, identity -> {
                    notifications.add(identity);
                    afterCommitNotification.run();
                }));
        }

        @Override
        public void close() {
            transfers.close();
            dispatch.close();
            session.close();
        }
    }

    private static final class Port implements TransportPort {
        final List<MissingPreviewFragmentProto> parts = new ArrayList<>();
        int chunks;
        final List<byte[]> previewBytes = new ArrayList<>();
        final Map<Integer, java.io.ByteArrayOutputStream> icons = new java.util.LinkedHashMap<>();
        final java.util.Set<Integer> completedIcons = new java.util.HashSet<>();

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public long highWatermarkBytes() {
            return 64 * 1024;
        }

        @Override
        public long pendingBytes() {
            return 0;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            try (var decoded = FrameCodec.decode(frame.bytes(), ignored -> true)) {
                if (decoded.messageId() == ProtocolMessages.MISSING_PREVIEW_FRAGMENT_ID)
                    parts.add(ProtocolBuffer.parse(decoded.protobuf(), MissingPreviewFragmentProto::parseFrom));
                if (decoded.messageId() == ProtocolMessages.CHUNK_FRAGMENT_ID)
                    chunks++;
                if (decoded.messageId() == ProtocolMessages.PREVIEW_FRAGMENT_ID) {
                    var bytes = new byte[decoded.attachment().size()];
                    decoded.attachment().nio().get(bytes);
                    previewBytes.add(bytes);
                }
                if (decoded.messageId() == ProtocolMessages.ICON_FRAGMENT_ID) {
                    var part = ProtocolBuffer.parse(decoded.protobuf(), IconFragmentProto::parseFrom);
                    assertEquals(PresentationDelivery.DATA, part.delivery());
                    assertFalse(completedIcons.contains(part.slot()));
                    var stored = icons.computeIfAbsent(part.slot(), ignored -> new java.io.ByteArrayOutputStream());
                    assertEquals(stored.size(), part.offset());
                    var bytes = new byte[decoded.attachment().size()];
                    decoded.attachment().nio().get(bytes);
                    stored.writeBytes(bytes);
                    if (part.finalFragment())
                        completedIcons.add(part.slot());
                }
            }
            return SendResult.SUCCESS;
        }

    }
}
