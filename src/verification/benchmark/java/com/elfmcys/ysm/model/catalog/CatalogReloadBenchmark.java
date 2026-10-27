package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CatalogReloadBenchmark {
    @State(Scope.Thread)
    public static class Input {
        Path temporary;
        Path file;
        ManagedContainer previous;
        CatalogLocation location;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            temporary = Files.createTempDirectory("ysm-catalog-benchmark-");
            var manifest = CatalogReloadBenchmark.class.getResource("/assets/ysm/builtin/default/ysm.json");
            var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
            try (var vfs = new Directory(source)) {
                file = ModelParser.parseBuiltinDefault(vfs, temporary.resolve("compiled"));
            }
            location = new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default"));
            previous = ManagedContainer.openDirect(file, location);
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            previous.representation().close();
            var root = temporary.toAbsolutePath().normalize();
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) {
                throw new IllegalStateException("Benchmark cleanup escaped the temporary directory");
            }
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Benchmark
    public ModelFileView freshMetadata(Input input) throws Exception {
        var content = ManagedContainer.openDirect(input.file, input.location);
        try {
            return content.view();
        } finally {
            content.representation().close();
        }
    }

    @Benchmark
    public ModelFileView reusedMetadata(Input input) throws Exception {
        var content = ManagedContainer.openDirect(input.file, input.location, input.previous);
        try {
            if (content.view() != input.previous.view()) {
                throw new IllegalStateException("Metadata reuse did not preserve the parsed graph");
            }
            return content.view();
        } finally {
            content.representation().close();
        }
    }

    /** Runs with CatalogReloadTimingAgent; generation, I/O and tick are observed separately. */
    public static void main(String[] arguments) throws Exception {
        var count = Integer.parseInt(arguments[0]);
        var input = new Input();
        input.setup();
        try {
            var root = Files.createDirectories(input.temporary.resolve("sources"));
            populate(input.previous, root, count);
            var cache = input.temporary.resolve("cache");
            var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
                new ConvertedSourceIndexStore(cache, "catalog-benchmark"), new ConvertedObjectStore(cache));
            var reconciler = new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false)),
                resolver, Set.of(input.previous.modelId()), input.previous, CatalogIndexSnapshot.empty());
            var coordinator = new AtomicReference<Thread>();
            try (var catalog = new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins(), runnable -> {
                var thread = new Thread(runnable);
                coordinator.set(thread);
                return thread;
            })) {
                catalog.subscribe(transition -> {
                    if (!transition.previous().byModelId().isEmpty()
                        && transition.current().byModelId().size() != count + 1) {
                        throw new IllegalStateException("Incomplete catalog observer input");
                    }
                });
                for (var phase : new String[] {"initial", "unchanged"}) {
                    CatalogReloadProbe.reset();
                    if (phase.equals("initial")) {
                        catalog.activate();
                    }
                    var completion = phase.equals("initial") ? null : catalog.reload();
                    coordinator.get().join(TimeUnit.SECONDS.toMillis(30));
                    if (coordinator.get().isAlive() || !catalog.busy() || completion != null && completion.isDone()) {
                        throw new IllegalStateException("Background scan requires main-thread progress");
                    }
                    var before = System.nanoTime();
                    catalog.tick();
                    var tickNanos = System.nanoTime() - before;
                    if (catalog.busy() || completion != null && completion.join().status() != ReloadStatus.COMMITTED
                        || catalog.current().byModelId().size() != count + 1
                        || phase.equals("initial") && CatalogReloadProbe.calls(0) == 0 || phase.equals("unchanged")
                            && (CatalogReloadProbe.calls(0) != 0 || CatalogReloadProbe.calls(3) != 0)) {
                        throw new IllegalStateException("Catalog observation or instrumentation failed");
                    }
                    System.out.println("CATALOG_PHASE count=" + count + " phase=" + phase + " tickNs=" + tickNanos + " "
                        + CatalogReloadProbe.report());
                }
            }
        } finally {
            input.close();
        }
    }

    private static void populate(ManagedContainer source, Path root, int count) throws Exception {
        var blank = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        try (var preview = PreviewStore.decode(blank)) {
            for (var index = 0; index < count; index++) {
                var id = new byte[Hash256.SIZE];
                ByteBuffer.wrap(id).putInt(index + 1);
                var properties = PropertiesProto.newBuilder(source.view().getManifest().info().properties())
                    .setModelId(ByteBuffer.wrap(id)).build();
                var info = InfoProto.newBuilder(source.view().getManifest().info()).setProperties(properties)
                    .setThumbnailSource(PreviewSource.GENERATED).build();
                var manifest = ManifestProto.newBuilder(source.view().getManifest()).setInfo(info).build();
                try (var writer = new ModelFileWriter(); var image = preview.open();
                    var channel = FileChannel.open(root.resolve("model-" + index + ".mxc"),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writer.setManifest(manifest);
                    for (var chunk : source.view().getFileView().getAssetView().getChunkTable().values().stream()
                        .filter(value -> !value.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                            && !value.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME)
                            && !value.type().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME))
                        .sorted(Comparator.comparingInt(AssetContainerView.ChunkInfo::offset)).toList()) {
                        try (var bytes = source.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                            writer.addStoredChunk(chunk, bytes);
                        }
                    }
                    writer.setThumbnail(image);
                    writer.write(channel);
                }
            }
        }
    }
}
