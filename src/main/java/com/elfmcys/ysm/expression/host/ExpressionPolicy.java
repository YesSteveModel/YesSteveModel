package com.elfmcys.ysm.expression.host;

import dev.semaweave.ArityMismatchPolicy;
import dev.semaweave.CompilationPolicy;
import dev.semaweave.CompilerLimits;
import dev.semaweave.ExecutionLimits;
import dev.semaweave.MissingSymbolPolicy;
import dev.semaweave.NonFinitePolicy;
import dev.semaweave.NumericPolicy;
import dev.semaweave.ObjectLanguageRules;
import dev.semaweave.SemanticFailurePolicy;

/** Host-owned limits shared by admission, cold compilation, loading and invocation. */
public final class ExpressionPolicy {
    public static final CompilerLimits COMPILER_LIMITS = new CompilerLimits(65_535, 64L << 20, 1_000_000,
        16_384, 16_384, 16_384, 4_096, 4_096, 16_384, 16, 64, 65_535, 64L << 20, 64L << 20, 4_096,
        16L << 20, 128L << 20, 80_000, 256, 1_024, 1_000_000, 64L << 20, 256L << 20, 1_000_000,
        4_096, 16_000_000);
    public static final ExecutionLimits EXECUTION_LIMITS = new ExecutionLimits(10_000, 32, 10_000, 10_000, 8_192);
    public static final CompilationPolicy COMPILATION = create(COMPILER_LIMITS, EXECUTION_LIMITS);

    private ExpressionPolicy() {}

    public static CompilationPolicy create(CompilerLimits compilerLimits, ExecutionLimits executionLimits) {
        return new CompilationPolicy(MissingSymbolPolicy.UNSET, SemanticFailurePolicy.USE_DECLARED_FALLBACK,
            ArityMismatchPolicy.UNSET_CALL, new NumericPolicy(NonFinitePolicy.FAIL_EXECUTION, false, false),
            executionLimits, compilerLimits, ObjectLanguageRules.YSM_STATIC_OBJECTS);
    }
}
