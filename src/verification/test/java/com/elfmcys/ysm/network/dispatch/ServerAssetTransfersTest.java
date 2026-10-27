package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelPackScanner;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.ChunkMemberProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PackCoverMemberProto;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAssetTransfersTest {
    @TempDir
    Path root;
    private final List<ServerResourceRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void closeRuntimes() {
        runtimes.forEach(ServerResourceRuntime::close);
    }

    @Test
    void structuralRejectionDoesNotAdvanceTheSharedHighWater() {
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var owner = active(CatalogSnapshot.empty());
        try (var worker = new ResourceDispatchWorker(1, 2, 0);
            var transfers = transfers(owner, worker, new RecordingPort(), false, failures)) {
            assertFalse(transfers.accept(MetadataPrefixRequestProto.newBuilder().setDataTransferId(1).build()));

            assertTrue(transfers.accept(MetadataPrefixRequestProto.newBuilder().setDataTransferId(1)
                .addContainerIds(ProtoBytes.wrap(hash(1))).build()));
            assertFailure(failures, 1, notFound());

            assertFalse(transfers.accept(modelRequest(1, "chunk")));
            assertTrue(transfers.accept(modelRequest(2, "chunk")));
            assertFailure(failures, 2, notFound());

            var invalidPage = PresentationPageRequestProto.newBuilder().setDataTransferId(3)
                .addPackCovers(cover(1, "../escape/", hash(4), 1)).addPackCovers(cover(2, "pack/", hash(4), 1)).build();
            assertFalse(transfers.accept(invalidPage));
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(3)
                .addPackCovers(cover(1, "missing/", hash(4), 1)).build()));
            assertFailure(failures, 3, notFound());
        }
    }

    @Test
    void traversalIsRejectedBeforeCatalogRootResolution() {
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var rootCalls = new AtomicInteger();
        var owner = active(CatalogSnapshot.empty());
        try (var worker = new ResourceDispatchWorker(1, 2, 0); var transfers = new ServerAssetTransfers(owner, worker,
            new RecordingPort(), () -> false, failures::add, runtime(), ignored -> {
                rootCalls.incrementAndGet();
                return root;
            })) {
            assertFalse(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "../escape/", hash(4), 1)).build()));

            assertEquals(0, rootCalls.get());
            assertEquals(0, transfers.activeCount());
            assertTrue(failures.isEmpty());
        }
    }

    @Test
    void publishedPackMayReadLinkedGroupAndCoverOutsideItsCatalogRoot() throws Exception {
        var catalogRoot = root.resolve("catalog");
        var outsideRoot = root.resolve("outside");
        Files.createDirectories(catalogRoot);
        Files.createDirectories(outsideRoot);
        var image = Path.of(
            Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/misc/1_alex/textures/gsl.png")).toURI());
        var bytes = Files.readAllBytes(image);
        var outsideCover = root.resolve("physical-cover.data");
        Files.write(outsideCover, bytes);
        Files.createSymbolicLink(outsideRoot.resolve("ysm-pack.png"), outsideCover);
        Files.writeString(outsideRoot.resolve("ysm-pack.json"), "{\"name\":\"Linked pack\"}");
        Files.createSymbolicLink(catalogRoot.resolve("linked"), outsideRoot);

        var contentHash = ModelHashing.blake3(outsideCover);
        var descriptor = new ModelPackScanner()
            .scan(new ModelCatalogSource(CatalogRootKind.CUSTOM, catalogRoot, false), catalogRoot.resolve("linked"))
            .orElseThrow();
        assertEquals("local:custom/linked", descriptor.hierarchy());
        assertEquals(contentHash, descriptor.coverHash());
        var owner = active(new CatalogSnapshot(Map.of(), List.of(descriptor), ModelScanReport.empty()));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 2, 0); var transfers = new ServerAssetTransfers(owner, worker,
            port, () -> false, failures::add, runtime(), ignored -> catalogRoot)) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "linked/", contentHash, bytes.length)).build()));

            assertTrue(worker.dispatchOnce());
            transfers.tick();
            assertTrue(failures.isEmpty());
            try (var decoded =
                FrameCodec.decode(port.frames.get(0), id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
                var result = new byte[decoded.attachment().size()];
                decoded.attachment().nio().get(result);
                assertArrayEquals(bytes, result);
                assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.DATA,
                    ProtocolBuffer.parse(decoded.protobuf(), PackCoverFragmentProto::parseFrom).delivery());
            }
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
        }
    }

    @Test
    void sameRelativePackCoversUseTheirExactPublishedRootAndNeverFallBack() throws Exception {
        var global = Files.createDirectories(root.resolve("global/team"));
        var local = Files.createDirectories(root.resolve("local/team"));
        var globalBytes = new byte[] {1, 2, 3};
        var localBytes = new byte[] {4, 5, 6, 7};
        Files.write(global.resolve("ysm-pack.png"), globalBytes);
        Files.write(local.resolve("ysm-pack.png"), localBytes);
        var globalHash = ModelHashing.blake3(globalBytes);
        var localHash = ModelHashing.blake3(localBytes);
        var globalPack = new ModelPackDescriptor(CatalogRootKind.CUSTOM, "global:custom/team", "Global", "", Map.of(),
            globalHash, "png", 3);
        var localPack = new ModelPackDescriptor(CatalogRootKind.CUSTOM, "local:custom/team", "Local", "", Map.of(),
            localHash, "png", 4);
        var owner = active(new CatalogSnapshot(Map.of(), List.of(globalPack, localPack), ModelScanReport.empty()));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var roots = new ArrayList<com.elfmcys.ysm.model.catalog.source.CatalogRoot>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 2, 0); var transfers =
            new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime(), selected -> {
                roots.add(selected);
                return selected == com.elfmcys.ysm.model.catalog.source.CatalogRoot.GLOBAL_CUSTOM ? global.getParent()
                    : local.getParent();
            })) {
            var packs = List.of(globalPack, localPack);
            var expected = List.of(globalBytes, localBytes);
            for (int i = 0; i < packs.size(); i++) {
                var pack = packs.get(i);
                var member = cover(1, "team", pack.coverHash(), pack.coverSize()).toBuilder()
                    .setHierarchyPath(pack.hierarchy()).build();
                assertTrue(transfers.accept(
                    PresentationPageRequestProto.newBuilder().setDataTransferId(i + 1).addPackCovers(member).build()));
                assertTrue(worker.dispatchOnce());
                transfers.tick();
                try (var decoded =
                    FrameCodec.decode(port.frames.get(i), id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
                    var bytes = new byte[decoded.attachment().size()];
                    decoded.attachment().nio().get(bytes);
                    assertArrayEquals(expected.get(i), bytes);
                }
            }
            assertEquals(List.of(com.elfmcys.ysm.model.catalog.source.CatalogRoot.GLOBAL_CUSTOM,
                com.elfmcys.ysm.model.catalog.source.CatalogRoot.LOCAL_CUSTOM), roots);
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(3)
                .addPackCovers(cover(1, "team", globalHash, 3).toBuilder().setHierarchyPath("global:auth/team").build())
                .build()));
            assertFailure(failures, 3, notFound());
            assertEquals(2, roots.size());
            assertEquals(2, port.frames.size());
        }
    }

    @Test
    void unpublishedLinkedPackAndForgedDescriptorsDoNotResolveSources() throws IOException {
        var catalogRoot = Files.createDirectories(root.resolve("catalog"));
        var target = Files.createDirectories(root.resolve("outside"));
        var bytes = new byte[] {7, 8, 9};
        var file = Files.write(target.resolve("cover.bin"), bytes);
        var group = Files.createDirectories(catalogRoot.resolve("linked"));
        Files.createSymbolicLink(group.resolve("ysm-pack.png"), file);
        var expected = ModelHashing.blake3(bytes);
        var owner = active(catalog("published", expected, bytes.length));
        var calls = new AtomicInteger();
        var failures = new ArrayList<ResourceTransferFailureProto>();
        try (var worker = new ResourceDispatchWorker(1, 2, 0); var transfers = new ServerAssetTransfers(owner, worker,
            new RecordingPort(), () -> false, failures::add, runtime(), ignored -> {
                calls.incrementAndGet();
                return catalogRoot;
            })) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "linked/", expected, bytes.length)).build()));
            assertFailure(failures, 1, notFound());
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(2)
                .addPackCovers(cover(1, "published/", hash(9), bytes.length)).build()));
            assertFailure(failures, 2, ResourceFailureReason.INVALID_REQUEST);
            assertEquals(0, calls.get());
            assertEquals(0, worker.queued(owner));
        }
    }

    @Test
    void validatesWholePresentationBeforeResolvingAnySource() {
        var contentHash = ModelHashing.blake3(new byte[] {1, 2, 3});
        var owner = active(catalog("pack", contentHash, 3));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var rootCalls = new AtomicInteger();
        try (var worker = new ResourceDispatchWorker(1, 2, 0); var transfers = new ServerAssetTransfers(owner, worker,
            new RecordingPort(), () -> false, failures::add, runtime(), ignored -> {
                rootCalls.incrementAndGet();
                return root;
            })) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "pack/", contentHash, 3)).addPackCovers(cover(2, "missing/", contentHash, 3))
                .build()));

            assertFailure(failures, 1, notFound());
            assertEquals(0, rootCalls.get());
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
        }
    }

    @Test
    void activeTransferCapacityReturnsTypedBusyWithoutRetiringSession() {
        var contentHash = ModelHashing.blake3(new byte[] {4, 5, 6});
        var owner = active(catalog("missing", contentHash, 3));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        try (var worker = new ResourceDispatchWorker(255, 256, 0);
            var transfers = transfers(owner, worker, new RecordingPort(), false, failures)) {
            for (var transferId = 1; transferId <= 256; transferId++) {
                assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(transferId)
                    .addPackCovers(cover(1, "missing/", contentHash, 3)).build()));
            }
            assertEquals(256, transfers.activeCount());
            assertTrue(failures.isEmpty());

            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(257)
                .addPackCovers(cover(1, "missing/", contentHash, 3)).build()));
            assertFailure(failures, 257, busy());
            assertEquals(256, transfers.activeCount());
            assertFalse(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(257)
                .addPackCovers(cover(1, "missing/", contentHash, 3)).build()));
        }
    }

    @Test
    void independentlyAdmitsChildrenAndRollsBackOnlyTheRejectedChild() throws IOException {
        var bytes = new byte[] {1, 2, 3, 4};
        var contentHash = writeCover("pack", bytes);
        var owner = active(catalog("pack", contentHash, bytes.length));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 2, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            var first = PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "pack/", contentHash, bytes.length))
                .addPackCovers(cover(2, "pack/", contentHash, bytes.length)).build();
            assertTrue(transfers.accept(first));
            assertEquals(2, worker.queued(owner));
            assertEquals(1, transfers.activeCount());

            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(2)
                .addPackCovers(cover(3, "pack/", contentHash, bytes.length)).build()));
            assertFailure(failures, 2, busy());
            assertEquals(2, worker.queued(owner));
            assertEquals(1, transfers.activeCount());

            assertTrue(transfers.cancel(1));
            assertEquals(0, transfers.activeCount());
            assertTrue(worker.dispatchOnce());
            assertTrue(port.frames.isEmpty());
        }
    }

    @Test
    void enqueueExceptionRollsBackTheCallerOwnedChildAsBusy() throws IOException {
        var bytes = new byte[] {1, 2, 3, 4};
        var contentHash = writeCover("pack", bytes);
        var owner = active(catalog("pack", contentHash, bytes.length));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var settingsCalls = new AtomicInteger();
        try (var worker = new ResourceDispatchWorker(() -> {
            if (settingsCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("configuration refresh failed");
            }
            return new ResourceDispatchWorker.Settings(1, 2, 0);
        }); var transfers = transfers(owner, worker, new RecordingPort(), false, failures)) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "pack/", contentHash, bytes.length)).build()));

            assertFailure(failures, 1, busy());
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
        }
    }

    @Test
    void missingPresentationSourceProducesTypedUnavailableOutcome() throws IOException {
        var bytes = new byte[] {5, 6, 7};
        var contentHash = ModelHashing.blake3(bytes);
        var owner = active(catalog("missing", contentHash, bytes.length));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 2, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(4, "missing/", contentHash, bytes.length)).build()));

            assertTrue(worker.dispatchOnce());
            assertEquals(1, transfers.activeCount());
            transfers.tick();
            assertTrue(failures.isEmpty());
            assertEquals(0, transfers.activeCount());
            assertEquals(1, port.frames.size());
            try (var decoded =
                FrameCodec.decode(port.frames.get(0), id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
                assertEquals(ProtocolMessages.PACK_COVER_FRAGMENT_ID, decoded.messageId());
                assertEquals(0, decoded.attachment().size());
                var message = ProtocolBuffer.parse(decoded.protobuf(), PackCoverFragmentProto::parseFrom);
                assertEquals(1, message.dataTransferId());
                assertEquals(4, message.slot());
                assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.UNAVAILABLE, message.delivery());
            }
        }
    }

    @Test
    void sourceFailureAfterEnqueueTerminatesTheChildOnce() throws IOException {
        var bytes = new byte[] {9, 10, 11};
        var contentHash = writeCover("volatile", bytes);
        var owner = active(catalog("volatile", contentHash, bytes.length));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 2, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "volatile/", contentHash, bytes.length)).build()));
            Files.delete(root.resolve("volatile").resolve("ysm-pack.png"));

            assertTrue(worker.dispatchOnce());
            assertTrue(failures.isEmpty());
            assertEquals(1, transfers.activeCount());
            transfers.tick();
            assertFailure(failures, 1, unavailable());
            assertEquals(0, transfers.activeCount());
            assertTrue(port.frames.isEmpty());
            assertFalse(worker.dispatchOnce());
            assertEquals(1, failures.size());
        }
    }

    @Test
    void productionFailureStopsTheRemainingAcceptedPacketsBeforeOwnerTick() throws IOException {
        var firstBytes = new byte[] {1, 2, 3};
        var secondBytes = new byte[] {4, 5, 6};
        var firstHash = writeCover("volatile", firstBytes);
        var secondHash = writeCover("stable", secondBytes);
        var owner = active(new CatalogSnapshot(Map.of(),
            List.of(pack("volatile", firstHash, firstBytes.length), pack("stable", secondHash, secondBytes.length)),
            ModelScanReport.empty()));
        var port = new RecordingPort();
        var failures = new ArrayList<ResourceTransferFailureProto>();
        try (var worker = new ResourceDispatchWorker(1, 4, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "volatile/", firstHash, firstBytes.length))
                .addPackCovers(cover(2, "stable/", secondHash, secondBytes.length)).build()));
            Files.delete(root.resolve("volatile").resolve("ysm-pack.png"));

            assertTrue(worker.dispatchOnce());
            assertTrue(port.frames.isEmpty());
            assertTrue(worker.dispatchOnce());
            assertTrue(port.frames.isEmpty());
            assertEquals(1, transfers.activeCount());
            assertTrue(failures.isEmpty());

            transfers.tick();

            assertFailure(failures, 1, unavailable());
            assertEquals(0, transfers.activeCount());
            assertEquals(0, worker.queued(owner));
        }
    }

    private ServerAssetTransfers transfers(ServerModelSession owner, ResourceDispatchWorker worker, TransportPort port,
        boolean restricted, List<ResourceTransferFailureProto> failures) {
        return new ServerAssetTransfers(owner, worker, port, () -> restricted, failures::add, runtime(),
            ignored -> root);
    }

    @Test
    void transferOwnerExistsBeforeSynchronousSourceResolutionAndCancelPreventsEnqueue() throws Exception {
        var bytes = new byte[] {1, 2, 3, 4};
        var contentHash = writeCover("blocked", bytes);
        var owner = active(catalog("blocked", contentHash, bytes.length));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var worker = new ResourceDispatchWorker(1, 2, 0); var transfers = new ServerAssetTransfers(owner, worker,
            new RecordingPort(), () -> false, failures::add, runtime(), ignored -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("source resolution timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("source resolution interrupted", interrupted);
                }
                return root;
            })) {
            var accepted = executor.submit(() -> transfers.accept(PresentationPageRequestProto.newBuilder()
                .setDataTransferId(1).addPackCovers(cover(1, "blocked/", contentHash, bytes.length)).build()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, transfers.activeCount());
            assertTrue(transfers.cancel(1));
            release.countDown();

            assertTrue(accepted.get(5, TimeUnit.SECONDS));
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
            assertTrue(failures.isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void partialPacketConstructionClosesThePrefixAndNeverEnqueues() throws IOException {
        var firstBytes = new byte[] {1, 2, 3};
        var secondBytes = new byte[] {4, 5, 6};
        var firstHash = writeCover("first", firstBytes);
        var secondHash = writeCover("second", secondBytes);
        var firstPack = pack("first", firstHash, firstBytes.length);
        var secondPack = pack("second", secondHash, secondBytes.length);
        var owner = active(new CatalogSnapshot(Map.of(), List.of(firstPack, secondPack), ModelScanReport.empty()));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var resolutions = new AtomicInteger();
        try (var worker = new ResourceDispatchWorker(1, 4, 0); var transfers = new ServerAssetTransfers(owner, worker,
            new RecordingPort(), () -> false, failures::add, runtime(), ignored -> {
                if (resolutions.incrementAndGet() == 2) {
                    try {
                        Files.write(root.resolve("second").resolve("ysm-pack.png"), new byte[] {7, 8, 9});
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
                return root;
            })) {
            assertTrue(transfers.accept(PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPackCovers(cover(1, "first/", firstHash, firstBytes.length))
                .addPackCovers(cover(2, "second/", secondHash, secondBytes.length)).build()));

            assertFailure(failures, 1, unavailable());
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
        }
    }

    private ServerResourceRuntime runtime() {
        var runtime = new ServerResourceRuntime(new PreviewStore(root.resolve("preview-cache")), ignored -> {
        });
        runtimes.add(runtime);
        return runtime;
    }

    private Hash256 writeCover(String hierarchy, byte[] bytes) throws IOException {
        var directory = root.resolve(hierarchy);
        Files.createDirectories(directory);
        var path = directory.resolve("ysm-pack.png");
        Files.write(path, bytes);
        return ModelHashing.blake3(path);
    }

    private static ServerModelSession active(CatalogSnapshot catalog) {
        var owner = new ServerModelSession(() -> catalog, false);
        assertTrue(owner.activate());
        owner.commitCatalog(catalog);
        return owner;
    }

    private static CatalogSnapshot catalog(String hierarchy, Hash256 hash, int size) {
        return new CatalogSnapshot(Map.of(), List.of(pack(hierarchy, hash, size)), ModelScanReport.empty());
    }

    private static ModelPackDescriptor pack(String hierarchy, Hash256 hash, int size) {
        return new ModelPackDescriptor(CatalogRootKind.CUSTOM,
            "local:custom" + (hierarchy.isEmpty() ? ""
                : "/" + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy)),
            hierarchy, "", Map.of(), hash, "png", size);
    }

    private static ModelChunkRequestProto modelRequest(long id, String name) {
        return ModelChunkRequestProto.newBuilder().setDataTransferId(id).setModelId(ProtoBytes.wrap(hash(10)))
            .setContainerId(ProtoBytes.wrap(hash(11))).addChunks(ChunkMemberProto.newBuilder().setName(name)
                .setExpectedHash(ProtoBytes.wrap(hash(12))).setStoredSize(1).setDecodedSize(0).setEncoding("").build())
            .build();
    }

    private static PackCoverMemberProto cover(int slot, String hierarchy, Hash256 hash, int size) {
        return PackCoverMemberProto.newBuilder().setSlot(slot)
            .setHierarchyPath("local:custom" + (hierarchy.isEmpty() ? ""
                : "/" + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy)))
            .setExpectedHash(ProtoBytes.wrap(hash)).setStoredSize(size).setDecodedSize(size).setEncoding("png").build();
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[Hash256.SIZE - 1] = (byte)marker;
        return new Hash256(bytes);
    }

    private static void assertFailure(List<ResourceTransferFailureProto> failures, long id,
        ResourceFailureReason reason) {
        var failure = failures.get(failures.size() - 1);
        assertEquals(id, failure.dataTransferId());
        assertEquals(reason, failure.reason());
    }

    private static ResourceFailureReason notFound() {
        return ResourceFailureReason.NOT_FOUND;
    }

    private static ResourceFailureReason busy() {
        return ResourceFailureReason.BUSY;
    }

    private static ResourceFailureReason unavailable() {
        return ResourceFailureReason.UNAVAILABLE;
    }

    private static final class RecordingPort implements TransportPort {
        private final List<byte[]> frames = new ArrayList<>();

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
            frames.add(frame.bytes());
            return SendResult.SUCCESS;
        }
    }
}
