package com.elfmcys.ysm.api.molang;

import com.elfmcys.ysm.expression.host.MolangExtensions;

import java.net.URL;
import java.util.Objects;

/** Manual startup admission without loading an implementation or subject class. */
public final class MolangDeclarations {
    private MolangDeclarations() {}

    /**
     * Submit the generated resource identity, its exact origin and an independent precheck result. Submit once before
     * client host startup. Rejection remains class-local; late submission fails.
     */
    public static void submit(String resource, URL origin, boolean compatible, String detail) {
        if (!resource.startsWith(DeclarationSchema.RESOURCE_ROOT) || !resource.endsWith(".bin"))
            throw new IllegalArgumentException("expected a generated Molang resource identity");
        com.elfmcys.ysm.api.internal.MolangDeclarationBootstrap.submit(new MolangExtensions.Submission(resource,
            Objects.requireNonNull(origin), compatible, Objects.requireNonNull(detail)));
    }
}
