package com.elfmcys.ysm.model.storage;

import dev.semaweave.RoamingPayload;
import java.util.Arrays;
import java.util.List;

/**
 * Preserves field identity across compilations. Arrays are stable and read-only after publication; storage entry points
 * validate external values before retaining them.
 */
public record NamedRoamingSnapshot(String[] names, float[] values) {
    @Override
    public boolean equals(Object other) {
        return other instanceof NamedRoamingSnapshot snapshot && Arrays.equals(names, snapshot.names)
            && Arrays.equals(values, snapshot.values);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(names) + Arrays.hashCode(values);
    }

    public static NamedRoamingSnapshot fromPayload(List<String> fieldNames, RoamingPayload payload) {
        var names = new String[payload.fields().length];
        for (int index = 0; index < names.length; index++)
            names[index] = fieldNames.get(payload.fields()[index]);
        return new NamedRoamingSnapshot(names, payload.values());
    }

    /** Restoring an unknown persisted field must fail instead of silently discarding player data. */
    public RoamingPayload toPayload(List<String> fieldNames) {
        var fields = new short[names.length];
        for (int index = 0; index < names.length; index++) {
            int field = fieldNames.indexOf(names[index]);
            if (field < 0)
                throw new IllegalArgumentException("Roaming value does not match the target layout");
            fields[index] = (short)field;
        }
        return new RoamingPayload(fields, values);
    }
}
