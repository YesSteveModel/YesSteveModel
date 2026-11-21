package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.host.*;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Fixed subject, child-context and record workloads with independent result and call-count oracles. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MolangBoundaryBenchmark {
    private static final ExpressionIdentityHash HASH = ExpressionIdentityHash.SHA_256;
    private static final StructType RECORD = new StructType(new SymbolKey("bench:boundary-record"));
    private static final CustomType REFERENCE = new CustomType(new SymbolKey("bench:boundary-reference"));
    private static final InvocationProvenance SOURCE = new InvocationProvenance("benchmark", "boundary", -1, 1, 1);

    public record Value(float number) {
    }

    public record ChildSubject(float number) {
    }

    public static class Views implements InvocationInputs<net.minecraft.world.entity.Entity> {
        private final Counters counters;

        Views(Counters counters) {
            this.counters = counters;
        }

        public net.minecraft.world.entity.Entity entity() {
            return null;
        }
    }

    public static final class Eligible extends Views {
        Eligible(Counters counters) {
            super(counters);
        }
    }

    static final class Counters {
        int arguments;
        int calls;
        int records;
        int derives;
        boolean present;
        MolangContext accepted;
        MolangContext rejected;
    }

    private static Counters counters(MolangContext<?> context) {
        return ((Views)context.entityView()).counters;
    }

    public static Object subject(MolangContext<?> context) {
        return context.entityView();
    }

    public static float argument(MolangContext<?> context) {
        counters(context).arguments++;
        return 4;
    }

    public static float guarded(MolangContext<?> context, float value) {
        counters(context).calls++;
        return value;
    }

    public static ChildContext<MolangContext> derive(MolangContext parent, ChildSubject value,
        SubjectBindingFactory bindings, ExecutionFrame frame) {
        var state = counters(parent);
        state.derives++;
        var child = value.number() == 1 ? state.accepted : state.rejected;
        return new ChildContext<>(child, bindings.bind(child.entityView()));
    }

    public static ChildSubject child(MolangContext<?> context) {
        return new ChildSubject(counters(context).present ? 1 : 0);
    }

    public static Value nullable(MolangContext<?> context) {
        var state = counters(context);
        state.records++;
        return state.present ? new Value(3) : null;
    }

    public static Value record(MolangContext<?> context) {
        var state = counters(context);
        state.records++;
        return new Value(state.present ? 3 : 9);
    }

    @State(Scope.Thread)
    public static class Workload {
        @Param({"false", "true"})
        public boolean divZeroFallback;
        private final Counters counters = new Counters();
        private CompiledModuleResource<MolangContext> resource;
        private CompiledModuleInstance<MolangContext> instance;
        private RuntimeLinkage<MolangContext> linkage;
        private MolangContext accepted;
        private MolangContext rejected;
        private SubjectBinding acceptedBinding;
        private SubjectBinding rejectedBinding;
        private final ProgramRef[] programs = new ProgramRef[4];
        private ProgramRef snapshot;
        private boolean next;

        @Setup(Level.Trial)
        public void setup() throws Throwable {
            var childBinding = new ChildContextBinding(REFERENCE, binding("derive", ChildContext.class,
                MolangContext.class, ChildSubject.class, SubjectBindingFactory.class, ExecutionFrame.class), false);
            var builder = ExpressionRegistry.builder(
                ContextTypes.create("bench:boundary", 1, MolangContext.class, List.of(childBinding), HASH), HASH);
            builder.registerType(new ReferenceTypeDescriptor(REFERENCE, 1, ChildSubject.class));
            var fields = new TreeMap<String, StructMemberDescriptor>();
            fields.put("number",
                new StructMemberDescriptor("number", BuiltinType.F32,
                    Optional.of(new JavaMemberBinding(Value.class, "number", MethodType.methodType(float.class),
                        InvocationKind.RECEIVER, Optional.empty())),
                    Optional.empty(), EffectSet.empty(), EffectSet.empty()));
            builder.registerType(new HostStructTypeDescriptor(RECORD, 1, Value.class, fields,
                CopyStrategyDescriptor.generated("bench:record-copy", 1),
                DisplayStrategyDescriptor.generated("bench:record-display", 1),
                EqualityStrategyDescriptor.generated("bench:record-equality", 1)));
            var guarded = binding("guarded", float.class, MolangContext.class, float.class);
            addFunction(builder, "argument", BuiltinType.F32, float.class, false);
            addFunction(builder, "guarded", BuiltinType.F32, float.class, false, float.class);
            addFunction(builder, "child", REFERENCE, ChildSubject.class, false);
            addFunction(builder, "nullable", RECORD, Value.class, true);
            addFunction(builder, "record", RECORD, Value.class, false);
            var registry = builder.freeze();
            var plan = BindingPlan.builder().subjectAccessor(binding("subject", Object.class, MolangContext.class))
                .guard(guarded, guarded, DeclarationEffect.CONTEXT, Eligible.class, InvocationScope.CURRENT).build();
            var unit = CompilationUnit.builder(CompilationPolicy.DEFAULT, PortableProgramCodecs.v1(), HASH);
            ProgramNode gate = call("guarded", call("argument"));
            add(unit, 0, coalesce(gate), ProgramShape.F32, BuiltinType.F32);
            add(unit, 1, coalesce(new ProgramNode.WithContext(call("child"), gate)), ProgramShape.F32, BuiltinType.F32);
            add(unit, 2, coalesce(new ProgramNode.Member(call("nullable"), "number")), ProgramShape.F32,
                BuiltinType.F32);
            var slot = slot("stored");
            add(unit, 3,
                new ProgramNode.Block(List.of(new ProgramNode.Assignment(slot, call("record")),
                    new ProgramNode.Assignment(new ProgramNode.Member(slot, "number"),
                        new ProgramNode.Member(slot, "number")),
                    new ProgramNode.Member(slot, "number"))),
                ProgramShape.F32, BuiltinType.F32);
            add(unit, 4, slot, ProgramShape.VALUE, new UnionType(List.of(BuiltinType.UNSET, RECORD)));
            var built = unit.build();
            var host =
                new HostAbi("bench:boundary", "1", Fingerprint.compute(HASH, ReadOnlyBytes.copyOf(new byte[] {1})));
            var backend = new JaninoJavaCompilerBackend();
            var options = new CompilerOptions(divZeroFallback);
            var artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, built,
                host, registry, options, plan, HASH);
            var expected =
                ExpectedArtifactIdentity.create(built, host, registry, options, plan, backend.descriptor(), HASH);
            resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, registry,
                registry.visibilityRoot(), HASH);
            var linked = RegistryRuntimeLinkage.<MolangContext>create(ProviderInstanceTable.create(registry, Map.of()),
                RuntimeSymbolTable.empty());
            linkage = new RuntimeLinkage<>() {
                public RegistryRuntimeLinkage<MolangContext> registry() {
                    return linked;
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }
            };
            instance = resource.newInstance(linkage);
            var allowed = new Eligible(counters);
            var denied = new Views(counters);
            var roaming = resource.newRoamingState(RoamingRole.CONSUMER);
            accepted = MolangContext.observation(allowed, allowed, roaming, SOURCE);
            rejected = MolangContext.observation(denied, denied, roaming, SOURCE);
            counters.accepted = accepted;
            counters.rejected = rejected;
            accepted.configureLimits(ExecutionLimits.DEFAULT);
            rejected.configureLimits(ExecutionLimits.DEFAULT);
            acceptedBinding = instance.bindSubject(allowed);
            rejectedBinding = instance.bindSubject(denied);
            for (int i = 0; i < programs.length; i++)
                programs[i] = resource.bindProgram(key(i), ProgramShape.F32);
            snapshot = resource.bindProgram(key(4), ProgramShape.VALUE);
            verifyTruth();
        }

        float execute(int program) throws Throwable {
            counters.present = next = !next;
            boolean useAccepted = program != 0 || next;
            var context = useAccepted ? accepted : rejected;
            context.bindExpression("boundary", programs[program].sourceId());
            try {
                return instance.executeFloat(programs[program], context,
                    useAccepted ? acceptedBinding : rejectedBinding, context);
            } finally {
                context.releaseExpression();
            }
        }

        private Object snapshot() throws Throwable {
            accepted.bindExpression("boundary-snapshot", snapshot.sourceId());
            try {
                return instance.executeValue(snapshot, accepted, acceptedBinding, accepted);
            } finally {
                accepted.releaseExpression();
            }
        }

        private void verifyTruth() throws Throwable {
            for (int program = 0; program < 4; program++) {
                next = false;
                int beforeArguments = counters.arguments, beforeCalls = counters.calls;
                int beforeRecords = counters.records, beforeDerives = counters.derives;
                for (float value : program == 3 ? new float[] {3, 9, 3, 9}
                    : new float[] {program == 2 ? 3 : 4, 7, program == 2 ? 3 : 4, 7})
                    requireBits(value, execute(program));
                if (counters.arguments - beforeArguments != (program < 2 ? 2 : 0)
                    || counters.calls - beforeCalls != (program < 2 ? 2 : 0)
                    || counters.records - beforeRecords != (program >= 2 ? 4 : 0)
                    || counters.derives - beforeDerives != (program == 1 ? 4 : 0))
                    throw new IllegalStateException("boundary call trace differs for program " + program);
            }
            next = false;
            requireBits(3, execute(3));
            var old = (ManagedRecord)snapshot();
            requireBits(9, execute(3));
            requireBits(3, (Float)old.field(0));
            requireBits(9, (Float)((ManagedRecord)snapshot()).field(0));
            try (var other = resource.newInstance(linkage)) {
                accepted.bindExpression("boundary-isolation", snapshot.sourceId());
                try {
                    if (other.executeValue(snapshot, accepted, other.bindSubject(accepted.entityView()),
                        accepted) != ExpressionValues.UNSET)
                        throw new IllegalStateException("managed state leaked across instances");
                } finally {
                    accepted.releaseExpression();
                }
            }
            next = false;
        }

        @TearDown(Level.Trial)
        public void close() {
            if (accepted != null)
                accepted.closeRound();
            if (rejected != null)
                rejected.closeRound();
            if (instance != null)
                instance.close();
            if (resource != null)
                resource.close();
        }
    }

    @Benchmark
    public float deniedSubject(Workload state) throws Throwable {
        return state.execute(0);
    }

    @Benchmark
    public float dynamicChild(Workload state) throws Throwable {
        return state.execute(1);
    }

    @Benchmark
    public float nullableRecord(Workload state) throws Throwable {
        return state.execute(2);
    }

    @Benchmark
    public float managedWriteRead(Workload state) throws Throwable {
        return state.execute(3);
    }

    public static void main(String[] args) throws Throwable {
        for (boolean div : List.of(false, true)) {
            var state = new Workload();
            state.divZeroFallback = div;
            try {
                state.setup();
                System.out.println(
                    "BOUNDARY_ORACLE_OK div=" + div + " gate=4,7,4,7 child=4,7,4,7 nullable=3,7,3,7 managed=3,9,3,9");
            } finally {
                state.close();
            }
        }
    }

    private static void addFunction(ExpressionRegistry.Builder<MolangContext> builder, String name, ValueType result,
        Class<?> carrier, boolean nullable, Class<?>... parameters) {
        var types = Arrays.stream(parameters).map(ignored -> (ValueType)BuiltinType.F32).toList();
        var method = MethodType.methodType(carrier, parameters).insertParameterTypes(0, MolangContext.class);
        builder.registerFunction(new FunctionDescriptor<>(new SymbolKey("bench:" + name), FunctionCallShape.FIXED,
            parameters.length, parameters.length, types, List.of(), Optional.empty(), result,
            binding(name, carrier, method.parameterArray()), EffectSet.empty(), "bench:boundary", 1, Optional.empty(),
            nullable));
    }

    private static JavaMemberBinding binding(String name, Class<?> result, Class<?>... parameters) {
        return new JavaMemberBinding(MolangBoundaryBenchmark.class, name, MethodType.methodType(result, parameters),
            InvocationKind.STATIC, Optional.empty());
    }

    private static ProgramKey key(int index) {
        return new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)index}));
    }

    private static ProgramNode.Symbol slot(String name) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.INSTANCE, name),
            ProgramNode.AccessForm.READ, List.of());
    }

    private static ProgramNode call(String name, ProgramNode... args) {
        return new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.EXTERNAL, "bench:" + name),
            ProgramNode.AccessForm.INVOKE, List.of(args));
    }

    private static ProgramNode coalesce(ProgramNode value) {
        return new ProgramNode.Coalesce(value, ProgramNode.F32.of(7));
    }

    private static void add(CompilationUnit.Builder builder, int index, ProgramNode value, ProgramShape shape,
        ValueType type) throws Exception {
        builder.addProgram(
            new ProgramSource(key(index), PortableProgramCodecs.v1().encode(PortablePrograms.ofRoots(List.of(value))),
                new TopLevelEntrySignature(shape, new EntryResult.Value(type), List.of(), List.of()), Optional.empty(),
                new DiagnosticLocation("boundary-" + index), EntryPermissions.CONTEXT));
    }

    private static void requireBits(float expected, float actual) {
        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual))
            throw new IllegalStateException("boundary result differs: " + expected + " / " + actual);
    }
}
