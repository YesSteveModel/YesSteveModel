package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.RestrictedModelPolicy;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistry;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.ChunkMemberProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic L1 contract suite covering 100-player concurrency, backpressure isolation and owner cleanup. */
@Tag("mock")
class ServerHundredPlayerContractTest {
    private static final int PLAYER_COUNT = 100;
    private static final int BACKPRESSURED_COUNT = 10;
    private static final int HARD_LIMIT = 4;
    private static final int DISPATCH_GUARD = 50_000;

    @TempDir
    Path temp;

    @Test
    void hundredSessionsProgressDespiteBackpressuredPeers() throws Exception {
        try (var fixture = createFixture(); var cache = new AtomicSharedCache(temp.resolve("cache-backpressure"));
            var runtime =
                new ServerResourceRuntime(new PreviewStore(temp.resolve("cache-backpressure"), cache), ignored -> {
                });
            var worker = new ResourceDispatchWorker(1, HARD_LIMIT, 0);
            var registry = new ServerConnectionRegistry<SessionOwner>()) {
            var owners = new ArrayList<SessionOwner>(PLAYER_COUNT);
            for (var i = 0; i < PLAYER_COUNT; i++) {
                var playerId = UUID.nameUUIDFromBytes(("player-" + i).getBytes());
                var owner = new SessionOwner(fixture.publicCatalog(), Set.of(), false, worker, runtime);
                owner.port.writable = i >= BACKPRESSURED_COUNT;
                registry.replace(playerId, new Object(), owner);
                owners.add(owner);
                assertTrue(owner.transfers.accept(metadataRequest(1, fixture.identity())));
                assertTrue(owner.transfers.accept(chunkRequest(2, fixture)));
            }

            // 90 healthy sessions must fully receive metadata + chunk despite 10 permanently backpressured peers.
            drainUntil(worker, runtime, owners, () -> owners.subList(BACKPRESSURED_COUNT, PLAYER_COUNT).stream()
                .allMatch(owner -> owner.port.completedTransfers() == 2 && worker.queued(owner.session) == 0));

            for (var i = 0; i < BACKPRESSURED_COUNT; i++) {
                var stalled = owners.get(i);
                assertEquals(0, stalled.port.completedTransfers(),
                    "Backpressured peer should not transmit while not writable");
                assertTrue(worker.queued(stalled.session) > 0);
                assertTrue(worker.queued(stalled.session) <= HARD_LIMIT);
                assertTrue(stalled.failures.isEmpty());
            }
            for (var i = BACKPRESSURED_COUNT; i < PLAYER_COUNT; i++) {
                var healthy = owners.get(i);
                assertEquals(2, healthy.port.completedTransfers());
                assertTrue(healthy.failures.isEmpty());
            }

            // Relieving backpressure allows the remaining 10 sessions to converge cleanly.
            for (var i = 0; i < BACKPRESSURED_COUNT; i++) {
                owners.get(i).port.writable = true;
            }
            drainUntil(worker, runtime, owners, () -> owners.stream()
                .allMatch(owner -> owner.port.completedTransfers() == 2 && worker.queued(owner.session) == 0));
        }
    }

    @Test
    void assetBackpressureDoesNotBlockGameplayFrames() throws Exception {
        try (var fixture = createFixture(); var cache = new AtomicSharedCache(temp.resolve("cache-gameplay"));
            var runtime =
                new ServerResourceRuntime(new PreviewStore(temp.resolve("cache-gameplay"), cache), ignored -> {
                });
            var worker = new ResourceDispatchWorker(1, HARD_LIMIT, 0)) {
            var owner = new SessionOwner(fixture.publicCatalog(), Set.of(), false, worker, runtime);
            try (owner) {
                owner.port.highWatermark = 32 * 1024;
                owner.port.pendingBytes = 32 * 1024;
                owner.port.writable = false;

                for (var transferId = 1L; transferId <= HARD_LIMIT; transferId++) {
                    assertTrue(owner.transfers.accept(metadataRequest(transferId, fixture.identity())));
                }
                // Exceeding hardLimit is rejected cleanly without dropping existing queued transfers.
                assertTrue(owner.transfers.accept(metadataRequest(HARD_LIMIT + 1L, fixture.identity())));
                assertEquals(1, owner.failures.size());
                assertEquals(ResourceFailureReason.BUSY, owner.failures.get(0).reason());
                assertEquals(HARD_LIMIT, worker.queued(owner.session));

                // Dispatch skips backpressured asset queue while interleaved gameplay frames flush with zero delay.
                for (var tick = 0; tick < 10; tick++) {
                    worker.dispatchOnce();
                    assertTrue(owner.port.sendGameplay("gameplay-tick-" + tick));
                }
                assertEquals(0, owner.port.completedTransfers());
                assertEquals(10, owner.port.gameplayFrames.size());
                assertEquals(HARD_LIMIT, worker.queued(owner.session));

                owner.port.pendingBytes = 0;
                owner.port.writable = true;
                drainUntil(worker, runtime, List.of(owner),
                    () -> owner.port.completedTransfers() == HARD_LIMIT && worker.queued(owner.session) == 0);
            }
        }
    }

    @Test
    void concurrentCatalogReplacementReconnectAndLeaveRetireOldWork() throws Exception {
        try (var fixture = createFixture(); var cache = new AtomicSharedCache(temp.resolve("cache-lifecycle"));
            var runtime =
                new ServerResourceRuntime(new PreviewStore(temp.resolve("cache-lifecycle"), cache), ignored -> {
                });
            var worker = new ResourceDispatchWorker(1, HARD_LIMIT, 0);
            var registry = new ServerConnectionRegistry<SessionOwner>()) {
            var activeOwners = new ArrayList<SessionOwner>();
            var staleOwners = new ArrayList<SessionOwner>();
            var replacedPlayers = new ArrayList<UUID>();
            var oldTokens = new ArrayList<Object>();

            for (var i = 0; i < PLAYER_COUNT; i++) {
                var playerId = UUID.nameUUIDFromBytes(("lifecycle-" + i).getBytes());
                var connA = new Object();
                var ownerA = new SessionOwner(fixture.publicCatalog(), Set.of(), false, worker, runtime);
                registry.replace(playerId, connA, ownerA);
                assertTrue(ownerA.transfers.accept(chunkRequest(1, fixture)));

                if (i < 10) {
                    // Same-player reconnect replaces old connection and retires its queued work immediately.
                    var connB = new Object();
                    var ownerB = new SessionOwner(fixture.publicCatalog(), Set.of(), false, worker, runtime);
                    registry.replace(playerId, connB, ownerB);
                    assertEquals(1, ownerA.closeCount.get());
                    assertEquals(0, worker.queued(ownerA.session));
                    assertFalse(registry.runIfCurrent(playerId, connA, ownerA, () -> {
                        throw new AssertionError("Stale connection must fail generation gate");
                    }));
                    staleOwners.add(ownerA);
                    replacedPlayers.add(playerId);
                    oldTokens.add(connA);
                    assertTrue(ownerB.transfers.accept(metadataRequest(1, fixture.identity())));
                    assertTrue(ownerB.transfers.accept(chunkRequest(2, fixture)));
                    activeOwners.add(ownerB);
                } else if (i < 20) {
                    // Mid-transfer disconnect drops session and queue cleanly.
                    registry.remove(playerId, connA);
                    assertEquals(1, ownerA.closeCount.get());
                    assertEquals(0, worker.queued(ownerA.session));
                    staleOwners.add(ownerA);
                } else if (i < 30) {
                    // Cancel + re-request on a live session.
                    assertTrue(ownerA.transfers.cancel(1));
                    assertTrue(ownerA.transfers.accept(chunkRequest(2, fixture)));
                    activeOwners.add(ownerA);
                } else {
                    activeOwners.add(ownerA);
                }
            }

            // Concurrent catalog snapshot replacement while remaining sessions drain.
            var replacementCatalog = new CatalogSnapshot(Map.of(), List.of(), ModelScanReport.empty());
            for (var owner : activeOwners) {
                owner.session.commitCatalog(replacementCatalog, Set.of());
            }

            // New request against retired model must be rejected at admission without entering worker.
            var probeOwner = activeOwners.get(0);
            assertTrue(probeOwner.transfers.accept(chunkRequest(99, fixture)));
            assertEquals(ResourceFailureReason.NOT_FOUND, probeOwner.failures.get(0).reason());

            drainUntil(worker, runtime, activeOwners, () -> activeOwners.stream()
                .allMatch(owner -> worker.queued(owner.session) == 0 && owner.port.completedTransfers() >= 1));

            for (var i = 0; i < replacedPlayers.size(); i++) {
                assertTrue(registry.current(replacedPlayers.get(i), oldTokens.get(i)).isEmpty());
            }
            for (var stale : staleOwners) {
                assertEquals(1, stale.closeCount.get());
                assertEquals(0, worker.queued(stale.session));
            }
        }
    }

    @Test
    void unauthorizedAndMalformedRequestsConsumeNoDispatchCapacity() throws Exception {
        try (var fixture = createFixture(); var cache = new AtomicSharedCache(temp.resolve("cache-auth"));
            var runtime = new ServerResourceRuntime(new PreviewStore(temp.resolve("cache-auth"), cache), ignored -> {
            }); var worker = new ResourceDispatchWorker(1, HARD_LIMIT, 0)) {
            var owner = new SessionOwner(fixture.authCatalog(), Set.of(), true, worker, runtime);
            try (owner) {
                // Malformed transferId == 0 returns false (session violation) and queues nothing.
                assertFalse(owner.transfers.accept(chunkRequest(0, fixture)));
                assertEquals(0, worker.queued(owner.session));

                // Unauthorized restricted model request is rejected at admission without entering worker.
                assertTrue(owner.transfers.accept(chunkRequest(1, fixture)));
                assertEquals(0, worker.queued(owner.session));
                assertEquals(1, owner.failures.size());
                assertEquals(ResourceFailureReason.UNAUTHORIZED, owner.failures.get(0).reason());

                // Granting permission allows valid requests and validates chunk descriptors at admission.
                owner.session.setGrants(Set.of(fixture.identity().modelId()));

                // Unknown chunk is rejected without leaking capacity.
                var badChunk = ModelChunkRequestProto.newBuilder().setDataTransferId(2)
                    .setModelId(fixture.identity().modelId().toString())
                    .setContainerId(ProtoBytes.wrap(fixture.identity().containerId()))
                    .addChunks(ChunkMemberProto.newBuilder().setName("missing_chunk")
                        .setExpectedHash(ByteBuffer.wrap(new byte[Hash256.SIZE])).setStoredSize(1).setDecodedSize(1)
                        .setEncoding("raw").build())
                    .build();
                assertTrue(owner.transfers.accept(badChunk));
                assertEquals(0, worker.queued(owner.session));
                assertEquals(ResourceFailureReason.INVALID_REQUEST, owner.failures.get(1).reason());

                assertTrue(owner.transfers.accept(chunkRequest(3, fixture)));
                drainUntil(worker, runtime, List.of(owner),
                    () -> owner.port.completedTransfers() == 1 && worker.queued(owner.session) == 0);
                assertEquals(2, owner.failures.size());
            }
        }
    }

    @Test
    void shutdownClosesAllAcceptedOwnersAndLeavesNoQueuedWork() throws Exception {
        var sessions = new ArrayList<ServerModelSession>(PLAYER_COUNT);
        var owners = new ArrayList<SessionOwner>(PLAYER_COUNT);
        try (var fixture = createFixture(); var cache = new AtomicSharedCache(temp.resolve("cache-shutdown"));
            var runtime =
                new ServerResourceRuntime(new PreviewStore(temp.resolve("cache-shutdown"), cache), ignored -> {
                });
            var worker = new ResourceDispatchWorker(1, HARD_LIMIT, 0);
            var registry = new ServerConnectionRegistry<SessionOwner>()) {
            for (var i = 0; i < PLAYER_COUNT; i++) {
                var playerId = UUID.nameUUIDFromBytes(("shutdown-" + i).getBytes());
                var owner = new SessionOwner(fixture.publicCatalog(), Set.of(), false, worker, runtime);
                owner.port.writable = false;
                registry.replace(playerId, new Object(), owner);
                assertTrue(owner.transfers.accept(metadataRequest(1, fixture.identity())));
                assertTrue(owner.transfers.accept(chunkRequest(2, fixture)));
                sessions.add(owner.session);
                owners.add(owner);
            }
            registry.close();
            for (var i = 0; i < PLAYER_COUNT; i++) {
                assertEquals(1, owners.get(i).closeCount.get());
                assertEquals(0, worker.queued(sessions.get(i)));
            }
        }
        // Idempotent second close check: none of the owners were closed more than once.
        owners.forEach(owner -> assertEquals(1, owner.closeCount.get()));
    }

    private static void drainUntil(ResourceDispatchWorker worker, ServerResourceRuntime runtime,
        List<SessionOwner> owners, java.util.function.BooleanSupplier condition) {
        for (var step = 0; step < DISPATCH_GUARD; step++) {
            runtime.tick();
            owners.forEach(owner -> owner.transfers.tick());
            if (condition.getAsBoolean()) {
                return;
            }
            if (!worker.dispatchOnce()) {
                Thread.yield();
            }
        }
        throw new AssertionError("Bounded dispatch guard exhausted before contract condition converged");
    }

    private ModelFixture createFixture() throws Exception {
        var manifest = ServerHundredPlayerContractTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest, "builtin fixture missing").toURI()).getParent();
        final Path file;
        try (var vfs = new Directory(source)) {
            file = ModelParser.parse(vfs, Files.createDirectories(temp.resolve("parsed-model")),
                DefaultAnimationFilter.keepAll());
        }
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        var publicLocation = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("contract/public"));
        var content = ManagedContainer.openIndexed(new CatalogIndexEntry(identity, publicLocation, file));
        var publicRecord = new CatalogRecord(publicLocation, new CatalogContentBinding(identity.modelId(), content));
        var authLocation = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("contract/auth"));
        var authRecord = new CatalogRecord(authLocation, new CatalogContentBinding(identity.modelId(), content));
        var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
            .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)).findFirst()
            .orElseThrow();
        var publicCatalog =
            new CatalogSnapshot(Map.of(identity.modelId(), publicRecord), List.of(), ModelScanReport.empty());
        var authCatalog = RestrictedModelPolicy.classify(
            new CatalogSnapshot(Map.of(identity.modelId(), authRecord), List.of(), ModelScanReport.empty()),
            RestrictedModelPolicy.parse(List.of("path:local:contract/auth")));
        return new ModelFixture(identity, content, publicCatalog, authCatalog, chunk);
    }

    private static MetadataPrefixRequestProto metadataRequest(long transferId, ModelFileIdentity identity) {
        return MetadataPrefixRequestProto.newBuilder().setDataTransferId(transferId)
            .addContainerIds(ProtoBytes.wrap(identity.containerId())).build();
    }

    private static ModelChunkRequestProto chunkRequest(long transferId, ModelFixture fixture) {
        var chunk = fixture.chunk();
        return ModelChunkRequestProto.newBuilder().setDataTransferId(transferId)
            .setModelId(fixture.identity().modelId().toString())
            .setContainerId(ByteBuffer.wrap(fixture.identity().containerId().bytes()))
            .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
            .build();
    }

    private record ModelFixture(ModelFileIdentity identity, ManagedContainer content, CatalogSnapshot publicCatalog,
        CatalogSnapshot authCatalog, AssetContainerView.ChunkInfo chunk) implements AutoCloseable {
        @Override
        public void close() {
            content.representation().close();
        }
    }

    private static final class SessionOwner implements AutoCloseable {
        private final ServerModelSession session;
        private final ResourceDispatchWorker worker;
        private final SimulatedTransportPort port = new SimulatedTransportPort();
        private final List<ResourceTransferFailureProto> failures = new ArrayList<>();
        private final ServerAssetTransfers transfers;
        private final AtomicInteger closeCount = new AtomicInteger();

        private SessionOwner(CatalogSnapshot catalog, Set<ModelId> grants, boolean restrictedAuth,
            ResourceDispatchWorker worker, ServerResourceRuntime runtime) {
            this.worker = worker;
            session = new ServerModelSession(() -> catalog, false);
            assertTrue(session.activate());
            session.commitCatalog(catalog, grants);
            transfers = new ServerAssetTransfers(session, worker, port, () -> restrictedAuth, failures::add, runtime);
        }

        @Override
        public void close() {
            if (closeCount.incrementAndGet() == 1) {
                transfers.close();
                worker.disconnect(session);
                session.close();
            }
        }
    }

    private static final class SimulatedTransportPort implements TransportPort {
        private boolean open = true;
        private boolean writable = true;
        private long highWatermark = 1024 * 1024;
        private long pendingBytes;
        private final List<Long> completedMetadata = new ArrayList<>();
        private final List<Long> completedChunks = new ArrayList<>();
        private final Queue<String> gameplayFrames = new ArrayDeque<>();

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isWritable() {
            return open && writable;
        }

        @Override
        public long highWatermarkBytes() {
            return highWatermark;
        }

        @Override
        public long pendingBytes() {
            return pendingBytes;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            if (!isOpen()) {
                return SendResult.FAILED;
            }
            try (var decoded = FrameCodec.decode(frame.bytes(),
                id -> id == ProtocolMessages.METADATA_PREFIX_FRAGMENT_ID || id == ProtocolMessages.CHUNK_FRAGMENT_ID)) {
                if (decoded.messageId() == ProtocolMessages.METADATA_PREFIX_FRAGMENT_ID) {
                    var msg = ProtocolBuffer.parse(decoded.protobuf(), MetadataPrefixFragmentProto::parseFrom);
                    if (msg.finalFragment()) {
                        completedMetadata.add(msg.dataTransferId());
                    }
                } else if (decoded.messageId() == ProtocolMessages.CHUNK_FRAGMENT_ID) {
                    var msg = ProtocolBuffer.parse(decoded.protobuf(), ChunkFragmentProto::parseFrom);
                    if (msg.finalFragment()) {
                        completedChunks.add(msg.dataTransferId());
                    }
                }
                return SendResult.SUCCESS;
            }
        }

        private boolean sendGameplay(String frame) {
            if (!open) {
                return false;
            }
            gameplayFrames.add(frame);
            return true;
        }

        private int completedTransfers() {
            return completedMetadata.size() + completedChunks.size();
        }
    }
}
