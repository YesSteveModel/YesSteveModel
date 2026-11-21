package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.ModelSystem;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistry;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.network.dispatch.ResourceDispatchWorker;
import com.elfmcys.ysm.network.dispatch.SendResult;
import com.elfmcys.ysm.network.dispatch.ServerAssetTransfers;
import com.elfmcys.ysm.network.dispatch.TransportPort;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.network.ChunkMemberProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.util.ProtoBytes;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless L2 server scale harness measuring MSPT, lag spikes, catalog reloads and gameplay delay across 100/200/300
 * simulated players backed by Netty EmbeddedChannel watermark backpressure.
 */
public final class ServerScaleLoadHarness {
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final int WARMUP_TICKS = 20;
    private static final int LOW_WATERMARK_BYTES = 16 * 1024;
    private static final int HIGH_WATERMARK_BYTES = 32 * 1024;
    private static final long NORMAL_BYTES_PER_TICK = 128 * 1024; // ~2.5 MB/s
    private static final long SLOW_BYTES_PER_TICK = 512; // ~10 KB/s

    private ServerScaleLoadHarness() {}

    public static void main(String[] args) throws Exception {
        var config = Config.parse(args);
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.WARN));

        var workDir = Files.createTempDirectory("ysm-scale-load-");
        try {
            var report = run(config, workDir);
            printSummary(report);
            if (config.output != null) {
                var out = config.output.toAbsolutePath().normalize();
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            }
        } finally {
            deleteTree(workDir);
        }
    }

    private static Map<String, Object> run(Config config, Path workDir) throws Exception {
        var builtinJson = ServerScaleLoadHarness.class.getResource("/assets/ysm/builtin/default/ysm.json");
        var sourceBuiltinRoot =
            Path.of(Objects.requireNonNull(builtinJson, "builtin default missing").toURI()).getParent().getParent();
        var harnessAssets = Files.createDirectories(workDir.resolve("assets"));
        var builtinRoot = Files.createDirectories(harnessAssets.resolve("builtin"));
        copyTree(sourceBuiltinRoot.resolve("default"), builtinRoot.resolve("default"));
        var fullIndex = BuiltinModelIndex.read(sourceBuiltinRoot.resolveSibling("builtin-index.json"));
        var defaultPath = new ModelPath("default");
        BuiltinModelIndex.of(Map.of(defaultPath, fullIndex.require(defaultPath)))
            .write(harnessAssets.resolve("builtin-index.json"));

        var gameDir = Files.createDirectories(workDir.resolve("game"));
        var configDir = Files.createDirectories(workDir.resolve("config"));
        var paths = AssetPaths.localOnly(gameDir, configDir, builtinRoot);

        // Build real .mxc model fixtures in paths.modelsRoot() before opening ModelSystem.
        var alexJson = ServerScaleLoadHarness.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var modelSource = Path.of(Objects.requireNonNull(alexJson, "1_alex fixture missing").toURI()).getParent();
        populateModels(modelSource, paths.modelsRoot(), config.models);

        var latestPublication = new AtomicReference<CatalogSnapshot>();
        var totalAssetFrames = new AtomicLong();
        var totalRejected = new AtomicLong();
        var backpressureEvents = new AtomicLong();
        var reloadsCompleted = new AtomicLong();

        try (var system = ModelSystem.openDefault(paths); var worker = new ResourceDispatchWorker(2, 8, 0);
            var registry = new ServerConnectionRegistry<ScalePlayer>()) {
            var catalog = system.catalog();
            var serverResources = system.serverResources();
            catalog.subscribe(transition -> latestPublication.set(transition.current()));
            catalog.activate();
            while (catalog.busy()) {
                catalog.tick();
                Thread.sleep(5);
            }
            var initialSnapshot = catalog.current();
            var records = initialSnapshot.byModelId().values().stream()
                .filter(record -> record.location().rootKind() != CatalogRootKind.BUILTIN).toList();
            if (records.isEmpty()) {
                throw new IllegalStateException("Expected generated custom models in catalog snapshot");
            }

            int slowCount = Math.max(0, Math.min(config.players, (int)Math.round(config.players * config.slowRatio)));
            var players = new ArrayList<ScalePlayer>(config.players);
            for (var i = 0; i < config.players; i++) {
                var slow = i < slowCount;
                var player = new ScalePlayer(UUID.nameUUIDFromBytes(("scale-" + i).getBytes()), initialSnapshot,
                    slow ? SLOW_BYTES_PER_TICK : NORMAL_BYTES_PER_TICK, worker, serverResources, totalAssetFrames,
                    totalRejected, backpressureEvents);
                registry.replace(player.playerId, new Object(), player);
                players.add(player);
            }

            worker.start();

            int totalTicks = Math.max(1, (int)((config.durationSec * 1_000L) / 50L));
            int reloadEveryTicks = Math.max(1, config.reloadIntervalMs / 50);
            var tickNanos = new long[totalTicks];
            var gameplayDelaysNanos = new ArrayList<Long>(totalTicks * 16);
            var warmupDelays = new ArrayList<Long>();
            var nextTransferId = 1L;
            var modelCursor = 0;

            for (var step = 0; step < WARMUP_TICKS + totalTicks; step++) {
                boolean measuring = step >= WARMUP_TICKS;
                int measureTick = step - WARMUP_TICKS;
                if (step == WARMUP_TICKS) {
                    totalAssetFrames.set(0);
                    totalRejected.set(0);
                    backpressureEvents.set(0);
                    reloadsCompleted.set(0);
                    warmupDelays.clear();
                }

                long tickStart = System.nanoTime();

                // 1. Trigger periodic catalog reloads and adopt completed publications on server tick.
                if (measuring && measureTick > 0 && measureTick % reloadEveryTicks == 0 && !catalog.busy()) {
                    catalog.reload();
                }
                var wasBusy = catalog.busy();
                catalog.tick();
                if (measuring && wasBusy && !catalog.busy()) {
                    reloadsCompleted.incrementAndGet();
                }
                var published = latestPublication.getAndSet(null);
                if (published != null) {
                    for (var player : players) {
                        player.session.commitCatalog(published, Set.of());
                    }
                    records = published.byModelId().values().stream()
                        .filter(record -> record.location().rootKind() != CatalogRootKind.BUILTIN).toList();
                }

                // 2. Admit staggered metadata + chunk requests across active sessions.
                if (!records.isEmpty()) {
                    for (var player : players) {
                        if (worker.queued(player.session) < 4) {
                            var record = records.get(modelCursor++ % records.size());
                            long id = nextTransferId++;
                            if ((id & 1L) == 0) {
                                player.transfers.accept(metadataRequest(id, record));
                            } else {
                                player.transfers.accept(chunkRequest(id, record));
                            }
                        }
                    }
                }

                // 3. Enqueue gameplay traffic on every player channel and tick server resource owners.
                long gameplaySentAt = System.nanoTime();
                for (var player : players) {
                    player.port.sendGameplayTick(gameplaySentAt);
                    player.transfers.tick();
                }
                serverResources.tick();

                long tickWorkNanos = System.nanoTime() - tickStart;
                if (measuring) {
                    tickNanos[measureTick] = tickWorkNanos;
                }

                // 4. Simulate Netty transport draining between ticks and record healthy gameplay delays.
                var delaySink = measuring ? gameplayDelaysNanos : warmupDelays;
                for (var player : players) {
                    player.port.drainTick(delaySink);
                }

                long remaining = TICK_NANOS - (System.nanoTime() - tickStart);
                if (remaining > 1_000_000L) {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                }
            }

            if (slowCount > 0 && backpressureEvents.get() == 0) {
                throw new IllegalStateException("Expected Netty WriteBufferWaterMark backpressure on slow channels");
            }

            Arrays.sort(tickNanos);
            double avgMs = Arrays.stream(tickNanos).average().orElse(0.0) / 1_000_000.0;
            double p95Ms = percentileMs(tickNanos, 0.95);
            double p99Ms = percentileMs(tickNanos, 0.99);
            double maxMs = tickNanos[tickNanos.length - 1] / 1_000_000.0;

            var gpArray = gameplayDelaysNanos.stream().mapToLong(Long::longValue).sorted().toArray();
            double gameplayP99Ms = gpArray.length == 0 ? 0.0 : percentileMs(gpArray, 0.99);

            var result = new LinkedHashMap<String, Object>();
            result.put("players", config.players);
            result.put("slow_players", slowCount);
            result.put("models", config.models);
            result.put("duration_sec", config.durationSec);
            result.put("ticks", totalTicks);
            result.put("reloads", reloadsCompleted.get());
            result.put("completed_asset_frames", totalAssetFrames.get());
            result.put("rejected_requests", totalRejected.get());
            result.put("backpressure_events", backpressureEvents.get());
            result.put("tick_mspt_avg_ms", round3(avgMs));
            result.put("tick_mspt_p95_ms", round3(p95Ms));
            result.put("tick_mspt_p99_ms", round3(p99Ms));
            result.put("tick_mspt_max_ms", round3(maxMs));
            result.put("gameplay_p99_delay_ms", round3(gameplayP99Ms));
            return result;
        }
    }

    private static void populateModels(Path modelSource, Path modelsRoot, int count) throws Exception {
        Files.createDirectories(modelsRoot);
        var compiled = Files.createTempDirectory("ysm-scale-seed-");
        try {
            final Path seedFile;
            try (var vfs = new Directory(modelSource)) {
                seedFile = ModelParser.parse(vfs, compiled, DefaultAnimationFilter.keepAll());
            }
            var base = ManagedContainer.openDirect(seedFile,
                new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("seed/alex")));
            var blank = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
            try (var baseRep = base.representation(); var preview = PreviewStore.decode(blank)) {
                var baseView = base.view();
                for (var index = 0; index < count; index++) {
                    var id = new byte[Hash256.SIZE];
                    ByteBuffer.wrap(id).putInt(index + 100);
                    var properties = PropertiesProto.newBuilder(baseView.getManifest().info().properties())
                        .setModelId("hash:" + java.util.HexFormat.of().formatHex(id)).build();
                    var info = InfoProto.newBuilder(baseView.getManifest().info()).setProperties(properties)
                        .setThumbnailSource(PreviewSource.GENERATED).build();
                    var manifest = ManifestProto.newBuilder(baseView.getManifest()).setInfo(info).build();
                    try (var writer = new ModelFileWriter(); var image = preview.open();
                        var channel = FileChannel.open(modelsRoot.resolve("scale-model-" + index + ".mxc"),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        writer.setManifest(manifest);
                        for (var chunk : baseView.getFileView().getAssetView().getChunkTable().values().stream()
                            .filter(c -> !c.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                                && !c.type()
                                    .equals(com.elfmcys.ysm.format.schema.model.ModelFileConstant.MANIFEST_CHUNK_NAME)
                                && !c.type().equals(
                                    com.elfmcys.ysm.format.schema.model.ModelFileConstant.THUMB_BUTTON_CHUNK_NAME))
                            .sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList()) {
                            var bytes =
                                base.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY);
                            writer.addStoredChunk(chunk, bytes);
                        }
                        writer.setThumbnail(image);
                        writer.write(channel);
                    }
                }
            }
        } finally {
            deleteTree(compiled);
        }
    }

    private static MetadataPrefixRequestProto metadataRequest(long transferId, CatalogRecord record) {
        return MetadataPrefixRequestProto.newBuilder().setDataTransferId(transferId)
            .addContainerIds(ProtoBytes.wrap(record.binding().content().representation().containerId())).build();
    }

    private static ModelChunkRequestProto chunkRequest(long transferId, CatalogRecord record) {
        var identity = record.binding().content().representation().identity();
        var builder = ModelChunkRequestProto.newBuilder().setDataTransferId(transferId)
            .setModelId(identity.modelId().toString()).setContainerId(ProtoBytes.wrap(identity.containerId()));
        for (var chunk : record.binding().content().modelFile().getFileView().getAssetView().getChunkTable().values()) {
            if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                builder.addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                    .setExpectedHash(ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                    .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build());
            }
        }
        return builder.build();
    }

    private static double percentileMs(long[] sortedNanos, double p) {
        int idx = Math.min(sortedNanos.length - 1, Math.max(0, (int)Math.ceil(sortedNanos.length * p) - 1));
        return sortedNanos[idx] / 1_000_000.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static void printSummary(Map<String, Object> report) {
        System.out.println("=== YSM Server Scale Load Report ===");
        report.forEach((k, v) -> System.out.printf("  %-24s: %s%n", k, v));
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (var src : paths.toList()) {
                var dst = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst);
                }
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private static final class ScalePlayer implements AutoCloseable {
        private final UUID playerId;
        private final ServerModelSession session;
        private final ResourceDispatchWorker worker;
        private final NettyTransportPort port;
        private final ServerAssetTransfers transfers;

        private ScalePlayer(UUID playerId, CatalogSnapshot initial, long bytesPerTick, ResourceDispatchWorker worker,
            ServerResourceRuntime runtime, AtomicLong frameCounter, AtomicLong rejectionCounter,
            AtomicLong backpressureCounter) {
            this.playerId = playerId;
            this.worker = worker;
            session = new ServerModelSession(() -> initial, false);
            session.activate();
            session.commitCatalog(initial, Set.of());
            port = new NettyTransportPort(bytesPerTick, frameCounter, backpressureCounter);
            transfers = new ServerAssetTransfers(session, worker, port, () -> false,
                ignored -> rejectionCounter.incrementAndGet(), runtime);
        }

        @Override
        public void close() {
            transfers.close();
            worker.disconnect(session);
            session.close();
            port.close();
        }
    }

    private static final class NettyTransportPort implements TransportPort, AutoCloseable {
        private final EmbeddedChannel channel;
        private final LinkShaperOutboundHandler shaper;
        private final long bytesPerTick;
        private final AtomicLong frameCounter;
        private final AtomicLong backpressureCounter;
        private final Queue<Long> pendingGameplayNanos = new ArrayDeque<>();
        private long creditBytes;

        private NettyTransportPort(long bytesPerTick, AtomicLong frameCounter, AtomicLong backpressureCounter) {
            this.bytesPerTick = bytesPerTick;
            this.frameCounter = frameCounter;
            this.backpressureCounter = backpressureCounter;
            shaper = new LinkShaperOutboundHandler();
            channel = new EmbeddedChannel(shaper);
            channel.config()
                .setWriteBufferWaterMark(new WriteBufferWaterMark(LOW_WATERMARK_BYTES, HIGH_WATERMARK_BYTES));
        }

        @Override
        public synchronized boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public synchronized boolean isWritable() {
            var writable = isOpen() && channel.isWritable();
            if (!writable) {
                backpressureCounter.incrementAndGet();
            }
            return writable;
        }

        @Override
        public synchronized long highWatermarkBytes() {
            return channel.config().getWriteBufferHighWaterMark();
        }

        @Override
        public synchronized long pendingBytes() {
            var outbound = channel.unsafe().outboundBuffer();
            return outbound == null ? 0 : outbound.totalPendingWriteBytes();
        }

        @Override
        public synchronized SendResult trySend(OutboundFrame frame) {
            if (!isOpen()) {
                return SendResult.FAILED;
            }
            var bytes = frame.bytes();
            var buf = channel.alloc().buffer(bytes.length);
            buf.writeBytes(bytes);
            // Write without immediate flush so ChannelOutboundBuffer enforces real WriteBufferWaterMark.
            channel.write(buf);
            if (!channel.isWritable()) {
                backpressureCounter.incrementAndGet();
            }
            return SendResult.SUCCESS;
        }

        private synchronized void sendGameplayTick(long sentAtNanos) {
            if (isOpen()) {
                pendingGameplayNanos.add(sentAtNanos);
                var buf = channel.alloc().buffer(256);
                buf.writeZero(256);
                channel.write(buf);
            }
        }

        private synchronized void drainTick(List<Long> healthyGameplayDelays) {
            if (!isOpen()) {
                return;
            }
            long pending = pendingBytes();
            if (pending == 0) {
                creditBytes = 0;
            } else {
                creditBytes = Math.min(HIGH_WATERMARK_BYTES * 2L, creditBytes + bytesPerTick);
                if (creditBytes >= pending) {
                    channel.flush();
                    creditBytes -= pending;
                    Object msg;
                    while ((msg = channel.readOutbound()) != null) {
                        if (msg instanceof ByteBuf) {
                            frameCounter.incrementAndGet();
                        }
                        ReferenceCountUtil.release(msg);
                    }
                }
            }
            // Healthy channels (not backpressured by asset outbound buffer) flush gameplay immediately.
            if (channel.isWritable()) {
                long now = System.nanoTime();
                Long sent;
                while ((sent = pendingGameplayNanos.poll()) != null) {
                    healthyGameplayDelays.add(Math.max(0L, now - sent));
                }
            }
        }

        @Override
        public synchronized void close() {
            pendingGameplayNanos.clear();
            channel.finishAndReleaseAll();
        }
    }

    private static final class LinkShaperOutboundHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.write(msg, promise);
        }
    }

    private record Config(int players, int models, int durationSec, double slowRatio, int reloadIntervalMs,
        Path output) {
        private static Config parse(String[] args) {
            int players = 100;
            int models = 8;
            int durationSec = 10;
            double slowRatio = 0.10;
            int reloadIntervalMs = 2_000;
            Path output = null;
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--players" -> players = Integer.parseInt(args[++i]);
                    case "--models" -> models = Integer.parseInt(args[++i]);
                    case "--duration-sec" -> durationSec = Integer.parseInt(args[++i]);
                    case "--slow-ratio" -> slowRatio = Double.parseDouble(args[++i]);
                    case "--reload-interval-ms" -> reloadIntervalMs = Integer.parseInt(args[++i]);
                    case "--output" -> output = Path.of(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            return new Config(players, models, durationSec, slowRatio, reloadIntervalMs, output);
        }
    }
}
