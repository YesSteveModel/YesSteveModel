package com.elfmcys.ysm.expression.host;

import java.util.Objects;

/** Stable host failure with exact invocation provenance. */
public final class ExecutionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        FRAME_STATE, LOOP_BUDGET, PROVIDER_BUDGET, CONTROL_TRANSFER_BUDGET, USER_FUNCTION_DEPTH,
        ARGUMENT_SCRATCH_BUDGET, CANCELLATION, STALE_GENERATION, EFFECT_NOT_AUTHORIZED, EFFECT_BATCH_LIMIT,
        EFFECT_BATCH_STATE, NON_NULL_RETURN
    }

    private final Reason reason;
    private final InvocationProvenance provenance;
    private final java.util.OptionalLong maximum;

    public ExecutionFailure(Reason reason, InvocationProvenance provenance, String message) {
        this(reason, provenance, message, null);
    }

    public ExecutionFailure(Reason reason, InvocationProvenance provenance, String message, Throwable cause) {
        this(reason, provenance, message, cause, java.util.OptionalLong.empty());
    }

    public ExecutionFailure(Reason reason, InvocationProvenance provenance, String message, long maximum) {
        this(reason, provenance, message, null, java.util.OptionalLong.of(maximum));
    }

    private ExecutionFailure(Reason reason, InvocationProvenance provenance, String message, Throwable cause,
        java.util.OptionalLong maximum) {
        super(message, cause);
        this.maximum = maximum;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    public java.util.OptionalLong maximum() { return maximum; }

    public String phase() { return "EXECUTION"; }

    public Reason reason() {
        return reason;
    }

    public InvocationProvenance provenance() {
        return provenance;
    }
}
