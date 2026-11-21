package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import it.unimi.dsi.fastutil.floats.AbstractFloatList;
import it.unimi.dsi.fastutil.floats.FloatList;
import java.util.Objects;
import java.util.RandomAccess;

/** Immutable event snapshot; generated reads use the shared FloatList carrier. */
public final class EventArguments extends AbstractFloatList implements RandomAccess {
    private final float[] values;

    private EventArguments(float[] ownedValues) {
        for (float value : ownedValues) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("event argument must be finite");
            }
        }
        values = ownedValues;
    }

    public static EventArguments sync(float... values) {
        return checkedSync(values.clone());
    }

    public static EventArguments sync(FloatList values) {
        return checkedSync(Objects.requireNonNull(values).toFloatArray());
    }

    public static EventArguments deferredFloats(float... values) {
        return new EventArguments(values.clone());
    }

    public static EventArguments deferred(float delay, float[] payload) {
        var values = new float[Math.incrementExact(payload.length)];
        values[0] = delay;
        System.arraycopy(payload, 0, values, 1, payload.length);
        return new EventArguments(values);
    }

    private static EventArguments checkedSync(float[] values) {
        if (values.length > MolangEventCatalog.SYNC_MAX_ARGUMENTS)
            throw new IllegalArgumentException("sync arguments exceed protocol bound");
        return new EventArguments(values);
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
