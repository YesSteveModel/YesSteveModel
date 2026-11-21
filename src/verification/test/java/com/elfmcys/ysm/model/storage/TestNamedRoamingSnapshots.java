package com.elfmcys.ysm.model.storage;

import java.util.Map;
import java.util.TreeMap;

public final class TestNamedRoamingSnapshots {
    private TestNamedRoamingSnapshots() {}

    public static NamedRoamingSnapshot fromMap(Map<String, Float> source) {
        var names = new String[source.size()];
        var values = new float[source.size()];
        int index = 0;
        for (var entry : source.entrySet()) {
            names[index] = entry.getKey();
            values[index++] = entry.getValue();
        }
        return new NamedRoamingSnapshot(names, values);
    }

    public static Map<String, Float> named(NamedRoamingSnapshot snapshot) {
        var result = new TreeMap<String, Float>();
        for (int index = 0; index < snapshot.names().length; index++)
            result.put(snapshot.names()[index], snapshot.values()[index]);
        return result;
    }
}
