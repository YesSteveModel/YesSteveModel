package com.elfmcys.ysm.benchmark;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.*;

import java.util.Collection;
import java.util.List;

/** Reports engine-owned phase timers without attributing cache deletion or module loading to them. */
public final class PlayerMolangPhaseProfiler implements InternalProfiler {
    @Override
    public String getDescription() {
        return "Player Molang compiler phase durations";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmark, IterationParams iteration) {
        PlayerMolangCompileBenchmark.PHASES.clear();
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams benchmark, IterationParams iteration,
        IterationResult result) {
        double semantic = 0, source = 0, backend = 0;
        double programs = 0, typedNodes = 0, sourceBytes = 0, replacements = 0;
        int count = 0;
        for (var sample : PlayerMolangCompileBenchmark.PHASES) {
            semantic += sample.semantic();
            source += sample.source();
            backend += sample.backend();
            programs += sample.programs();
            typedNodes += sample.typedNodes();
            sourceBytes += sample.sourceBytes();
            replacements += sample.replacements();
            count++;
        }
        if (count == 0)
            throw new IllegalStateException("no compiler phase samples");
        return List.of(new ScalarResult("semantic", semantic / count, "ms/op", AggregationPolicy.AVG),
            new ScalarResult("source", source / count, "ms/op", AggregationPolicy.AVG),
            new ScalarResult("backend", backend / count, "ms/op", AggregationPolicy.AVG),
            new ScalarResult("programs", programs / count, "#/op", AggregationPolicy.AVG),
            new ScalarResult("typedNodes", typedNodes / count, "#/op", AggregationPolicy.AVG),
            new ScalarResult("sourceBytes", sourceBytes / count, "B/op", AggregationPolicy.AVG),
            new ScalarResult("replacements", replacements / count, "#/op", AggregationPolicy.AVG));
    }
}
