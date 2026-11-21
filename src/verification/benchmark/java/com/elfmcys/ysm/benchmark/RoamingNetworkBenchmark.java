package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.capability.RoamingNetworkAdapter;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.network.frame.FrameCodec;
import dev.semaweave.*;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.*;
import dev.semaweave.registry.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures one generated export and the host/wire boundaries with an independent named oracle. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RoamingNetworkBenchmark {
    private static boolean initialized;

    private static synchronized void initializeNative() throws Exception {
        if (!initialized) {
            com.elfmcys.ysm.natives.NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            com.elfmcys.ysm.natives.NativeRuntime.initialize(
                com.elfmcys.ysm.natives.NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            initialized = true;
        }
    }

    @State(Scope.Thread)
    public static class Workload {
        @Param({"1", "8", "32", "64"})
        public int fields;
        @Param({"full", "all", "sparse", "empty", "selection", "track"})
        public String opportunity;
        public int nameBytes = 12;
        private CompiledModuleResource<MolangContext> resource;
        private CompiledRoamingState producer;
        private CompiledRoamingState consumer;
        private String[] names;
        private short[] localFields;
        private float[] values;
        private RoamingNetworkAdapter adapter;
        private RoamingPayload batch;
        private Object mapped;
        private byte[] protobuf;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            initializeNative();
            names = new String[fields];
            values = new float[fields];
            var hash = ExpressionIdentityHash.SHA_256;
            var registry = ExpressionRegistry
                .builder(ContextTypes.create("bench:network", 1, MolangContext.class, hash), hash).freeze();
            var unit = CompilationUnit.builder(CompilationPolicy.DEFAULT, PortableProgramCodecs.v1(), hash);
            var roots = new ArrayList<ProgramNode>();
            for (int i = 0; i < fields; i++) {
                names[i] = nameBytes == 12 ? String.format(java.util.Locale.ROOT, "field_%06d", fields - i - 1)
                    : "界".repeat(9) + String.format(java.util.Locale.ROOT, "%05d", fields - i - 1);
                if (names[i].getBytes(StandardCharsets.UTF_8).length != nameBytes)
                    throw new IllegalStateException("Name width differs");
                values[i] = i + 0.25f;
                var local = "local" + i;
                unit.addRoamingInstanceSlot(new LocalName(local), names[i]);
                var slot = new ProgramNode.Symbol(new ProgramNode.SymbolRef(ProgramNode.SymbolDomain.INSTANCE, local),
                    ProgramNode.AccessForm.READ, List.of());
                roots.add(new ProgramNode.Assignment(slot, ProgramNode.F32.of(1)));
            }
            roots.add(ProgramNode.F32.of(1));
            unit.addProgram(
                new ProgramSource(new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1})),
                    PortableProgramCodecs.v1().encode(PortablePrograms.ofRoots(roots)),
                    new TopLevelEntrySignature(ProgramShape.F32, new EntryResult.Value(BuiltinType.F32), List.of(),
                        List.of()),
                    Optional.empty(), new DiagnosticLocation("roaming-network"), EntryPermissions.CONTEXT));
            var built = unit.build();
            var host =
                new HostAbi("bench:network", "1", Fingerprint.compute(hash, ReadOnlyBytes.copyOf(new byte[] {1})));
            var options = new CompilerOptions(false);
            var plan = BindingPlan.builder().build();
            var backend = new JaninoJavaCompilerBackend();
            var artifact = new DefaultExpressionAotCompiler<MolangContext>(backend).compile(TaskContext.NONE, built,
                host, registry, options, plan, hash);
            var expected =
                ExpectedArtifactIdentity.create(built, host, registry, options, plan, backend.descriptor(), hash);
            resource = ModuleLoaders.<MolangContext>standard().load(artifact, expected, registry,
                registry.visibilityRoot(), hash);
            producer = resource.newRoamingState(RoamingRole.PRODUCER);
            consumer = resource.newRoamingState(RoamingRole.CONSUMER);
            localFields = new short[fields];
            for (int i = 0; i < fields; i++) {
                localFields[i] = (short)producer.fieldNames().indexOf(names[i]);
                producer.write(localFields[i], values[i]);
            }
            producer.full();
            adapter = new RoamingNetworkAdapter(producer.fieldNames().toArray(String[]::new), consumer);
            batch = export();
            mapped = adapter.map(batch);
            protobuf = adapter.encode(mapped, opportunity);
            adapter.primeDiagnostics(protobuf, opportunity);
            adapter.decodeApply(protobuf, opportunity);
            verify();
        }

        RoamingPayload export() {
            return switch (opportunity) {
                case "full" -> producer.full();
                case "sparse" -> {
                    producer.write(localFields[fields - 1], values[fields - 1]);
                    yield producer.popChanges();
                }
                case "empty" -> producer.popChanges();
                case "all" -> {
                    for (int i = 0; i < fields; i++)
                        producer.write(localFields[i], values[i]);
                    yield producer.popChanges();
                }
                case "selection", "track" -> producer.snapshot();
                default -> throw new IllegalArgumentException(opportunity);
            };
        }

        boolean full() {
            return opportunity.equals("selection") || opportunity.equals("track") || opportunity.equals("full");
        }

        byte[] frame(byte[] bytes) {
            try (var encoded = FrameCodec.encode(adapter.protocolId(opportunity), bytes, null)) {
                return encoded.bytes();
            }
        }

        void receive(byte[] bytes) throws Exception {
            try (var decoded = FrameCodec.decode(bytes, id -> id == adapter.protocolId(opportunity))) {
                var proto = new byte[decoded.protobuf().size()];
                decoded.protobuf().nio().get(proto);
                adapter.decodeApply(proto, opportunity);
            }
        }

        void verify() {
            var named = adapter.named(batch);
            int count = opportunity.equals("empty") ? 0 : opportunity.equals("sparse") ? 1 : fields;
            if (named.size() != count)
                throw new IllegalStateException("Export presence differs");
            for (int i = 0; i < fields; i++) {
                boolean present = count == fields || opportunity.equals("sparse") && i == fields - 1;
                var value = named.get(names[i]);
                if (present && (value == null || Float.floatToRawIntBits(value) != Float.floatToRawIntBits(values[i])))
                    throw new IllegalStateException("Named export differs: " + names[i]);
                if (!present && value != null)
                    throw new IllegalStateException("Unexpected exported field");
                float actual = consumer.read(localFields[i]);
                if (Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(present ? values[i] : 0.0f))
                    throw new IllegalStateException("Decoded named state differs: " + opportunity + "/" + names[i]);
            }
        }

        @TearDown(Level.Trial)
        public void close() {
            if (resource != null)
                resource.close();
        }
    }

    @Benchmark
    public Object generatedExport(Workload state) {
        return state.export();
    }

    @Benchmark
    public Object mapping(Workload state) {
        return state.adapter.map(state.batch);
    }

    @Benchmark
    public float accumulatedApply(Workload state) {
        state.adapter.apply(state.mapped, state.full());
        return state.consumer.read(state.localFields[state.fields - 1]);
    }

    @Benchmark
    public byte[] protobuf(Workload state) {
        return state.adapter.encode(state.mapped, state.opportunity);
    }

    @Benchmark
    public Object relation(Workload state) {
        return state.adapter.createRelation();
    }

    @Benchmark
    public byte[] chain(Workload state) throws Exception {
        var payload = state.export();
        var numeric = state.adapter.map(payload);
        var packet = state.adapter.encode(numeric, state.opportunity);
        var wire = state.frame(packet);
        state.receive(wire);
        return wire;
    }

    @Benchmark
    public void diagnostics(Workload state) {
        state.adapter.diagnostics();
    }

    public static void main(String[] args) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int width : new int[] {12, 32})
            for (int fields : new int[] {1, 8, 32, 64})
                for (String opportunity : List.of("full", "all", "sparse", "empty", "selection", "track")) {
                    var state = new Workload();
                    state.fields = fields;
                    state.opportunity = opportunity;
                    state.nameBytes = width;
                    try {
                        state.setup();
                        var wire = new RoamingNetworkBenchmark().chain(state);
                        state.verify();
                        rows.add(Map.of("fields", fields, "opportunity", opportunity, "nameBytes", width, "named",
                            state.adapter.named(state.batch), "presence", state.adapter.named(state.batch).keySet(),
                            "protobufBytes", state.protobuf.length, "frameBytes", wire.length, "roamingBytes",
                            state.adapter.roamingBytes(state.mapped), "protobufHex",
                            HexFormat.of().formatHex(state.protobuf), "frameHex", HexFormat.of().formatHex(wire)));
                    } finally {
                        state.close();
                    }
                }
        Files.writeString(Path.of(args[0]),
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(rows));
        System.out.println("ROAMING_NETWORK_ORACLE_OK rows=" + rows.size());
    }
}
