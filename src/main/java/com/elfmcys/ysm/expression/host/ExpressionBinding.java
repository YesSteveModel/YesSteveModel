package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.compatibility.AbiVersion;

import dev.semaweave.compilation.BindingPlan;
import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One frozen declaration environment and call plan, shared by every subject. */
public final class ExpressionBinding {
    private final DeclarationEnvironment environment;
    private final BindingPlan bindingPlan;
    private final Fingerprint fingerprint;

    public ExpressionBinding(DeclarationEnvironment environment, BindingPlan bindingPlan) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.bindingPlan = Objects.requireNonNull(bindingPlan, "bindingPlan");
        this.fingerprint =
            Fingerprint
                .compute(ExpressionIdentityHash.SHA_256,
                    ReadOnlyBytes.copyOf(("ysm:static-binding:" + AbiVersion.CACHE_ANIMATION + ":"
                        + environment.fingerprint() + ':' + bindingPlan.fingerprint(ExpressionIdentityHash.SHA_256))
                        .getBytes(StandardCharsets.UTF_8)));
    }

    public DeclarationEnvironment environment() {
        return environment;
    }

    public BindingPlan bindingPlan() {
        return bindingPlan;
    }

    public Fingerprint fingerprint() {
        return fingerprint;
    }

    public String key() {
        return fingerprint.value().toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExpressionBinding binding && fingerprint.equals(binding.fingerprint)
            && environment.equals(binding.environment)
            && bindingPlan.rootSubjectType().equals(binding.bindingPlan.rootSubjectType());
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint, environment, bindingPlan.rootSubjectType());
    }

    public static ExpressionBinding standalone(BindingPlan plan) {
        return new ExpressionBinding(DeclarationEnvironment.create(java.util.List.of()), plan);
    }

    public static ExpressionBinding standalone() {
        return standalone(BindingPlan.identity());
    }
}
