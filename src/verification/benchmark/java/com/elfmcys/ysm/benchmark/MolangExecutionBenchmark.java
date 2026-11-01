package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.api.molang.PreparedEffect;
import com.elfmcys.ysm.expression.host.*;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import org.openjdk.jmh.annotations.*;

import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class MolangExecutionBenchmark {
    private static final ExpressionIdentityHash HASH = ExpressionIdentityHash.SHA_256;
    private static final PortableProgramCodec CODEC = PortableProgramCodecs.v1();
    private static final EffectValidator VALIDATOR = (command, scope) -> {
    };
    private static final EffectSink.PreparedCommit EMPTY_COMMIT = () -> {
    };
    private static final EffectSink EMPTY_SINK = (commands, provenance) -> EMPTY_COMMIT;
    private static final InvocationProvenance SOURCE = new InvocationProvenance("benchmark", "round", -1, 1, 1);
    private static final float[][] VALUES =
            {{3.25f, +0f, -0f, -2.5f}, {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN},
                    {3.25f, Float.NaN, -0f, Float.POSITIVE_INFINITY}};
    private static final float[][] EXPECTED = {{3.25f, +0f, -0f, -2.5f}, {7, 7, 7, 7}, {3.25f, 7, -0f, 7}};

    public static float primitive(MolangContext<?> context) {
        return ((Inputs) context.entityView()).next();
    }

    public static Float boxed(MolangContext<?> context) {
        float value = ((Inputs) context.entityView()).next();
        return Float.isFinite(value) ? value : null;
    }

    public static float action(MolangContext<?> context, float value) {
        ((Inputs) context.entityView()).actions++;
        return value;
    }

    public static float identity(MolangContext<?> context, float value) {
        return pureIdentity(value);
    }

    public static float pureIdentity(float value) {
        return value == 99 ? Float.NaN : value;
    }

    public static float encoded(MolangContext<?> context) {
        float value = ((Inputs) context.entityView()).next();
        return Float.isFinite(value) ? value : 99;
    }

    public static float rejected(MolangContext<?> context, float value) {
        throw new AssertionError("subject gate bypassed");
    }

    public static Object subject(MolangContext<?> context) {
        return context.entityView();
    }

    public static void validate(MolangContext<?> context) {
        context.verifyEligible();
    }

    public static final class Inputs implements InvocationInputs<net.minecraft.world.entity.Entity> {
        @Override
        public net.minecraft.world.entity.Entity entity() {
            return null;
        }

        float[] values;
        int cursor;
        int reads;
        int actions;

        float next() {
            reads++;
            return values[cursor++ & 3];
        }

        @Override
        public void prevalidateEffect(PreparedEffect command, MolangContext<?> scope) {
        }
    }

    @State(Scope.Thread)
    public static class Workload {
        @Param({"finite", "missing", "mixed"})
        public String distribution;
        @Param({"1", "16", "256"})
        public int expressions;
        private final Inputs inputs = new Inputs();
        private RegistrySnapshot<MolangContext> registry;
        private CompiledArtifact artifact;
        private CompiledModuleResource<MolangContext> resource;
        private CompiledModuleInstance<MolangContext> instance;
        private SubjectBinding subject;
        private TransientExecutionAccess<MolangContext> transientAccess;
        private TransientProgramLease<MolangContext> transientLease;
        private SubjectBinding transientSubject;
        private CompiledRoamingState roaming;
        private MolangContext context;
        private final dev.semaweave.compilation.ProgramRef[] ids = new dev.semaweave.compilation.ProgramRef[7];

        @Setup(Level.Trial)
        public void setup() throws Throwable {
            inputs.values = VALUES[switch (distribution) {
                case "finite" -> 0;
                case "missing" -> 1;
                default -> 2;
            }];
            var builder =
                    ExpressionRegistry.builder(ContextTypes.create("ysm:benchmark", 1, MolangContext.class, HASH), HASH);
            var primitive = binding("primitive", float.class, MolangContext.class);
            var boxed = binding("boxed", Float.class, MolangContext.class);
            var action = binding("action", float.class, MolangContext.class, float.class);
            var encoded = binding("encoded", float.class, MolangContext.class);
            builder.registerFunction(function("encoded", encoded, List.of()));
            var identity = binding("identity", float.class, MolangContext.class, float.class);
            var rejected = binding("rejected", float.class, MolangContext.class, float.class);
            builder.registerFunction(function("identity", identity, List.of(BuiltinType.F32)));
            builder.registerFunction(function("rejected", rejected, List.of(BuiltinType.F32)));
            builder.registerFunction(function("primitive", primitive, List.of()));
            builder.registerFunction(function("boxed", boxed, List.of()));
            builder.registerFunction(function("action", action, List.of(BuiltinType.F32)));
            registry = builder.freeze();
            var plan = BindingPlan.builder().invocationValidator(binding("validate", void.class, MolangContext.class))
                    .guard(primitive, primitive, DeclarationEffect.CONTEXT, null)
                    .guard(boxed, boxed, DeclarationEffect.CONTEXT, null)
                    .guard(action, action, DeclarationEffect.ACTION, null)
                    .guard(encoded, encoded, DeclarationEffect.CONTEXT, null)
                    .guard(identity, identity, DeclarationEffect.CONTEXT, null)
                    .contextFreeCall(identity, binding("pureIdentity", float.class, float.class))
                    .subjectAccessor(binding("subject", Object.class, MolangContext.class))
                    .guard(rejected, rejected, DeclarationEffect.CONTEXT, String.class).build();
            var unit = CompilationUnit.builder(CompilationPolicy.DEFAULT, CODEC, HASH);
            add(unit, 0, coalesce(call("primitive")), EntryPermissions.CONTEXT);
            add(unit, 1, coalesce(call("boxed")), EntryPermissions.CONTEXT);
            add(unit, 2,
                    new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, ProgramNode.F32.of(1), ProgramNode.F32.of(2)),
                    EntryPermissions.CONTEXT);
            add(unit, 3, coalesce(call("action", call("primitive"))), EntryPermissions.CONTEXT);
            add(unit, 4,
                    coalesce(new ProgramNode.Symbol(
                            new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.USER_FUNCTION, "bench:helper"),
                            ProgramNode.AccessForm.INVOKE, List.of())),
                    EntryPermissions.CONTEXT);
            add(unit, 6, coalesce(call("rejected", call("primitive"))), EntryPermissions.CONTEXT);
            add(unit, 7, coalesce(call("identity", call("encoded"))), EntryPermissions.CONTEXT);
            unit.addProgram(
                    new ProgramSource(key(5), CODEC.encode(PortablePrograms.ofRoots(List.of(coalesce(call("primitive"))))),
                            new UserFunctionEntrySignature(new SymbolKey("bench:helper"),
                                    new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
                            Optional.empty(), new DiagnosticLocation("helper")));
            var input = unit.build();
            var host =
                    new HostAbi("ysm:benchmark", "1", Fingerprint.compute(HASH, ReadOnlyBytes.copyOf(new byte[]{1})));
            var backend = new JaninoJavaCompilerBackend();
            artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, input, host,
                    registry, CompilerOptions.DEFAULT, plan, HASH);
            var expected = ExpectedArtifactIdentity.create(input, host, registry, CompilerOptions.DEFAULT, plan,
                    backend.descriptor(), HASH);
            resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, registry,
                    registry.visibilityRoot(), HASH);
            var linkage = RegistryRuntimeLinkage.<MolangContext>create(ProviderInstanceTable.create(registry, Map.of()),
                    RuntimeSymbolTable.empty());
            instance = resource.newInstance(new RuntimeLinkage<>() {
                public ContextType<MolangContext> contextType() {
                    return registry.contextType();
                }

                public RegistryRuntimeLinkage<MolangContext> registry() {
                    return linkage;
                }

                public BakedConstantTable constants() {
                    return artifact.constants();
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }

                public ResourceLease<MolangContext> retainResource() {
                    return resource.retain();
                }
            });
            subject = instance.bindSubject(inputs);
            roaming = resource.newRoamingState(RoamingRole.CONSUMER);
            context = newRound();
            for (int index = 0; index < ids.length; index++)
                ids[index] = resource.bindProgram(key(index < 5 ? index : index + 1), ProgramShape.F32);
            var none = TransientDynamicAccess.none(HASH);
            var slots = new TreeSet<LocalName>(Comparator.comparing(LocalName::name));
            var layout = resource.createTransientLayout(new TransientAccessRequest(slots, slots, new TreeSet<>(), none.descriptor(), CompilationPolicy.DEFAULT));
            transientLease = TransientCompilers.<MolangContext>standard(backend).compile(TaskContext.NONE, CODEC.encode(PortablePrograms.ofRoots(List.of(coalesce(call("primitive"))))), ProgramShape.F32, layout, TransientPurpose.WATCH, HASH);
            transientAccess = instance.openTransientAccess(layout, none);
            transientSubject = transientAccess.bindSubject(inputs);
            verifyTruth();
        }

        private MolangContext newRound() {
            return newRound(false);
        }

        private MolangContext newRound(boolean logical) {
            var value = logical
                    ? MolangContext.logical(inputs, inputs, MolangContext.LogicalPhase.UPDATE, VALIDATOR, roaming, SOURCE,
                    () -> false, () -> true, EffectBatch.DEFAULT_MAX_COMMANDS)
                    : MolangContext.observation(inputs, inputs, VALIDATOR, roaming, SOURCE, () -> false, () -> true);
            value.configureLimits(ExecutionLimits.DEFAULT);
            return value;
        }

        float execute(int index, MolangContext round) throws Throwable {
            var id = ids[index];
            round.bindExpression("benchmark-expression", id.sourceId());
            try {
                return instance.executeFloat(id, round, subject, round);
            } finally {
                round.releaseExpression();
            }
        }

        private void verifyTruth() throws Throwable {
            var selected = inputs.values;
            for (int distribution = 0; distribution < VALUES.length; distribution++) {
                inputs.values = VALUES[distribution];
                for (int program : new int[]{0, 1, 4, 6}) {
                    inputs.cursor = 0;
                    int before = inputs.reads;
                    for (float expected : EXPECTED[distribution])
                        requireBits(expected, execute(program, context));
                    if (inputs.reads - before != 4)
                        throw new IllegalStateException("provider call count differs");
                }
            }
            inputs.values = selected;
            inputs.cursor = 0;
            requireBits(3, execute(2, context));
            int reads = inputs.reads;
            requireBits(7, execute(5, context));
            requireBits(7, execute(3, context));
            if (reads != inputs.reads || inputs.actions != 0 || context.firstFailure() != null || context.isActive())
                throw new IllegalStateException("denied ACTION evaluated an argument or retained active state");
        }

        @TearDown(Level.Trial)
        public void close() {
            if (transientAccess != null) transientAccess.close();
            if (transientLease != null) transientLease.close();
            if (context != null)
                context.closeRound();
            if (instance != null)
                instance.close();
            if (resource != null)
                resource.close();
        }
    }

    @Benchmark
    public float primitive(Workload state) throws Throwable {
        return state.execute(0, state.context);
    }

    @Benchmark
    public float boxed(Workload state) throws Throwable {
        return state.execute(1, state.context);
    }

    @Benchmark
    public float arithmetic(Workload state) throws Throwable {
        return state.execute(2, state.context);
    }

    @Benchmark
    public float deniedAction(Workload state) throws Throwable {
        return state.execute(3, state.context);
    }

    @Benchmark
    public float internalFunction(Workload state) throws Throwable {
        return state.execute(4, state.context);
    }

    @Benchmark
    public float deniedSubject(Workload state) throws Throwable {
        return state.execute(5, state.context);
    }

    @Benchmark
    public float contextFreeCall(Workload state) throws Throwable {
        return state.execute(6, state.context);
    }

    @Benchmark
    public float transientPrimitive(Workload state) throws Throwable {
        state.context.bindExpression("benchmark-transient", -1);
        try {
            return state.transientLease.program().executeFloat(state.transientAccess, state.context, state.transientSubject, state.context);
        } finally {
            state.context.releaseExpression();
        }
    }

    @Benchmark
    public float consecutiveExpressions(Workload state) throws Throwable {
        float value = 0;
        for (int index = 0; index < state.expressions; index++)
            value += state.execute(0, state.context);
        return value;
    }

    @Benchmark
    public float completeObservation(Workload state) throws Throwable {
        var context = state.newRound();
        try {
            float value = 0;
            for (int index = 0; index < state.expressions; index++)
                value += state.execute(0, context);
            return value;
        } finally {
            context.closeRound();
        }
    }

    @Benchmark
    public float completeLogicalRound(Workload state) throws Throwable {
        var context = state.newRound(true);
        boolean successful = false;
        try {
            float value = 0;
            for (int index = 0; index < state.expressions; index++)
                value += state.execute(0, context);
            successful = true;
            return value;
        } finally {
            try {
                EffectTransaction.finish(context, EMPTY_SINK, successful);
            } finally {
                context.closeRound();
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        for (String distribution : List.of("finite", "missing", "mixed")) {
            var state = new Workload();
            state.distribution = distribution;
            state.expressions = 128;
            try {
                state.setup();
                var benchmark = new MolangExecutionBenchmark();
                state.inputs.cursor = 0;
                for (float expected : EXPECTED[distribution.equals("finite") ? 0 : distribution.equals("missing") ? 1 : 2])
                    requireBits(expected, benchmark.transientPrimitive(state));
                benchmark.consecutiveExpressions(state);
                benchmark.completeObservation(state);
                benchmark.completeLogicalRound(state);
                if (args.length != 0) {
                    for (var entry : state.artifact.classes().entrySet()) {
                        var file = Path.of(args[0], distribution, entry.getKey().replace('.', '/') + ".class");
                        Files.createDirectories(file.getParent());
                        Files.write(file, entry.getValue().toByteArray());
                    }
                }
            } finally {
                state.close();
            }
        }
        System.out.println(
                "Molang execution benchmark truth: PASS (finite/missing/mixed, primitive/boxed/internal, denied ACTION, observation/logical round)");
    }

    private static FunctionDescriptor<MolangContext> function(String name, JavaMemberBinding member,
                                                              List<ValueType> parameters) {
        return new FunctionDescriptor<>(new SymbolKey("bench:" + name), FunctionCallShape.FIXED, parameters.size(),
                parameters.size(), parameters, List.of(), Optional.empty(), BuiltinType.F32, member, EffectSet.empty(),
                "benchmark", 1);
    }

    private static JavaMemberBinding binding(String name, Class<?> result, Class<?>... parameters) {
        return new JavaMemberBinding(MolangExecutionBenchmark.class, name, MethodType.methodType(result, parameters),
                InvocationKind.STATIC, Optional.empty());
    }

    private static ProgramKey key(int value) {
        return new ProgramKey(ReadOnlyBytes.copyOf(new byte[]{(byte) value}));
    }

    private static ProgramNode call(String name, ProgramNode... arguments) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "bench:" + name),
                ProgramNode.AccessForm.INVOKE, List.of(arguments));
    }

    private static ProgramNode coalesce(ProgramNode value) {
        return new ProgramNode.Coalesce(value, ProgramNode.F32.of(7));
    }

    private static void add(CompilationUnit.Builder unit, int index, ProgramNode root, EntryPermissions permissions)
            throws Exception {
        unit.addProgram(new ProgramSource(key(index), CODEC.encode(PortablePrograms.ofRoots(List.of(root))),
                new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(), List.of()),
                Optional.empty(), new DiagnosticLocation("benchmark-" + index), permissions));
    }

    private static void requireBits(float expected, float actual) {
        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual))
            throw new IllegalStateException("benchmark truth differs: expected " + expected + ", actual " + actual);
    }
}
