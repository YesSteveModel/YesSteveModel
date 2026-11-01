package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogPresentation;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.ChunkMemberProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewMemberProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTypedResourceFlowTest {
    @TempDir
    static Path fixtureRoot;

    @TempDir
    Path testRoot;

    private static Path fixture;
    private static Path fixtureWithoutPreview;
    private ManagedContainer content;
    private CatalogSnapshot catalog;
    private ServerResourceRuntime runtime;
    private PreviewStore previews;

    @BeforeAll
    static void createFixture() throws Exception {
        var manifest = ServerTypedResourceFlowTest.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            var parsed = ModelParser.parseBuiltinDefault(vfs, Files.createDirectories(fixtureRoot.resolve("model")));
            fixtureWithoutPreview = parsed;
            var location = new CatalogLocation(CatalogRootKind.AUTH, new ModelPath("fixture"));
            var content = ManagedContainer.openDirect(parsed, location);
            try {
                fixture = ModelExporter.export(content, TestPreviews.blank(),
                    fixtureRoot.resolve("model-with-preview.mxc"), null);
            } finally {
                content.representation().close();
            }
        }
    }

    @BeforeEach
    void openContent() throws Exception {
        previews = new PreviewStore(testRoot.resolve("preview-cache"), id -> runtime.invalidatePreview(id));
        runtime = new ServerResourceRuntime(previews, ignored -> {
        });
        var file = Files.copy(fixture, testRoot.resolve("model.mxc"));
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        var location = new CatalogLocation(CatalogRootKind.AUTH, new ModelPath("fixture"));
        content = ManagedContainer.openIndexed(new CatalogIndexEntry(identity, location, file));
        var record = new CatalogRecord(location, new CatalogContentBinding(identity.modelId(), content));
        catalog = new CatalogSnapshot(Map.of(identity.modelId(), record), List.of(), ModelScanReport.empty());
    }

    @AfterEach
    void closeRuntime() {
        runtime.close();
    }

    @Test
    void invalidIndependentMediaIsSentAsDataWhileManagementStillObservesAMiss() throws Exception {
        var location = new CatalogLocation(CatalogRootKind.AUTH, new ModelPath("missing"));
        var missing = ManagedContainer.openDirect(fixtureWithoutPreview, location);
        try {
            var identity = missing.representation().identity();
            var owner = active(new CatalogSnapshot(
                Map.of(identity.modelId(),
                    new CatalogRecord(location, new CatalogContentBinding(identity.modelId(), missing))),
                List.of(), ModelScanReport.empty()));
            var root = testRoot.resolve("preview-cache");
            var path = Files.createDirectories(root.resolve("previews")).resolve(identity.containerId() + ".image");
            Files.write(path, new byte[] {1, 2, 3});
            var failures = new ArrayList<ResourceTransferFailureProto>();
            var port = new RecordingPort();
            try (var worker = new ResourceDispatchWorker(1, 8, 0); var transfers =
                new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime, previews)) {
                assertTrue(transfers.accept(previewRequest(1, identity)));
                awaitQueued(worker, owner, transfers);
                drain(worker, owner, transfers);
                assertTrue(failures.isEmpty());
                var frames = port.take();
                assertEquals(1, frames.size());
                try (var decoded = FrameCodec.decode(frames.get(0), id -> id == ProtocolMessages.PREVIEW_FRAGMENT_ID)) {
                    var message = ProtocolBuffer.parse(decoded.protobuf(), PreviewFragmentProto::parseFrom);
                    assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, message.delivery());
                    assertEquals(0, message.offset());
                    assertTrue(message.finalFragment());
                    var received = new byte[decoded.attachment().size()];
                    decoded.attachment().nio().get(received);
                    assertArrayEquals(new byte[] {1, 2, 3}, received);
                }
                assertTrue(previews.probe(identity.containerId()).isEmpty());
                assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(path));
                assertTrue(owner.findModel(identity.modelId()).isPresent());
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void missingStandalonePreviewReturnsAbsentWithoutNegativeRetention() throws Exception {
        var missing = standaloneContent();
        try {
            var identity = missing.representation().identity();
            var owner = active(catalogFor(missing));
            var path = Files.createDirectories(testRoot.resolve("preview-cache/previews"))
                .resolve(identity.containerId() + ".image");
            var failures = new ArrayList<ResourceTransferFailureProto>();
            var port = new RecordingPort();
            try (var worker = new ResourceDispatchWorker(1, 8, 0);
                var transfers = new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime)) {
                assertTrue(transfers.accept(previewRequest(1, identity)));
                awaitQueued(worker, owner, transfers);
                drain(worker, owner, transfers);
                var frames = port.take();
                assertEquals(1, frames.size());
                try (var decoded = FrameCodec.decode(frames.get(0), ignored -> true)) {
                    var part = ProtocolBuffer.parse(decoded.protobuf(), PreviewFragmentProto::parseFrom);
                    assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.ABSENT, part.delivery());
                    assertEquals(0, decoded.attachment().size());
                }
                Files.write(path, new byte[] {1, 2, 3});
                assertTrue(transfers.accept(previewRequest(2, identity)));
                awaitQueued(worker, owner, transfers);
                drain(worker, owner, transfers);
                try (var decoded = FrameCodec.decode(port.take().get(0), ignored -> true)) {
                    var part = ProtocolBuffer.parse(decoded.protobuf(), PreviewFragmentProto::parseFrom);
                    assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, part.delivery());
                }
                assertTrue(failures.isEmpty());
                assertEquals(0, transfers.activeCount());
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void cancellingAnActionAfterReadDeliveryStopsItsNextReadAndPreservesAnotherWaiter() throws Exception {
        var missing = standaloneContent();
        try {
            var identity = missing.representation().identity();
            var path = Files.createDirectories(testRoot.resolve("preview-cache/previews"))
                .resolve(identity.containerId() + ".image");
            Files.write(path, new byte[] {3, 2, 1});
            var reads = useControlledReads();
            var owner = active(catalogFor(missing));
            var port = new RecordingPort();
            var failures = new ArrayList<ResourceTransferFailureProto>();
            try (var worker = new ResourceDispatchWorker(1, 8, 0);
                var transfers = new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime)) {
                assertTrue(transfers.accept(previewRequest(1, identity, 2)));
                assertTrue(transfers.accept(previewRequest(2, identity)));
                runtime.tick();
                assertEquals(1, reads.pending());
                reads.runNext();
                runtime.tick();
                runtime.invalidatePreview(identity.containerId());
                assertTrue(transfers.cancel(1));
                transfers.tick();
                runtime.tick();
                assertEquals(0, reads.pending(), "The cancelled action must not begin its second read");
                assertEquals(1, worker.queued(owner));
                drain(worker, owner, transfers);
                var frames = port.take();
                assertEquals(1, frames.size());
                try (var decoded = FrameCodec.decode(frames.get(0), ignored -> true)) {
                    var part = ProtocolBuffer.parse(decoded.protobuf(), PreviewFragmentProto::parseFrom);
                    assertEquals(2, part.dataTransferId());
                    assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, part.delivery());
                }
                assertTrue(failures.isEmpty());
                assertEquals(0, transfers.activeCount());
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void closingAfterReadDeliveryDiscardsTheLateEventAndStopsTheNextRead() throws Exception {
        var missing = standaloneContent();
        try {
            var identity = missing.representation().identity();
            var path = Files.createDirectories(testRoot.resolve("preview-cache/previews"))
                .resolve(identity.containerId() + ".image");
            Files.write(path, new byte[] {1});
            var reads = useControlledReads();
            var owner = active(catalogFor(missing));
            var port = new RecordingPort();
            var failures = new ArrayList<ResourceTransferFailureProto>();
            try (var worker = new ResourceDispatchWorker(1, 8, 0);
                var transfers = new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime)) {
                assertTrue(transfers.accept(previewRequest(1, identity, 2)));
                runtime.tick();
                reads.runNext();
                runtime.tick();
                runtime.invalidatePreview(identity.containerId());
                transfers.close();
                transfers.tick();
                runtime.tick();
                assertEquals(0, reads.pending());
                assertEquals(0, worker.queued(owner));
                assertEquals(0, transfers.activeCount());
                assertTrue(port.frames.isEmpty());
                assertTrue(failures.isEmpty());
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void previewActionStopsBeforeItsTenthReadWhenActualBytesExceedTheAggregateBound() throws Exception {
        var missing = standaloneContent();
        try {
            var identity = missing.representation().identity();
            var path = Files.createDirectories(testRoot.resolve("preview-cache/previews"))
                .resolve(identity.containerId() + ".image");
            try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.position(PreviewStore.MAX_STORED_BYTES - 1L);
                file.write(ByteBuffer.wrap(new byte[] {1}));
            }
            var reads = useControlledReads();
            var owner = active(catalogFor(missing));
            var port = new RecordingPort();
            var failures = new ArrayList<ResourceTransferFailureProto>();
            try (var worker = new ResourceDispatchWorker(1, 16, 0);
                var transfers = new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime)) {
                assertTrue(transfers.accept(previewRequest(1, identity, 10)));
                for (var index = 0; index < 9; index++) {
                    runtime.tick();
                    assertEquals(1, reads.pending());
                    reads.runNext();
                    runtime.tick();
                    runtime.invalidatePreview(identity.containerId());
                    transfers.tick();
                    if (index < 8)
                        assertTrue(failures.isEmpty());
                }
                runtime.tick();
                assertEquals(0, reads.pending(), "The action must reject before starting its tenth read");
                assertEquals(1, failures.size());
                assertEquals(ResourceFailureReason.ITEM_TOO_LARGE, failures.get(0).reason());
                assertEquals(0, transfers.activeCount());
                assertEquals(0, worker.queued(owner));
                assertTrue(port.frames.isEmpty());
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void anIndependentPreviewPacketKeepsItsBytesAfterInvalidationAndFileReplacement() throws Exception {
        var missing = standaloneContent();
        try {
            var identity = missing.representation().identity();
            var path = Files.createDirectories(testRoot.resolve("preview-cache/previews"))
                .resolve(identity.containerId() + ".image");
            var expected = new byte[RangePacket.FRAGMENT_BYTES * 2 + 5];
            for (var index = 0; index < expected.length; index++)
                expected[index] = (byte)index;
            Files.write(path, expected);
            var owner = active(catalogFor(missing));
            var port = new RecordingPort();
            var failures = new ArrayList<ResourceTransferFailureProto>();
            try (var worker = new ResourceDispatchWorker(1, 8, 0);
                var transfers = new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime)) {
                assertTrue(transfers.accept(previewRequest(1, identity)));
                awaitQueued(worker, owner, transfers);
                runtime.invalidatePreview(identity.containerId());
                Files.write(path, new byte[] {9});
                drain(worker, owner, transfers);
                var received = new java.io.ByteArrayOutputStream();
                var frames = port.take();
                assertEquals(3, frames.size());
                for (var frame : frames) {
                    try (var decoded = FrameCodec.decode(frame, ignored -> true)) {
                        var part = ProtocolBuffer.parse(decoded.protobuf(), PreviewFragmentProto::parseFrom);
                        assertEquals(received.size(), part.offset());
                        var bytes = new byte[decoded.attachment().size()];
                        decoded.attachment().nio().get(bytes);
                        received.writeBytes(bytes);
                    }
                }
                assertArrayEquals(expected, received.toByteArray());
                assertTrue(failures.isEmpty());
                assertEquals(0, transfers.activeCount());
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void sharedRequestBacklogRejectsChunksAndStandalonePreviewsAsBusy() throws Exception {
        var missing = standaloneContent();
        try {
            for (var standalone : List.of(false, true)) {
                runtime.close();
                runtime = new ServerResourceRuntime(previews, ignored -> {
                });
                for (var index = 0; index < 4_096; index++)
                    runtime.acquirePreview(content.representation().containerId());
                var source = standalone ? missing : content;
                var owner = active(catalogFor(source));
                var port = new RecordingPort();
                var failures = new ArrayList<ResourceTransferFailureProto>();
                try (var worker = new ResourceDispatchWorker(1, 8, 0); var transfers =
                    new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime)) {
                    assertTrue(standalone ? transfers.accept(previewRequest(1, source.representation().identity()))
                        : transfers.accept(chunkRequest(1)));
                    transfers.tick();
                    assertEquals(1, failures.size());
                    assertEquals(ResourceFailureReason.BUSY, failures.get(0).reason());
                    assertEquals(0, transfers.activeCount());
                    assertEquals(0, worker.queued(owner));
                    assertTrue(port.frames.isEmpty());
                }
            }
        } finally {
            missing.representation().close();
        }
    }

    @Test
    void declaredPreviewReadFailureDoesNotFallBackToIndependentImageOrAbsent() throws Exception {
        var owner = active(catalog);
        var identity = content.representation().identity();
        var previews = this.previews;
        previews.accept(identity.containerId(), TestPreviews.blank().bytes());
        Files.delete(content.file());
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 8, 0); var transfers =
            new ServerAssetTransfers(owner, worker, port, () -> false, failures::add, runtime, previews)) {
            assertTrue(transfers.accept(previewRequest(1, identity)));
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (failures.isEmpty() && System.nanoTime() < deadline) {
                runtime.tick();
                transfers.tick();
                Thread.yield();
            }
            assertEquals(1, failures.size());
            assertEquals(ResourceFailureReason.UNAVAILABLE, failures.get(0).reason());
            assertTrue(port.frames.isEmpty());
            assertEquals(0, transfers.activeCount());
        }
    }

    private static PresentationPageRequestProto previewRequest(long id, ModelFileIdentity identity) {
        return previewRequest(id, identity, 1);
    }

    private static PresentationPageRequestProto previewRequest(long id, ModelFileIdentity identity, int count) {
        var request = PresentationPageRequestProto.newBuilder().setDataTransferId(id);
        for (var slot = 0; slot < count; slot++)
            request.addPreviews(
                PreviewMemberProto.newBuilder().setSlot(slot).setModelId(ProtoBytes.wrap(identity.modelId()))
                    .setContainerId(ProtoBytes.wrap(identity.containerId())).build());
        return request.build();
    }

    private ManagedContainer standaloneContent() throws IOException {
        return ManagedContainer.openDirect(fixtureWithoutPreview,
            new CatalogLocation(CatalogRootKind.AUTH, new ModelPath("standalone")));
    }

    private static CatalogSnapshot catalogFor(ManagedContainer source) {
        return new CatalogSnapshot(
            Map.of(source.modelId(),
                new CatalogRecord(source.location(), new CatalogContentBinding(source.modelId(), source))),
            List.of(), ModelScanReport.empty());
    }

    private ControlledReads useControlledReads() throws Exception {
        runtime.close();
        var reads = new ControlledReads();
        var constructor =
            ServerResourceRuntime.class.getDeclaredConstructor(com.elfmcys.ysm.model.storage.PreviewStorage.class,
                Consumer.class, LongSupplier.class, long.class, ExecutorService.class);
        constructor.setAccessible(true);
        runtime = constructor.newInstance(previews, (Consumer<IOException>)ignored -> {
        }, (LongSupplier)System::nanoTime, 64L * 1024 * 1024, reads);
        return reads;
    }

    private static final class ControlledReads extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean stopped;

        int pending() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        @Override
        public void execute(Runnable command) {
            if (stopped)
                throw new java.util.concurrent.RejectedExecutionException();
            tasks.addLast(command);
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            stopped = true;
            var remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    @Test
    void metadataAndChunkRequestsProduceTheirTypedFragments() {
        var owner = active(catalog);
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 8, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            var identity = content.representation().identity();
            assertTrue(transfers.accept(MetadataPrefixRequestProto.newBuilder().setDataTransferId(1)
                .addContainerIds(ProtoBytes.wrap(identity.containerId())).build()));
            drain(worker, owner, transfers);
            assertTrue(failures.isEmpty());
            assertFalse(port.frames.isEmpty());
            assertMetadataFrames(port.take(), identity);

            var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
                .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)).findFirst()
                .orElseThrow();
            var request = ModelChunkRequestProto.newBuilder().setDataTransferId(2)
                .setModelId(ProtoBytes.wrap(identity.modelId())).setContainerId(ProtoBytes.wrap(identity.containerId()))
                .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                    .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                    .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
                .build();
            assertTrue(transfers.accept(request));
            awaitQueued(worker, owner, transfers);
            drain(worker, owner, transfers);
            assertTrue(failures.isEmpty());
            assertChunkFrames(port.take(), chunk.type(), chunk.size());
        }
    }

    @Test
    void protectedChunkAuthorizationPrecedesSourceAvailability() throws Exception {
        var owner = active(catalog);
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var identity = content.representation().identity();
        var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)).findFirst()
            .orElseThrow();
        Files.delete(content.file());
        try (var worker = new ResourceDispatchWorker(1, 2, 0);
            var transfers = transfers(owner, worker, new RecordingPort(), true, failures)) {
            assertTrue(transfers.accept(ModelChunkRequestProto.newBuilder().setDataTransferId(1)
                .setModelId(ProtoBytes.wrap(identity.modelId())).setContainerId(ProtoBytes.wrap(identity.containerId()))
                .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                    .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                    .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
                .build()));
            assertEquals(1, failures.size());
            assertEquals(ResourceFailureReason.UNAUTHORIZED, failures.get(0).reason());
            assertEquals(0, worker.queued(owner));
        }
    }

    @Test
    void cancelBeforeChunkAcquisitionCompletesDoesNotRestartOrEnqueueLateSource() {
        var owner = active(catalog);
        var failures = new ArrayList<ResourceTransferFailureProto>();
        try (var worker = new ResourceDispatchWorker(1, 8, 0);
            var transfers = transfers(owner, worker, new RecordingPort(), false, failures)) {
            assertTrue(transfers.accept(chunkRequest(1)));
            assertEquals(1, transfers.activeCount());
            assertTrue(transfers.cancel(1));

            for (var attempt = 0; attempt < 20; attempt++) {
                runtime.tick();
                transfers.tick();
                Thread.yield();
            }
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
            assertTrue(failures.isEmpty());
        }
    }

    @Test
    void chunkAcquisitionFailureKeepsUnavailableProvenanceAndNeverEnqueues() throws Exception {
        var owner = active(catalog);
        var failures = new ArrayList<ResourceTransferFailureProto>();
        Files.delete(content.file());
        try (var worker = new ResourceDispatchWorker(1, 8, 0);
            var transfers = transfers(owner, worker, new RecordingPort(), false, failures)) {
            assertTrue(transfers.accept(chunkRequest(1)));
            var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (failures.isEmpty() && System.nanoTime() < deadline) {
                runtime.tick();
                transfers.tick();
                Thread.yield();
            }

            assertEquals(1, failures.size());
            assertEquals(com.elfmcys.ysm.proto.network.ResourceFailureReason.UNAVAILABLE, failures.get(0).reason());
            assertEquals(0, worker.queued(owner));
            assertEquals(0, transfers.activeCount());
        }
    }

    @Test
    void chunkSourceRetriesEveryCursorWithoutChangingItsRange() throws Exception {
        var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
            .max(Comparator.comparingInt(AssetContainerView.ChunkInfo::size)).orElseThrow();
        assertTrue(chunk.size() > RangePacket.FRAGMENT_BYTES * 2,
            "fixture must cover first, middle, and last range cursors");
        var expectedFuture = runtime.acquireChunk(content, chunk);
        var sourceDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!expectedFuture.isDone() && System.nanoTime() < sourceDeadline) {
            runtime.tick();
            Thread.yield();
        }
        assertTrue(expectedFuture.isDone());
        byte[] expected;
        try (var source = expectedFuture.join()) {
            expected = new byte[source.size()];
            source.copyTo(0, ByteBuffer.wrap(expected));
        }
        var owner = active(catalog);
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new CursorRetryingPort();
        try (var worker = new ResourceDispatchWorker(1, 8, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            assertTrue(transfers.accept(chunkRequest(1, chunk)));
            awaitQueued(worker, owner, transfers);

            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (worker.queued(owner) != 0 && System.nanoTime() < deadline) {
                if (!worker.dispatchOnce()) {
                    Thread.sleep(110);
                }
                transfers.tick();
            }
            assertEquals(0, worker.queued(owner));
            transfers.tick();
            assertEquals(0, transfers.activeCount());
            assertTrue(failures.isEmpty());

            var fragmentCount =
                Math.toIntExact((chunk.size() + (long)RangePacket.FRAGMENT_BYTES - 1) / RangePacket.FRAGMENT_BYTES);
            assertEquals(fragmentCount, port.attempts.size());
            for (var attempts : port.attempts.values()) {
                assertEquals(2, attempts.size());
                assertArrayEquals(attempts.get(0), attempts.get(1));
            }
            assertTrue(port.attempts.containsKey(0L));
            assertTrue(port.attempts.containsKey((long)RangePacket.FRAGMENT_BYTES));
            assertTrue(port.attempts.containsKey((long)(fragmentCount - 1) * RangePacket.FRAGMENT_BYTES));
            var rebuilt = new byte[chunk.size()];
            for (var entry : port.attempts.entrySet()) {
                var accepted = entry.getValue().get(1);
                System.arraycopy(accepted, 0, rebuilt, Math.toIntExact(entry.getKey()), accepted.length);
            }
            assertArrayEquals(expected, rebuilt);
        }
    }

    @Test
    void presentationDispatchUsesVerifiedRuntimeBytesAfterTheSourceDisappears() throws Exception {
        var identity = content.representation().identity();
        var chunk = content.modelFile().getFileView().getAssetView()
            .getChunkInfo(com.elfmcys.ysm.format.schema.model.ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        var preview = new AssetRef(AssetRef.Kind.PREVIEW, chunk.type(), new Hash256(chunk.hash()), chunk.size(),
            chunk.decodeSize(), chunk.encoding());
        var record = catalog.byModelId().get(identity.modelId());
        var entry = new CatalogEntry(identity.modelId(), record.entry().path(), record.entry().access(),
            new CatalogPresentation("fixture", "", Optional.empty(), Optional.of(preview)));
        var owner = active(new CatalogSnapshot(
            Map.of(identity.modelId(), new CatalogRecord(entry, record.location(), record.binding())), List.of(),
            ModelScanReport.empty()));
        var failures = new ArrayList<ResourceTransferFailureProto>();
        var port = new RecordingPort();
        try (var worker = new ResourceDispatchWorker(1, 8, 0);
            var transfers = transfers(owner, worker, port, false, failures)) {
            var request = PresentationPageRequestProto.newBuilder().setDataTransferId(1)
                .addPreviews(PreviewMemberProto.newBuilder().setSlot(0).setModelId(ProtoBytes.wrap(identity.modelId()))
                    .setContainerId(ProtoBytes.wrap(identity.containerId())).build())
                .build();

            assertTrue(transfers.accept(request));
            awaitQueued(worker, owner, transfers);
            Files.delete(content.file());
            drain(worker, owner, transfers);

            assertTrue(failures.isEmpty());
            var received = 0;
            for (var frame : port.take()) {
                try (var decoded = FrameCodec.decode(frame, id -> id == ProtocolMessages.PREVIEW_FRAGMENT_ID)) {
                    var message = ProtocolBuffer.parse(decoded.protobuf(), PreviewFragmentProto::parseFrom);
                    assertEquals(com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, message.delivery());
                    received += decoded.attachment().size();
                }
            }
            assertEquals(chunk.size(), received);
        }
    }

    private ServerAssetTransfers transfers(ServerModelSession owner, ResourceDispatchWorker worker, TransportPort port,
        boolean restricted, List<ResourceTransferFailureProto> failures) {
        return new ServerAssetTransfers(owner, worker, port, () -> restricted, failures::add, runtime,
            ignored -> testRoot);
    }

    private ModelChunkRequestProto chunkRequest(long transferId) {
        var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)).findFirst()
            .orElseThrow();
        return chunkRequest(transferId, chunk);
    }

    private ModelChunkRequestProto chunkRequest(long transferId, AssetContainerView.ChunkInfo chunk) {
        var identity = content.representation().identity();
        return ModelChunkRequestProto.newBuilder().setDataTransferId(transferId)
            .setModelId(ProtoBytes.wrap(identity.modelId())).setContainerId(ProtoBytes.wrap(identity.containerId()))
            .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
            .build();
    }

    private void awaitQueued(ResourceDispatchWorker worker, ServerModelSession owner, ServerAssetTransfers transfers) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.queued(owner) == 0 && System.nanoTime() < deadline) {
            runtime.tick();
            transfers.tick();
            Thread.yield();
        }
        assertTrue(worker.queued(owner) > 0, "chunk acquisition did not reach dispatch");
    }

    private static ServerModelSession active(CatalogSnapshot catalog) {
        var owner = new ServerModelSession(() -> catalog, false);
        assertTrue(owner.activate());
        owner.commitCatalog(catalog);
        return owner;
    }

    private static void drain(ResourceDispatchWorker worker, ServerModelSession owner, ServerAssetTransfers transfers) {
        while (worker.queued(owner) != 0) {
            assertTrue(worker.dispatchOnce());
            transfers.tick();
        }
    }

    private static void assertMetadataFrames(List<byte[]> frames, ModelFileIdentity identity) {
        for (var index = 0; index < frames.size(); index++) {
            try (var decoded =
                FrameCodec.decode(frames.get(index), id -> id == ProtocolMessages.METADATA_PREFIX_FRAGMENT_ID)) {
                assertEquals(0, decoded.attachment().size());
                var message = ProtocolBuffer.parse(decoded.protobuf(), MetadataPrefixFragmentProto::parseFrom);
                assertEquals(1, message.dataTransferId());
                assertTrue(ProtoBytes.equals(identity.containerId(), message.containerId()));
                assertEquals(index, message.sequence());
                assertEquals(index + 1 == frames.size(), message.finalFragment());
                assertTrue(message.payload().remaining() > 0);
            }
        }
    }

    private static void assertChunkFrames(List<byte[]> frames, String name, int size) {
        var received = 0;
        for (var index = 0; index < frames.size(); index++) {
            try (var decoded = FrameCodec.decode(frames.get(index), id -> id == ProtocolMessages.CHUNK_FRAGMENT_ID)) {
                var message = ProtocolBuffer.parse(decoded.protobuf(), ChunkFragmentProto::parseFrom);
                assertEquals(2, message.dataTransferId());
                assertEquals(name, message.name());
                assertEquals(received, message.offset());
                received += decoded.attachment().size();
                assertEquals(index + 1 == frames.size(), message.finalFragment());
            }
        }
        assertEquals(size, received);
    }

    private static final class RecordingPort implements TransportPort {
        private final List<byte[]> frames = new ArrayList<>();

        private List<byte[]> take() {
            var result = List.copyOf(frames);
            frames.clear();
            return result;
        }

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

    private static final class CursorRetryingPort implements TransportPort {
        private final Map<Long, List<byte[]>> attempts = new LinkedHashMap<>();

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
            try (var decoded = FrameCodec.decode(frame.bytes(), id -> id == ProtocolMessages.CHUNK_FRAGMENT_ID);
                var attachment = decoded.attachment().copyArray()) {
                var message = ProtocolBuffer.parse(decoded.protobuf(), ChunkFragmentProto::parseFrom);
                var copy = new byte[attachment.size()];
                System.arraycopy(attachment.array(), attachment.arrayOffset(), copy, 0, copy.length);
                var cursorAttempts = attempts.computeIfAbsent(message.offset(), ignored -> new ArrayList<>());
                cursorAttempts.add(copy);
                return cursorAttempts.size() == 1 ? SendResult.FAILED : SendResult.SUCCESS;
            }
        }
    }
}
