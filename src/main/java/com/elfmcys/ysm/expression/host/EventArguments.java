package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import it.unimi.dsi.fastutil.floats.AbstractFloatList;
import it.unimi.dsi.fastutil.floats.FloatList;
import java.util.Objects;
import java.util.RandomAccess;

/** Immutable event snapshot; generated reads use the shared FloatList carrier. */
public final class EventArguments extends AbstractFloatList implements RandomAccess {
    private final float[] values;

    private EventArguments(float[] ownedValues, int maximum) {
        if (ownedValues.length > maximum) {
            throw new IllegalArgumentException("event arguments exceed catalog bound " + maximum);
        }
        for (float value : ownedValues) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("event argument must be finite");
            }
        }
        values = ownedValues;
    }

    public static EventArguments sync(float... values) {
        return new EventArguments(values.clone(), MolangEventCatalog.SYNC_MAX_ARGUMENTS);
    }

    public static EventArguments sync(FloatList values) {
        return new EventArguments(Objects.requireNonNull(values).toFloatArray(), MolangEventCatalog.SYNC_MAX_ARGUMENTS);
    }

    public static EventArguments deferredFloats(float... values) {
        return new EventArguments(values.clone(), MolangEventCatalog.DEFER_MAX_ARGUMENTS);
    }

    public static EventArguments deferred(float delay, float[] payload) {
        var values = new float[payload.length + 1];
        values[0] = delay;
        System.arraycopy(payload, 0, values, 1, payload.length);
        return new EventArguments(values, MolangEventCatalog.DEFER_MAX_ARGUMENTS);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public float getFloat(int index) {
        return values[index];
    }

}
