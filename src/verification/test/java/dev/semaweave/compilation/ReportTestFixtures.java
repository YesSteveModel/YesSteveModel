package dev.semaweave.compilation;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Keeps lifecycle fixtures valid without expanding the production report interface. */
public final class ReportTestFixtures {
    private ReportTestFixtures() {}

    public static CompilationReport emptyReport() {
        var metrics = new CompilationMetrics(0, 0, 0, 0, 0, 0, 0, 0);
        var optimizations = OptimizationStatistics.empty();
        var codes = new TreeMap<String, Long>();
        var encoded =
            ArtifactV1Codec.encodeReport(metrics, optimizations, Map.of(), codes, List.of(), List.of(), List.of());
        return new CompilationReport(metrics, optimizations, Map.of(), codes, List.of(), List.of(), List.of(), encoded);
    }
}
