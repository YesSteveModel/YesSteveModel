package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.host.ExpressionIdentityHash;
import dev.semaweave.registry.RegistrySnapshot;
import dev.semaweave.backend.janino.JaninoJavaCompilerBackend;
import dev.semaweave.compilation.*;
import dev.semaweave.HostAbi;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Measures the already verified large matrix without repeating its execution or transient compilation. */
public final class ArrowCapacityCostProbe {
    static Object invoke(Object receiver, String name) throws Exception {
        var method = receiver.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(receiver);
    }

    public static void main(String[] args) throws Exception {
        var matrix = Class.forName("com.elfmcys.ysm.verification.MolangArrowSemanticProcess");
        var casesMethod = matrix.getDeclaredMethod("cases");
        casesMethod.setAccessible(true);
        var programsMethod = matrix.getDeclaredMethod("programs", Path.class, boolean.class, List.class);
        programsMethod.setAccessible(true);
        var environmentMethod = matrix.getDeclaredMethod("environment", List.class, String.class);
        environmentMethod.setAccessible(true);
        var root = Files.createTempDirectory("arrow-capacity-cost-");
        var cases = (List<?>)casesMethod.invoke(null);
        var programs = (List<?>)programsMethod.invoke(null, root, true, cases);
        var environment = environmentMethod.invoke(null, programs, "");
        var unit = (CompilationUnit)invoke(environment, "unit");
        var registry = (RegistrySnapshot<Object>)invoke(environment, "registry");
        var host = (HostAbi)invoke(environment, "host");
        var plan = (BindingPlan)invoke(environment, "bindings");
        var metrics = new ExpressionEngineMetrics();
        var compiler = new DefaultExpressionAotCompiler<Object>(new JaninoJavaCompilerBackend(), metrics);
        for (var pool : ManagementFactory.getMemoryPoolMXBeans())
            pool.resetPeakUsage();
        long start = System.nanoTime();
        var artifact = compiler.compile(TaskContext.NONE, unit, host, registry, CompilerOptions.DEFAULT, plan,
            ExpressionIdentityHash.SHA_256);
        long coldNanos = System.nanoTime() - start;
        var pools = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
            .map(pool -> Map.of("pool", pool.getName(), "peakUsedBytes", pool.getPeakUsage().getUsed())).toList();
        long[] requested = {0};
        long cancelStart = System.nanoTime();
        long due = cancelStart + 50_000_000;
        var cancellationMetrics = new ExpressionEngineMetrics();
        boolean cancelled = false;
        try {
            new DefaultExpressionAotCompiler<Object>(new JaninoJavaCompilerBackend(), cancellationMetrics)
                .compile(() -> {
                    long now = System.nanoTime();
                    if (now < due)
                        return false;
                    if (requested[0] == 0)
                        requested[0] = due;
                    return true;
                }, unit, host, registry, CompilerOptions.DEFAULT, plan, ExpressionIdentityHash.SHA_256);
        } catch (CancellationException expected) {
            cancelled = true;
        }
        long ended = System.nanoTime();
        var result = new LinkedHashMap<String, Object>();
        result.put("programs", programs.size());
        result.put("cases", cases.size());
        result.put("coldEngineNanos", coldNanos);
        result.put("stages", metrics.snapshot());
        result.put("heapPoolPeaks", pools);
        result.put("classCount", artifact.classes().size());
        result.put("classBytes", artifact.classes().values().stream().mapToLong(x -> x.size()).sum());
        result.put("cancelRequestedAfterNanos", due - cancelStart);
        result.put("cancelled", cancelled);
        result.put("cancelResponseNanos", requested[0] == 0 ? null : ended - due);
        result.put("cancelStages", cancellationMetrics.snapshot());
        if (!cancelled)
            throw new AssertionError("large compilation completed without cancellation");
        System.out.println("CAPACITY_COST " + new com.google.gson.Gson().toJson(result));
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}
