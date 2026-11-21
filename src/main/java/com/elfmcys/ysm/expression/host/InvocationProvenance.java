package com.elfmcys.ysm.expression.host;

import java.util.Objects;

/** Exact attribution carried by runtime, budget, cancellation and effect failures. */
public record InvocationProvenance(String targetId, String programLocation, int programId, long targetGeneration,
    long entityGeneration) {
    public InvocationProvenance {
        targetId = requireText(targetId, "targetId");
        programLocation = requireText(programLocation, "programLocation");
        if (programId < -1) {
            throw new IllegalArgumentException("programId must be -1 or non-negative");
        }
        if (targetGeneration < 0 || entityGeneration < 0) {
            throw new IllegalArgumentException("generations must be non-negative");
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
