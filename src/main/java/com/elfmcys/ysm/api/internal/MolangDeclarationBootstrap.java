package com.elfmcys.ysm.api.internal;

import com.elfmcys.ysm.expression.host.*;
import java.util.List;

/** The single process-startup collector; registration is closed before any target can bake. */
public final class MolangDeclarationBootstrap {
    private static final MolangExtensions.Collector COLLECTOR = new MolangExtensions.Collector();

    private MolangDeclarationBootstrap() {}

    public static void submit(MolangExtensions.Submission submission) {
        COLLECTOR.submit(submission);
    }

    public static MolangExtensions.Admission freeze(HostAvailability availability,
        List<MolangExtensions.Source> sources, MolangExtensions.Qualification qualification) {
        COLLECTOR.collectBuiltins();
        sources.forEach(COLLECTOR::collect);
        return COLLECTOR.freeze(availability, qualification);
    }
}
