package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.host.*;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Alternates producer and consumer writes under the common UPDATE semantics. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MolangRoamingBenchmark {
    private static final ExpressionIdentityHash HASH = ExpressionIdentityHash.SHA_256;
    private static final InvocationProvenance SOURCE = new InvocationProvenance("benchmark", "roaming", -1, 1, 1);

    @State(Scope.Thread)
    public static class Workload {
        @Param({"16", "64", "256"})
        public int fields;
        @Param({"1", "16"})
        public int readsPerWrite;
        @Param({"false", "true"})
        public boolean divZeroFallback;
        private CompiledModuleResource<MolangContext> resource;
        private final CompiledModuleInstance<MolangContext>[] instances = new CompiledModuleInstance[2];
        private final MolangContext[] contexts = new MolangContext[2];
        private final CompiledRoamingState[] roaming = new CompiledRoamingState[2];
        private SubjectBinding subject;
        private ProgramRef[] programs;
        private int cursor;
        private long artifactBytes;
        private long classBytes;

        @Setup(Level.Trial)
        public void setup() throws Throwable {
            var registry = ExpressionRegistry
                .builder(ContextTypes.create("bench:roaming", 1, MolangContext.class, HASH), HASH).freeze();
            var plan = BindingPlan.builder().build();
            var unit = CompilationUnit.builder(CompilationPolicy.DEFAULT, PortableProgramCodecs.v1(), HASH);
            for (int field = 0; field < fields; field++) {
                var slot = new ProgramNode.Symbol(
                    new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.INSTANCE, "field" + field),
                    ProgramNode.AccessForm.READ, List.of());
                unit.addRoamingInstanceSlot(new LocalName("field" + field), "field" + field);
                ProgramNode reads = slot;
                for (int read = 1; read < readsPerWrite; read++)
                    reads = new ProgramNode.Binary(ProgramNode.BinaryOperator.ADD, reads, slot);
                var root =
                    new ProgramNode.Block(List.of(new ProgramNode.Assignment(slot, ProgramNode.F32.of(3)), reads));
                unit.addProgram(new ProgramSource(key(field),
                    PortableProgramCodecs.v1().encode(PortablePrograms.ofRoots(List.of(root))),
                    new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                        List.of()),
                    Optional.empty(), new DiagnosticLocation("roaming-field-" + field), EntryPermissions.CONTEXT));
            }
            var built = unit.build();
            var host =
                new HostAbi("bench:roaming", "1", Fingerprint.compute(HASH, ReadOnlyBytes.copyOf(new byte[] {1})));
            var options = new CompilerOptions(divZeroFallback);
            var backend = new JaninoJavaCompilerBackend();
            var artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, built,
                host, registry, options, plan, HASH);
            artifactBytes = ArtifactCodecs.v1().encode(artifact, HASH).size();
            classBytes = artifact.classes().values().stream().mapToLong(ReadOnlyBytes::size).sum();
            var expected =
                ExpectedArtifactIdentity.create(built, host, registry, options, plan, backend.descriptor(), HASH);
            resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, registry,
                registry.visibilityRoot(), HASH);
            var linked = RegistryRuntimeLinkage.<MolangContext>create(ProviderInstanceTable.create(registry, Map.of()),
                RuntimeSymbolTable.empty());
            var linkage = new RuntimeLinkage<MolangContext>() {
                public RegistryRuntimeLinkage<MolangContext> registry() {
                    return linked;
                }

                public RuntimeDiagnostics diagnostics() {
                    return RuntimeDiagnostics.none();
                }
            };
            var inputs = new InvocationInputs<net.minecraft.world.entity.Entity>() {};
            for (int role = 0; role < 2; role++) {
                roaming[role] = resource.newRoamingState(role == 0 ? RoamingRole.PRODUCER : RoamingRole.CONSUMER);
                instances[role] = resource.newInstance(linkage, roaming[role]);
                contexts[role] =
                    MolangContext.logical(inputs, inputs, MolangContext.LogicalPhase.UPDATE, roaming[role], SOURCE);
                contexts[role].configureLimits(ExecutionLimits.DEFAULT);
            }
            subject = instances[0].bindSubject(inputs);
            programs = new ProgramRef[fields];
            for (int field = 0; field < fields; field++)
                programs[field] = resource.bindProgram(key(field), ProgramShape.F32);
            for (int call = 0; call < fields * 2; call++)
                require(3 * readsPerWrite, execute());
            verifyState();
            cursor = 0;
        }

        float execute() throws Throwable {
            int step = cursor++;
            var program = programs[(step >>> 1) & (fields - 1)];
            var context = contexts[step & 1];
            context.bindExpression("roaming", program.sourceId());
            try {
                return instances[step & 1].executeFloat(program, context, subject, context);
            } finally {
                context.releaseExpression();
            }
        }

        private void verifyState() {
            for (var state : roaming) {
                if (state.fieldNames().size() != fields)
                    throw new IllegalStateException("roaming field count differs");
                for (int field = 0; field < fields; field++)
                    require(3, state.read((short)state.fieldNames().indexOf("field" + field)));
            }
            if (!roaming[0].hasChanges())
                throw new IllegalStateException("producer lost its delta");
            if (roaming[0].snapshot().values().length != fields)
                throw new IllegalStateException("producer snapshot differs");
        }

        @TearDown(Level.Iteration)
        public void verify() {
            verifyState();
            var delta = roaming[0].popChanges();
            if (delta.values().length != fields || roaming[0].hasChanges())
                throw new IllegalStateException("producer delta differs");
            for (float value : delta.values())
                require(3, value);
        }

        @TearDown(Level.Trial)
        public void close() {
            for (var context : contexts)
                if (context != null)
                    context.closeRound();
            for (var instance : instances)
                if (instance != null)
                    instance.close();
            if (resource != null)
                resource.close();
        }
    }

    @Benchmark
    public float readWrite(Workload state) throws Throwable {
        return state.execute();
    }

    public static void main(String[] args) throws Throwable {
        for (boolean div : List.of(false, true))
            for (int fields : new int[] {16, 64, 256})
                for (int reads : new int[] {1, 16}) {
                    var state = new Workload();
                    state.fields = fields;
                    state.readsPerWrite = reads;
                    state.divZeroFallback = div;
                    try {
                        state.setup();
                        state.verify();
                        System.out.println("ROAMING_ORACLE_OK div=" + div + " fields=" + fields + " reads=" + reads);
                        System.out.println("ROAMING_ARTIFACT_RESULT " + new com.google.gson.Gson()
                            .toJson(Map.of("divZeroFallback", div, "fields", fields, "readsPerWrite", reads,
                                "artifactBytes", state.artifactBytes, "classBytes", state.classBytes)));
                    } finally {
                        state.close();
                    }
                }
    }

    private static ProgramKey key(int field) {
        return new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {(byte)field, (byte)(field >>> 8)}));
    }

    private static void require(float expected, float actual) {
        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual))
            throw new IllegalStateException("roaming value differs");
    }
}
