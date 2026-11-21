package com.elfmcys.ysm.expression;

import dev.semaweave.RoamingPayload;
import dev.semaweave.CompiledRoamingState;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Named fixture values for assertions that do not depend on array order. */
public final class TestRoamingPayloads {
    private TestRoamingPayloads() {}

    public static RoamingPayload empty() {
        return new RoamingPayload(new short[0], new float[0]);
    }

    public static RoamingPayload fromMap(Map<String, Float> source, List<String> fieldNames) {
        var fields = new short[source.size()];
        var values = new float[source.size()];
        int index = 0;
        for (var entry : source.entrySet()) {
            int field = fieldNames.indexOf(entry.getKey());
            if (field < 0)
                throw new IllegalArgumentException("fixture field is not declared: " + entry.getKey());
            fields[index] = (short)field;
            values[index++] = entry.getValue();
        }
        return new RoamingPayload(fields, values);
    }

    public static Map<String, Float> named(RoamingPayload payload, List<String> fieldNames) {
        var result = new TreeMap<String, Float>();
        for (int index = 0; index < payload.fields().length; index++)
            result.put(fieldNames.get(payload.fields()[index]), payload.values()[index]);
        return result;
    }

    public static Map<String, Float> named(CompiledRoamingState state) {
        return named(new com.elfmcys.ysm.expression.host.RoamingState.Aot(state));
    }

    public static Map<String, Float> named(com.elfmcys.ysm.expression.host.RoamingState state) {
        var result = new TreeMap<String, Float>();
        for (int field = 0; field < state.fieldNames().size(); field++)
            result.put(state.fieldNames().get(field), state.read((short)field));
        return result;
    }

    public static Map<String, Float> named(com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Snapshot snapshot) {
        return named(snapshot.payload(), snapshot.installation().roamingFields());
    }
}
