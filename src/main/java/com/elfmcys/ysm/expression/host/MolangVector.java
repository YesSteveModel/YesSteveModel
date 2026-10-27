package com.elfmcys.ysm.expression.host;

/** Immutable host vector returned by bone inspection providers. */
public record MolangVector(float x, float y, float z) {
    public MolangVector {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("vector components must be finite");
    }
}
