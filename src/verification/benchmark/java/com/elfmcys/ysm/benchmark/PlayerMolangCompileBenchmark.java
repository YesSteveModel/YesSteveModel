package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.render.ModelMolangBenchmarkInput;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.NativeLibUtil;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.compilation.EngineMetrics;
import org.openjdk.jmh.annotations.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(value = 1, jvmArgsAppend = "-Xmx3G")
@Threads(1)
public class PlayerMolangCompileBenchmark {
    static final Queue<PhaseSample> PHASES = new ConcurrentLinkedQueue<>();

    record PhaseSample(double semantic, double source, double backend, int programs, long typedNodes, long sourceBytes,
        int replacements) {
    }

    record Animation(String file, AnimationProto value) {
    }

    @State(Scope.Thread)
    public static class Workload {
        @Param({"full"})
        public String selection;
        @Param({"builtin"})
        public String input = "builtin";
        @Param({"FULL"})
        public String purpose = "FULL";
        private ModelMolangBenchmarkInput modelInput;
        private Path root;
        private Path cache;
        private ExpressionHost host;
        private ModelDataProto definition;
        private StringDataProto strings;
        private SettingsProto settings;
        private byte[] config;
        private Hash256 containerId;
        private ExpressionEngineMetrics metrics;
        private RenderTargetExpressionModuleFactory factory;
        private RenderTargetExpressionModule module;
        private int expectedPrograms = -1;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeLibUtil.load();
            host = ExpressionHost.create(HostAvailability.none());
            if ("model".equals(input)) {
                if (!"full".equals(selection))
                    throw new IllegalArgumentException("model input requires selection=full");
                modelInput = new ModelMolangBenchmarkInput(
                    Path.of(Objects.requireNonNull(System.getenv("YSM_BENCHMARK_MODEL"), "YSM_BENCHMARK_MODEL")),
                    purpose);
                root = Files.createTempDirectory("ysm-model-molang-jmh-").toAbsolutePath().normalize();
                cache = root.resolve("cache");
                return;
            }
            if (!"builtin".equals(input) || !"FULL".equals(purpose))
                throw new IllegalArgumentException("builtin input supports FULL MAIN only");
            var builtin =
                Path.of(Objects.requireNonNull(Workload.class.getResource("/assets/ysm/builtin/default")).toURI());
            try (var directory = new Directory(builtin); var memory = ModelParser.parseBuiltinDefaultMemory(directory);
                var bytes = memory.bytes()) {
                var handle = ManagedContainer.openResidentDefault(bytes,
                    new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
                try {
                    var view = handle.view();
                    var target =
                        view.getRenderTargets().stream().filter(t -> t.id().equals("player")).findFirst().orElseThrow();
                    definition = select(ExpressionSet.MAIN.select(target.readDefinition(() -> false, handle.chunks())),
                        selection);
                    strings = view.getCommon().readStringData(() -> false, handle.chunks());
                    settings = view.getMetadata().getSettings();
                    config = ProtoUtil.serializeToArray(settings);
                    containerId = handle.representation().containerId();
                } finally {
                    handle.representation().close();
                }
            }
            root = Files.createTempDirectory("ysm-player-molang-jmh-").toAbsolutePath().normalize();
            cache = root.resolve("cache");
        }

        @Setup(Level.Invocation)
        public void coldCache() throws Exception {
            deleteCache();
            Files.createDirectories(cache);
            try (var files = Files.list(cache)) {
                if (files.findAny().isPresent())
                    throw new IllegalStateException("cache is not empty");
            }
            // A fresh store and factory also prevent in-memory artifact and backend linkage reuse.
            metrics = new ExpressionEngineMetrics();
            factory = new RenderTargetExpressionModuleFactory(
                new ExpressionArtifactStore(cache, new AtomicSharedCache(cache)), host, metrics);
        }

        @TearDown(Level.Invocation)
        public void verifyCold() throws Exception {
            if (module == null)
                throw new IllegalStateException("compilation did not publish a module");
            try {
                var snapshot = metrics.snapshot();
                if (module.source() != ExpressionArtifactStore.Source.COLD
                    || snapshot.stageCalls(EngineMetrics.Stage.SEMANTIC_ANALYSIS) != 1
                    || snapshot.stageCalls(EngineMetrics.Stage.SOURCE_GENERATION) != 1
                    || snapshot.stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION) != 1
                    || snapshot.portableProgramDecodes() != 1) {
                    throw new IllegalStateException("expected one complete cold compilation");
                }
                int programs = module.programs().size();
                if (expectedPrograms < 0)
                    expectedPrograms = programs;
                if (programs != expectedPrograms)
                    throw new IllegalStateException("inventory changed");
                var nanos = snapshot.stageNanos();
                var report = module.compilationReport();
                PHASES.add(new PhaseSample(nanos[EngineMetrics.Stage.SEMANTIC_ANALYSIS.ordinal()] / 1e6,
                    nanos[EngineMetrics.Stage.SOURCE_GENERATION.ordinal()] / 1e6,
                    nanos[EngineMetrics.Stage.BACKEND_COMPILATION.ordinal()] / 1e6, programs,
                    report.metrics().typedNodes(), report.metrics().generatedSourceBytes(),
                    report.replacements().size()));
            } finally {
                module.close();
                module = null;
            }
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (modelInput != null)
                modelInput.close();
            deleteCache();
            if (root != null)
                Files.delete(root);
        }

        private void deleteCache() throws Exception {
            if (cache == null || !Files.exists(cache))
                return;
            if (!cache.getParent().equals(root) || !cache.getFileName().toString().equals("cache")) {
                throw new IllegalStateException("cache escaped benchmark root");
            }
            try (var paths = Files.walk(cache)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    static ModelDataProto select(ModelDataProto source, String selection) {
        var animations = new ArrayList<Animation>();
        source.animationFiles().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
            if (file.getKey().equals("fp_arm"))
                throw new IllegalArgumentException("fp_arm in MAIN");
            for (var animation : file.getValue().animations())
                animations.add(new Animation(file.getKey(), animation));
        });
        var keep = new BitSet(animations.size());
        if (selection.equals("full"))
            keep.set(0, animations.size());
        else if (!selection.equals("empty")) {
            String[] parts = selection.split(":", -1);
            if (parts.length != 2 || !(parts[0].equals("keep") || parts[0].equals("remove"))) {
                throw new IllegalArgumentException("expected full, empty, keep:start-end or remove:start-end");
            }
            if (parts[0].equals("remove"))
                keep.set(0, animations.size());
            for (String range : parts[1].split("\\+")) {
                String[] bounds = range.split("-", -1);
                if (bounds.length != 2)
                    throw new IllegalArgumentException("invalid range: " + range);
                int start = Integer.parseInt(bounds[0]), end = Integer.parseInt(bounds[1]);
                if (start < 0 || end <= start || end > animations.size())
                    throw new IllegalArgumentException("range out of bounds");
                keep.set(start, end, parts[0].equals("keep"));
            }
        }
        var result = source.toBuilder().clearAnimationFiles();
        source.animationFiles().forEach((file, content) -> {
            var selected = content.toBuilder().clearAnimations();
            for (int i = 0; i < animations.size(); i++) {
                var animation = animations.get(i);
                if (keep.get(i) && animation.file().equals(file))
                    selected.addAnimations(animation.value());
            }
            result.putAnimationFiles(file, selected.build());
        });
        return result.build();
    }

    @Benchmark
    public int coldCompile(Workload state) throws Exception {
        state.module = state.modelInput != null ? state.modelInput.compile(state.factory)
            : state.factory.createSet(() -> false, state.containerId, "player", ExpressionSet.MAIN,
                state.host.expressionBinding(), state.definition, state.strings, state.settings, state.config, false);
        return state.module.programs().size();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("output inventory TSV path");
        var state = new Workload();
        state.selection = "full";
        try {
            state.setup();
            var output = new StringBuilder("index\tfile\tanimation\n");
            int[] index = {0};
            state.definition.animationFiles().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
                for (var animation : file.getValue().animations()) {
                    output.append(index[0]++).append('\t').append(file.getKey()).append('\t').append(animation.name())
                        .append('\n');
                }
            });
            Files.writeString(Path.of(args[0]), output);
        } finally {
            state.close();
        }
    }
}
