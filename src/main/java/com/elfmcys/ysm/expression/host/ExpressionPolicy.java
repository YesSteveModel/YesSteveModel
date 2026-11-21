package com.elfmcys.ysm.expression.host;

import dev.semaweave.ArityMismatchPolicy;
import dev.semaweave.CompilationPolicy;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.MissingSymbolPolicy;
import dev.semaweave.NonFinitePolicy;
import dev.semaweave.NumericPolicy;
import dev.semaweave.ObjectLanguageRules;
import dev.semaweave.SemanticFailurePolicy;

/** Host-owned language and invocation policy. */
public final class ExpressionPolicy {
    public static final ExecutionLimits EXECUTION_LIMITS = new ExecutionLimits(32);
    public static final CompilationPolicy COMPILATION = create(EXECUTION_LIMITS);

    private ExpressionPolicy() {}

    public static CompilationPolicy create(ExecutionLimits executionLimits) {
        return new CompilationPolicy(MissingSymbolPolicy.UNSET, SemanticFailurePolicy.USE_DECLARED_FALLBACK,
            ArityMismatchPolicy.UNSET_CALL, new NumericPolicy(NonFinitePolicy.ZERO_NAN_ARITHMETIC, false, false),
            executionLimits, ObjectLanguageRules.YSM_STATIC_OBJECTS);
    }
}
