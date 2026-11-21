package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.proto.network.ChunkMemberProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.openjdk.jmh.annotations.*;

/** Measures polling, cancellation and dispatch adoption through production resource uses. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class ServerAcquisitionBenchmark {
    public abstract static class Fixture {
        Path root;
        ManagedContainer content;
        ServerResourceRuntime resources;
        ControlledReads reads;
        ServerModelSession owner;
        ResourceDispatchWorker dispatch;
        ServerAssetTransfers transfers;
        ModelChunkRequestProto template;
        long id;

        @Setup(Level.Trial)
        public void open() throws Exception {
            com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            com.elfmcys.ysm.natives.NativeRuntime.initialize(
                com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            new com.elfmcys.ysm.testutil.DerivedStorageTestScope().beforeEach(null);
            root = Files.createTempDirectory("ysm-server-acquisition-");
            var source =
                Path.of(ServerAcquisitionBenchmark.class.getResource("/assets/ysm/builtin/default/ysm.json").toURI())
                    .getParent();
            try (var directory = new Directory(source)) {
                var file = ModelParser.parseBuiltinDefault(directory, Files.createDirectories(root.resolve("model")));
                var location = new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("benchmark"));
                content = ManagedContainer.openDirect(file, location);
                var catalog = new CatalogSnapshot(
                    Map.of(content.modelId(),
                        new CatalogRecord(location, new CatalogContentBinding(content.modelId(), content))),
                    List.of(), ModelScanReport.empty());
                owner = new ServerModelSession(() -> catalog, false);
                owner.activate();
                owner.commitCatalog(catalog);
            }
            reads = new ControlledReads();
            var constructor = ServerResourceRuntime.class.getDeclaredConstructor(PreviewStorage.class, Consumer.class,
                LongSupplier.class, long.class, ExecutorService.class);
            constructor.setAccessible(true);
            // Oversize delivery avoids turning the next Ready sample into an immediate cache hit.
            resources = constructor.newInstance(
                new PreviewStore(root.resolve("previews"),
                    com.elfmcys.ysm.testutil.DerivedStorageTestScope
                        .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(root.resolve("previews")))),
                (Consumer<IOException>)failure -> {
                    throw new AssertionError(failure);
                }, (LongSupplier)System::nanoTime, 1L, reads);
            dispatch = new ResourceDispatchWorker(1, 256, 0);
            transfers = transferOwner();
            var chunk = content.modelFile().getFileView().getAssetView().getChunkTable().values().stream()
                .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                .filter(value -> value.size() > 1).min(Comparator.comparingInt(AssetContainerView.ChunkInfo::size))
                .orElseThrow();
            template = ModelChunkRequestProto.newBuilder().setModelId(content.modelId().toString())
                .setContainerId(ProtoBytes.wrap(content.representation().containerId()))
                .addChunks(ChunkMemberProto.newBuilder().setName(chunk.type())
                    .setExpectedHash(java.nio.ByteBuffer.wrap(chunk.hash())).setStoredSize(chunk.size())
                    .setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build())
                .build();
        }

        ServerAssetTransfers transferOwner() {
            return new ServerAssetTransfers(owner, dispatch, new Port(), () -> false, failure -> {
                throw new AssertionError(failure.reason());
            }, resources);
        }

        void request() {
            if (!transfers.accept(template.toBuilder().setDataTransferId(++id).build()))
                throw new AssertionError("Request rejected");
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            transfers.close();
            dispatch.close();
            owner.close();
            resources.close();
            content.representation().close();
            new com.elfmcys.ysm.testutil.DerivedStorageTestScope().afterEach(null);
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    @State(Scope.Thread)
    public static class Pending extends Fixture {
        @Param({"1", "128"})
        public int requests;

        @Setup(Level.Trial)
        public void pending() {
            for (int index = 0; index < requests; index++)
                request();
            resources.tick();
            if (reads.tasks.size() != 1 || dispatch.queued(owner) != 0)
                throw new AssertionError("Pending fixture is not one shared read");
        }
    }

    @State(Scope.Thread)
    public static class Ready extends Fixture {
        @Setup(Level.Invocation)
        public void prepare() {
            request();
            resources.tick();
            reads.tasks.removeFirst().run();
            resources.tick();
            if (dispatch.queued(owner) != 0)
                throw new AssertionError("Ready source was adopted before its owner tick");
        }

        @TearDown(Level.Invocation)
        public void release() {
            if (dispatch.queued(owner) != 1)
                throw new AssertionError("Ready source did not enter dispatch in one owner tick");
            transfers.cancel(id);
            dispatch.dispatchOnce();
            transfers.tick();
        }
    }

    @State(Scope.Thread)
    public static class Churn extends Fixture {
        @TearDown(Level.Invocation)
        public void release() {
            resources.tick();
            if (!reads.tasks.isEmpty() || dispatch.queued(owner) != 0)
                throw new AssertionError("Cancelled queued use started a read or packet");
        }
    }

    @State(Scope.Thread)
    public static class Closing extends Fixture {
        @Setup(Level.Invocation)
        public void prepare() {
            transfers.close();
            transfers = transferOwner();
            id = 0;
            for (int index = 0; index < 128; index++)
                request();
        }

        @TearDown(Level.Invocation)
        public void release() {
            resources.tick();
            if (!reads.tasks.isEmpty() || dispatch.queued(owner) != 0)
                throw new AssertionError("Closed owner started a read or packet");
        }
    }

    @Benchmark
    public void pendingTick(Pending state) {
        state.transfers.tick();
    }

    @Benchmark
    public void readyToEnqueue(Ready state) {
        state.transfers.tick();
    }

    @Benchmark
    public void churn(Churn state) {
        state.request();
        state.transfers.cancel(state.id);
    }

    @Benchmark
    public void closePending(Closing state) {
        state.transfers.close();
    }

    private static final class ControlledReads extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean closed;

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        @Override
        public void shutdown() {
            closed = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            closed = true;
            var abandoned = List.copyOf(tasks);
            tasks.clear();
            return abandoned;
        }

        @Override
        public boolean isShutdown() {
            return closed;
        }

        @Override
        public boolean isTerminated() {
            return closed && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    private static final class Port implements TransportPort {
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
            return SendResult.SUCCESS;
        }
    }
}
