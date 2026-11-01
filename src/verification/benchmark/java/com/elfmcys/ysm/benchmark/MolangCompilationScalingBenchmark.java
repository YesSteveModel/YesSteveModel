package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import com.elfmcys.ysm.expression.host.ExpressionPolicy;
import com.google.gson.Gson;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.openjdk.jmh.annotations.*;

/** Separates duplicate sources from real contracts and real instance references. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsAppend = "-Xmx2G")
public class MolangCompilationScalingBenchmark {
    private static final ExpressionIdentityHash HASH = ExpressionIdentityHash.SHA_256;

    @State(Scope.Thread)
    public static class Workload {
        @Param({"repeated", "unique", "sparse", "dense"})
        public String scenario;
        @Param({"1", "8", "65"})
        public int size;
        private RegistrySnapshot<Object> registry;
        private List<ProgramSource> sources;
        private ExpressionEngineMetrics metrics;
        private CompiledArtifact artifact;
        private CompilationUnit unit;
        private String compileFailure;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            registry = ExpressionRegistry.builder(ContextTypes.create("benchmark:scaling", 1, Object.class, HASH), HASH,
                ExpressionPolicy.COMPILER_LIMITS).freeze();
            sources = new ArrayList<>();
            if (scenario.equals("repeated") || scenario.equals("unique")) {
                for (int index = 0; index < size; index++) {
                    sources.add(source(index, new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD,
                        ProgramNode.F32.of(scenario.equals("unique") ? index : 1), ProgramNode.F32.of(2))));
                }
            } else if (scenario.equals("sparse") || scenario.equals("dense")) {
                var writes = new ArrayList<ProgramNode>();
                for (int slot = 0; slot < size; slot++)
                    writes.add(new ProgramNode.Assignment(slot(slot), ProgramNode.F32.of(7)));
                writes.add(ProgramNode.F32.of(1));
                sources.add(source(0, new ProgramNode.Block(writes)));
                var reads = new ArrayList<ProgramNode>();
                for (int slot = 0; slot < (scenario.equals("dense") ? size : 1); slot++)
                    reads.add(slot(slot));
                reads.add(ProgramNode.F32.of(3));
                for (int index = 1; index <= 12; index++)
                    sources.add(source(index, new ProgramNode.Block(reads)));
            } else if (scenario.startsWith("scalar")) {
                registry = ScalarCompilationCases.registry();
                for (var root : ScalarCompilationCases.roots(scenario)) {
                    var original = source(sources.size(), root);
                    var signature = scenario.equals("scalarJoin") && sources.size() == 3
                        ? new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32),
                            List.of(), List.of(new LocalName("joined")))
                        : original.signature();
                    sources.add(new ProgramSource(original.key(), original.program(), signature,
                        Optional.of(PortableProgramCodecs.v1()
                            .encode(PortablePrograms.ofRoots(List.of(ProgramNode.F32.of(0))))),
                        original.location(), original.entryPermissions()));
                }
            } else {
                throw new IllegalArgumentException("unknown scenario");
            }
        }

        @Setup(Level.Invocation)
        public void reset() {
            metrics = new ExpressionEngineMetrics();
            artifact = null;
            compileFailure = null;
        }

        @TearDown(Level.Invocation)
        public void verify() {
            var snapshot = metrics.snapshot();
            var nanos = snapshot.stageNanos();
            if (artifact == null) {
                require(scenario.equals("scalarConflicts") && compileFailure != null, "expected conflict failure");
                PlayerMolangCompileBenchmark.PHASES.add(new PlayerMolangCompileBenchmark.PhaseSample(
                    nanos[EngineMetrics.Stage.SEMANTIC_ANALYSIS.ordinal()] / 1e6, 0, 0, sources.size(), -1, -1, -1));
                return;
            }
            var report = artifact.report();
            PlayerMolangCompileBenchmark.PHASES.add(new PlayerMolangCompileBenchmark.PhaseSample(
                nanos[EngineMetrics.Stage.SEMANTIC_ANALYSIS.ordinal()] / 1e6,
                nanos[EngineMetrics.Stage.SOURCE_GENERATION.ordinal()] / 1e6,
                nanos[EngineMetrics.Stage.BACKEND_COMPILATION.ordinal()] / 1e6, sources.size(),
                report.metrics().typedNodes(), report.metrics().generatedSourceBytes(), report.replacements().size()));
            if (scenario.startsWith("scalar")) {
                require(artifact.catalog().size() == sources.size(), "scalar source inventory");
                return;
            }
            int entries = scenario.equals("unique") ? size : scenario.equals("repeated") ? 1 : 2;
            require(artifact.catalog().size() == sources.size(), "source inventory");
            require(artifact.catalog().entries().size() == entries, "complete contracts");
            require(cases(artifact, false) == entries, "actual shard switches");
            require(cases(artifact, true) == (entries + 63) / 64, "actual root shard routing");
            require(bodies(artifact) == entries, "physical entry bodies");
            if (scenario.equals("sparse") || scenario.equals("dense")) {
                long references = size + (scenario.equals("dense") ? size : 1);
                require(
                    metrics.snapshot().analysisWork(EngineMetrics.CompilationKind.PERSISTENT,
                        EngineMetrics.AnalysisCounter.DIAGNOSTIC_INSTANCE_QUERIES) == references,
                    "effective instance references");
            }
        }

        private Map<String, Object> record() {
            var result = new LinkedHashMap<String, Object>();
            result.put("scenario", scenario);
            result.put("size", size);
            result.put("expectedSources", sources.size());
            if (artifact == null) {
                result.put("compileFailure", compileFailure);
                result.put("metrics", metrics.snapshot());
                result.put("inputHex", sources.stream()
                    .map(source -> HexFormat.of().formatHex(source.program().payload().toByteArray())).toList());
                return result;
            }
            result.put("report", artifact.report().metrics());
            result.put("metrics", metrics.snapshot());
            result.put("localRecoveries", artifact.report().localRecoveries().stream().map(Object::toString).toList());
            result.put("replacements", artifact.report().replacements().stream().map(Object::toString).toList());
            result.put("diagnostics", artifact.report().diagnostics().stream().map(Object::toString).toList());
            result.put("inputHex", sources.stream()
                .map(source -> HexFormat.of().formatHex(source.program().payload().toByteArray())).toList());
            result.put("rootFloatCases", cases(artifact, true));
            result.put("shardFloatCases", cases(artifact, false));
            result.put("physicalBodies", bodies(artifact));
            result.put("heapPools", ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .map(pool -> Map.of("pool", pool.getName(), "peakUsedBytes", pool.getPeakUsage().getUsed())).toList());
            return result;
        }
    }

    @Benchmark
    public int coldCompile(Workload state) throws Exception {
        var builder = CompilationUnit.builder(ExpressionPolicy.COMPILATION, PortableProgramCodecs.v1(), HASH);
        for (var source : state.sources)
            builder.addProgram(source);
        state.unit = builder.build();
        try {
            state.artifact = new DefaultExpressionAotCompiler<Object>(new JaninoJavaCompilerBackend(), state.metrics)
                .compile(TaskContext.NONE, state.unit, host(), state.registry, CompilerOptions.DEFAULT,
                    BindingPlan.identity(), HASH);
        } catch (SemanticCompilationException failure) {
            if (!state.scenario.equals("scalarConflicts"))
                throw failure;
            state.compileFailure = failure.toString();
            return 0;
        }
        return state.artifact.catalog().size();
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 2)
            throw new IllegalArgumentException("expected scenario and size");
        var state = new Workload();
        state.scenario = args[0];
        state.size = Integer.parseInt(args[1]);
        state.setup();
        state.reset();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans())
            pool.resetPeakUsage();
        long started = System.nanoTime();
        new MolangCompilationScalingBenchmark().coldCompile(state);
        long coldNanos = System.nanoTime() - started;
        state.verify();
        var result = state.record();
        result.put("coldNanos", coldNanos);
        if (state.artifact == null) {
            System.out.println("SCALING_RESULT " + new Gson().toJson(result));
            return;
        }
        var expected = ExpectedArtifactIdentity.create(state.unit, host(), state.registry, CompilerOptions.DEFAULT,
            BindingPlan.identity(), new JaninoJavaCompilerBackend().descriptor(), HASH);
        var encoded = ArtifactCodecs.v1().encode(state.artifact, HASH);
        started = System.nanoTime();
        var restored = ArtifactCodecs.v1().decodeAndVerify(encoded, expected, HASH);
        try (var resource = ModuleLoaders.<Object>standard().load(restored, expected, state.registry,
            state.registry.visibilityRoot(), HASH)) {
            result.put("warmDecodeLoadNanos", System.nanoTime() - started);
            result.put("payloadBytes", encoded.size());
            if (state.scenario.startsWith("scalar"))
                result.put("oracle",
                    ScalarCompilationCases.execute(resource, restored, state.registry, state.scenario));
        }
        System.out.println("SCALING_RESULT " + new Gson().toJson(result));
    }

    private static HostAbi host() {
        return new HostAbi("benchmark:scaling", "1", Fingerprint.compute(HASH, ReadOnlyBytes.copyOf(new byte[] {1})));
    }

    private static ProgramSource source(int key, ProgramNode root) throws Exception {
        return new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(ByteBuffer.allocate(4).putInt(key).array())),
            PortableProgramCodecs.v1().encode(PortablePrograms.ofRoots(List.of(root))),
            new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
            Optional.empty(), new DiagnosticLocation("scaling-" + key), EntryPermissions.CONTEXT);
    }

    private static ProgramNode.Symbol slot(int slot) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.INSTANCE, "slot" + slot),
            ProgramNode.AccessForm.READ, List.of());
    }

    private static int cases(CompiledArtifact artifact, boolean root) {
        int total = 0;
        for (var entry : artifact.classes().entrySet()) {
            if (entry.getKey().equals(artifact.manifest().rootBinaryName()) != root)
                continue;
            if (!root && !entry.getKey().matches(".*\\.E[0-9]+"))
                continue;
            var node = new ClassNode();
            new ClassReader(entry.getValue().toByteArray()).accept(node, 0);
            for (var method : node.methods) {
                if (!method.name.equals("executeFloat"))
                    continue;
                for (var instruction : method.instructions) {
                    if (instruction instanceof LookupSwitchInsnNode select)
                        total += select.keys.size();
                    if (instruction instanceof TableSwitchInsnNode select)
                        total += (int)select.labels.stream().filter(label -> label != select.dflt).count();
                }
            }
        }
        return total;
    }

    private static long bodies(CompiledArtifact artifact) {
        return artifact.classes().values().stream().map(bytes -> {
            var node = new ClassNode();
            new ClassReader(bytes.toByteArray()).accept(node, 0);
            return node;
        }).flatMap(node -> node.methods.stream()).filter(method -> method.name.matches("p[0-9]+")).count();
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }
}
