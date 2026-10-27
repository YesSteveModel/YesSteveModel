package com.elfmcys.ysm.expression.host;

import java.util.Objects;

/** Single logical transaction owner for an invocation-local effect batch. */
public final class EffectTransaction {
    private EffectTransaction() {}

    @FunctionalInterface
    public interface Body<T> {
        T run(MolangContext context) throws Throwable;
    }

    public static <T> T execute(MolangContext context, EffectSink sink, Body<T> body) throws Throwable {
        return execute(context, sink, body, true);
    }

    /**
     * Runs an entity-owned body whose individual compiled programs perform their own eligibility checks. This lets a
     * failed coded-controller program return its typed fallback so the Java predicate can still run before the whole
     * batch is discarded.
     */
    public static <T> T executeAfterProgramValidation(MolangContext context, EffectSink sink, Body<T> body)
        throws Throwable {
        return execute(context, sink, body, false);
    }

    private static <T> T execute(MolangContext context, EffectSink sink, Body<T> body, boolean verifyBeforeBody)
        throws Throwable {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(body, "body");
        if (context.phase() == MolangContext.LogicalPhase.OBSERVATION) {
            throw new IllegalArgumentException("observation is not a logical effect transaction");
        }
        try {
            if (verifyBeforeBody) {
                context.verifyEligible();
            }
            var result = body.run(context);
            context.verifyEligible();
            if (context.firstFailure() == null)
                context.commit(sink);
            else
                context.discard();
            return result;
        } catch (Throwable failure) {
            context.discard();
            throw failure;
        }
    }

    /** Finalizes an already evaluated owner body without a typed-result handoff wrapper. */
    public static void finish(MolangContext context, EffectSink sink, boolean successful) {
        if (!successful || context.firstFailure() != null) {
            context.discard();
            return;
        }
        try {
            context.verifyEligible();
            context.commit(sink);
        } catch (Throwable failure) {
            context.discard();
            throw failure;
        }
    }
}
