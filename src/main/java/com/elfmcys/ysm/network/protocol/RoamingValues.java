package com.elfmcys.ysm.network.protocol;

import java.util.Objects;

/** A trusted selection-local batch. The caller transfers read-only arrays and must copy before mutation. */
public record RoamingValues(short[] ids, float[] values) {
    public RoamingValues {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(values, "values");
    }
}
