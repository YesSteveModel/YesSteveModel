package com.elfmcys.ysm.network.protocol;

import java.util.Objects;
import java.util.Arrays;

/** The current dense values of one selection. Its owner serializes trusted apply and snapshot. */
public final class RoamingProjection {
    private final float[] values;

    public RoamingProjection(RoamingDictionary dictionary, RoamingValues initial) {
        Objects.requireNonNull(dictionary, "dictionary");
        values = new float[dictionary.size()];
        apply(initial, true);
    }

    public void apply(RoamingValues payload, boolean full) {
        if (full)
            Arrays.fill(values, 0.0f);
        for (int index = 0; index < payload.ids().length; index++) {
            int id = payload.ids()[index];
            values[id] = payload.values()[index];
        }
    }

    public RoamingValues snapshot() {
        var ids = new short[values.length];
        var snapshot = values.clone();
        for (short id = 0; id < values.length; id++) {
            ids[id] = id;
        }
        return new RoamingValues(ids, snapshot);
    }
}
