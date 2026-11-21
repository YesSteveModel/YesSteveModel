package com.elfmcys.ysm.expression.host;

import java.util.Objects;

/** Stable host failure with exact invocation provenance. */
public final class ExecutionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        USER_FUNCTION_DEPTH, STALE_GENERATION, INSTANCE_EXECUTION
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

    public java.util.OptionalLong maximum() {
        return maximum;
    }

    public String phase() {
        return "EXECUTION";
    }

    public boolean isUserFunctionDepthExceeded() {
        return reason == Reason.USER_FUNCTION_DEPTH;
    }

    public Reason reason() {
        return reason;
    }

    public InvocationProvenance provenance() {
        return provenance;
    }

}
