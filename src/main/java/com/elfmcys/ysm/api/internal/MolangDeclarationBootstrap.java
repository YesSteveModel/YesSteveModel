package com.elfmcys.ysm.api.internal;

import com.elfmcys.ysm.expression.host.*;
import java.util.List;

/**
 * The single process-startup collector; registration is closed before any target can bake.
 *
 * <p>Execution: the collector serializes submissions and admission. Freeze is a one-shot startup
 * operation, including after failure; closing a client service does not reset this process collector.
 *
 * <p>Seam: owns the publication gate for
 * [declaration admission](docs/architecture/integration/overview.md#扩展接纳与声明发布).
 */
public final class MolangDeclarationBootstrap {
    private static final MolangExtensions.Collector COLLECTOR = new MolangExtensions.Collector();

    private MolangDeclarationBootstrap() {}

    /**
     * Records a manual precheck receipt, not an admitted implementation class. Admission later verifies
     * its exact source, uniqueness, and acceptance; late submissions throw IllegalStateException.
     */
    public static void submit(MolangExtensions.Submission submission) {
        COLLECTOR.submit(submission);
    }

    /**
     * Collects builtins and supplied discovery facts, then seals and resolves the complete candidate set.
     * Normal return publishes an immutable host and class-level diagnostics. Callers must not retry freeze
     * on this process collector or infer provider execution safety from a successful admission.
     */
    public static MolangExtensions.Admission freeze(HostAvailability availability,
        List<MolangExtensions.Source> sources, MolangExtensions.Qualification qualification) {
        COLLECTOR.collectBuiltins();
        sources.forEach(COLLECTOR::collect);
        return COLLECTOR.freeze(availability, qualification);
    }
}
