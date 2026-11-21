package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;

import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.*;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.render.ModelMolangBenchmarkInput;
import com.elfmcys.ysm.model.storage.*;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.NativeLibUtil;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.gson.Gson;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures source preparation through complete encoding, with no cache or disk work in the timed path. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
@Fork(value = 1, jvmArgsAppend = "-Xmx3G")
public class MolangConvergenceCompileBenchmark {
    @State(Scope.Thread)
    public static class Workload {
        @Param({"default", "external-FULL"})
        public String input;
        @Param({"false", "true"})
        public boolean divZeroFallback;
        private final List<Input> inputs = new ArrayList<>();
        private final List<Result> results = new ArrayList<>();
        private final List<List<UnitTiming>> samples = new ArrayList<>();
        private final List<Map<String, String>> unavailable = new ArrayList<>();
        private ExpressionHost host;
        private RenderTargetExpressionModuleFactory factory;
        private final ExpressionEngineMetrics metrics = new ExpressionEngineMetrics();
        private Method compileCold;
        private HostLinkage productionLinkage;
        private final MolangEventCatalog eventCatalog = MolangEventCatalog.production();
        private final JaninoJavaCompilerBackend backend = new JaninoJavaCompilerBackend();
        private Path root;
        private AtomicSharedCache cache;
        private final List<ModelMolangBenchmarkInput> external = new ArrayList<>();

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeLibUtil.load();
            host = ExpressionHost.create(HostAvailability.none());
            productionLinkage = HostLinkage.production(host);
            root = Files.createTempDirectory("ysm-molang-convergence-").toAbsolutePath();
            cache = new AtomicSharedCache(root);
            factory = new RenderTargetExpressionModuleFactory(new ExpressionArtifactStore(root, cache),
                new AnimationInputStore(root, cache), host, metrics);
            // Invoke the production cold branch without scheduling optional persistence or reading a cache.
            compileCold = RenderTargetExpressionModuleFactory.class.getDeclaredMethod("compileCold",
                java.util.function.BooleanSupplier.class, ExpressionArtifactLookup.class, String.class,
                ExpressionSet.class, ModelDataProto.class, ModelProgramInventory.Prepared.class,
                ExpressionBinding.class);
            compileCold.setAccessible(true);
            if (input.equals("default")) {
                var builtin =
                    Path.of(Objects.requireNonNull(Workload.class.getResource("/assets/ysm/builtin/default")).toURI());
                try (var directory = new Directory(builtin)) {
                    var memory = ModelParser.parseBuiltinDefaultMemory(directory);
                    var content = ManagedContainer.openResidentDefault(memory.bytes(),
                        new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
                    try (var representation = content.representation()) {
                        var strings = content.view().getCommon().readStringData(() -> false, content.chunks());
                        var settings = content.view().getMetadata().getSettings();
                        for (var target : content.view().getRenderTargets()) {
                            var definition = target.readDefinition(() -> false, content.chunks());
                            var sets =
                                target.kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER
                                    ? ExpressionSet.values() : new ExpressionSet[] {ExpressionSet.MAIN};
                            for (var set : sets)
                                inputs.add(new Input(target.id(), set, set.select(definition), strings, settings,
                                    representation.containerId(), ModelResourcePurpose.FULL,
                                    factory.expressionBinding(true, net.minecraft.world.entity.LivingEntity.class),
                                    null));
                        }
                    }
                }
            } else {
                if (!input.equals("external-FULL"))
                    throw new IllegalArgumentException("Unknown FULL input: " + input);
                String purpose = "FULL";
                var path = Path.of(Objects.requireNonNull(System.getenv("YSM_BENCHMARK_MODEL"), "YSM_BENCHMARK_MODEL"));
                var content = ManagedContainer.openDirect(path,
                    new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("benchmark-inventory")));
                try (var representation = content.representation()) {
                    for (var target : content.view().getRenderTargets()) {
                        var sets = target.kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER
                            ? ExpressionSet.values() : new ExpressionSet[] {ExpressionSet.MAIN};
                        for (var set : sets) {
                            final ModelMolangBenchmarkInput model;
                            try {
                                model = new ModelMolangBenchmarkInput(path, purpose, target.id(), set);
                            } catch (ModelMolangBenchmarkInput.UnavailableDefaultSource failure) {
                                unavailable.add(Map.of("target", target.id(), "set", set.id(), "purpose", purpose,
                                    "reason", failure.getMessage()));
                                continue;
                            }
                            external.add(model);
                            inputs.add(new Input(target.id(), set, null, model.functions(), model.settings(),
                                model.containerId(), ModelResourcePurpose.valueOf(purpose), model.binding(factory),
                                model));
                        }
                    }
                }
            }
        }

        long compile() throws Exception {
            results.clear();
            long bytes = 0;
            for (var input : inputs) {
                long started = System.nanoTime();
                var definition = input.external() == null ? input.definition() : input.external().projectedDefinition();
                var prepared = ModelProgramInventory.prepare(input.target(), input.set(), definition, input.strings(),
                    input.settings(), eventCatalog);
                var lookup =
                    ExpressionArtifactLookup.create(input.container(), input.target(), input.set(), input.binding(),
                        prepared.sourceFingerprint(), ProtoUtil.serializeToArray(input.settings()), host, eventCatalog,
                        productionLinkage, new CompilerOptions(divZeroFallback), backend.descriptor(), input.purpose());
                long preparedAt = System.nanoTime();
                ExpressionArtifactStore.Produced produced;
                try {
                    produced = (ExpressionArtifactStore.Produced)compileCold.invoke(factory,
                        (java.util.function.BooleanSupplier)() -> false, lookup, input.target(), input.set(),
                        definition, prepared, input.binding());
                } catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof Exception cause)
                        throw cause;
                    throw (Error)failure.getCause();
                }
                long compiledAt = System.nanoTime();
                var encoded = ArtifactCodecs.v1().encode(produced.artifact(), ExpressionIdentityHash.SHA_256);
                var copy = encoded.toByteArray();
                long encodedAt = System.nanoTime();
                bytes += encoded.size();
                results.add(new Result(input.target() + "/" + input.set().id() + "/" + input.purpose(), produced, copy,
                    prepared.locations().size(),
                    new UnitTiming(input.target() + "/" + input.set().id() + "/" + input.purpose(),
                        preparedAt - started, compiledAt - preparedAt, encodedAt - compiledAt)));
            }
            return bytes;
        }

        @TearDown(Level.Invocation)
        public void verify() {
            if (results.size() != inputs.size())
                throw new IllegalStateException("incomplete compile matrix");
            for (var result : results)
                if (!result.produced().artifact().report().replacements().isEmpty())
                    throw new IllegalStateException("unexpected program replacement: " + result.unit());
            if (cache.derivedWork().snapshot().jobs() != 0)
                throw new IllegalStateException("measurement scheduled optional persistence");
            samples.add(results.stream().map(Result::timing).toList());
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            System.out.println("COMPILE_UNIT_SAMPLES " + new Gson().toJson(samples));
            for (var model : external)
                model.close();
            if (cache != null)
                cache.derivedWork().close();
            if (root != null)
                Files.delete(root);
        }
    }

    record Input(String target, ExpressionSet set, ModelDataProto definition, StringDataProto strings,
        SettingsProto settings, Hash256 container, ModelResourcePurpose purpose, ExpressionBinding binding,
        ModelMolangBenchmarkInput external) {
    }

    record UnitTiming(String unit, long sourceNanos, long compileNanos, long encodeNanos) {
    }

    record Result(String unit, ExpressionArtifactStore.Produced produced, byte[] encoded, int sources,
        UnitTiming timing) {
    }

    @Benchmark
    public long coldCompile(Workload state) throws Exception {
        return state.compile();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4)
            throw new IllegalArgumentException(
                "expected input divZeroFallback outputDirectory [previousArtifactDirectory]");
        var state = new Workload();
        state.input = args[0];
        state.divZeroFallback = Boolean.parseBoolean(args[1]);
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        try {
            state.setup();
            long started = System.nanoTime();
            long total = state.compile();
            long nanos = System.nanoTime() - started;
            state.verify();
            var units = new ArrayList<Map<String, Object>>();
            var previousRejected = new ArrayList<String>();
            for (var result : state.results) {
                var artifact = result.produced().artifact();
                Path directory = output.resolve(result.unit());
                Files.createDirectories(directory);
                Files.write(directory.resolve("artifact.bin"), result.encoded());
                for (var entry : artifact.classes().entrySet())
                    Files.write(directory.resolve(entry.getKey() + ".class"), entry.getValue().toByteArray());
                var expected = result.produced().identity().expected();
                if (args.length == 4) {
                    var previous = dev.semaweave.program.ReadOnlyBytes
                        .copyOf(Files.readAllBytes(Path.of(args[3]).resolve(result.unit()).resolve("artifact.bin")));
                    try {
                        // Match the local-store identity gate before decoding a previous report layout.
                        ArtifactCodecs.v1().decodeTrustedLocal(previous, expected);
                        throw new IllegalStateException("previous artifact was accepted: " + result.unit());
                    } catch (ArtifactIdentityMismatchException rejected) {
                        previousRejected.add(result.unit());
                    }
                }
                long loadStarted = System.nanoTime();
                try (var resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected,
                    state.host.registry(), state.host.registry().visibilityRoot(), ExpressionIdentityHash.SHA_256)) {
                    long load = System.nanoTime() - loadStarted;
                    loadStarted = System.nanoTime();
                    var restored = ArtifactCodecs.v1().decodeAndVerify(
                        dev.semaweave.program.ReadOnlyBytes.copyOf(result.encoded()), expected,
                        ExpressionIdentityHash.SHA_256);
                    try (var warm =
                        ModuleLoaders.<MolangContext>standard().load(restored, expected, state.host.registry(),
                            state.host.registry().visibilityRoot(), ExpressionIdentityHash.SHA_256)) {
                        units.add(Map.of("unit", result.unit(), "sources", result.sources(), "programs",
                            artifact.catalog().size(), "classBytes",
                            artifact.classes().values().stream().mapToLong(dev.semaweave.program.ReadOnlyBytes::size)
                                .sum(),
                            "catalogBytes", artifact.catalog().encoded().size(), "constantBytes",
                            artifact.constants().encoded().size(), "artifactBytes", result.encoded().length,
                            "coldLoadNanos", load, "warmDecodeLoadNanos", System.nanoTime() - loadStarted, "report",
                            artifact.report().metrics()));
                    }
                }
            }
            Files.writeString(output.resolve("summary.json"),
                new Gson().toJson(Map.of("input", state.input, "divZeroFallback", state.divZeroFallback,
                    "artifactBytes", total, "compileAndEncodeNanos", nanos, "units", units, "unavailable",
                    state.unavailable, "previousRejected", previousRejected, "unitTimings",
                    state.results.stream().map(Result::timing).toList(), "stages", state.metrics.snapshot())));
            System.out.println("CONVERGENCE_COMPILE_OK " + state.input + " " + total);
        } finally {
            state.close();
        }
    }
}
