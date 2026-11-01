package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Scans the actual MAIN samples under a fixed observation with no Minecraft subject. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
public class PlayerMolangExecutionBenchmark {
    @State(Scope.Thread)
    public static class Workload {
        private PlayerMolangCompileBenchmark.Workload compiled;
        private EntityExpressionRuntime runtime;
        private List<FloatExpression.Program> programs;
        private final EntityExpressionRuntime.InvocationInputs inputs = new EntityExpressionRuntime.InvocationInputs(
            new InvocationInputs<net.minecraft.world.entity.Entity>() {}, (commands, provenance) -> () -> {
            }, () -> false);
        private long expected;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            net.minecraft.SharedConstants.tryDetectVersion();
            var gate = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
            gate.setAccessible(true);
            boolean previous = gate.getBoolean(null);
            gate.setBoolean(null, true);
            try {
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY.size();
            } finally {
                gate.setBoolean(null, previous);
            }
            compiled = new PlayerMolangCompileBenchmark.Workload();
            compiled.selection = "full";
            compiled.setup();
            compiled.coldCache();
            new PlayerMolangCompileBenchmark().coldCompile(compiled);
            programs = compiled.module.programs().keySet().stream()
                .filter(location -> location.role() == ProgramRole.ANIMATION_SAMPLE)
                .sorted(Comparator.comparing(Object::toString)).map(compiled.module::requireFloatProgram).toList();
            if (programs.isEmpty())
                throw new AssertionError("empty MAIN sample scan");
            runtime = new EntityExpressionRuntime();
            runtime.replace(compiled.module, Map.of(), TestComponentHolders.linkageScope());
            expected = scan();
            for (int i = 0; i < 10; i++)
                if (scan() != expected)
                    throw new AssertionError("unstable MAIN observation");
        }

        long scan() {
            return runtime.observe(inputs, evaluation -> {
                long hash = 1;
                for (var program : programs)
                    hash = hash * 31 + Float.floatToRawIntBits(evaluation.evaluate(program));
                return hash;
            });
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (runtime != null)
                runtime.close();
            if (compiled != null) {
                compiled.verifyCold();
                compiled.close();
            }
        }
    }

    @Benchmark
    public long scanMain(Workload state) {
        return state.scan();
    }

    public static void main(String[] args) throws Exception {
        var state = new Workload();
        try {
            state.setup();
            System.out.println("EXECUTION_RESULT " + new com.google.gson.Gson()
                .toJson(Map.of("programs", state.programs.size(), "checksum", state.expected, "subject", "absent",
                    "rawBits", state.runtime.observe(state.inputs, evaluation -> state.programs.stream()
                        .map(program -> Float.floatToRawIntBits(evaluation.evaluate(program))).toList()))));
        } finally {
            state.close();
        }
    }
}
