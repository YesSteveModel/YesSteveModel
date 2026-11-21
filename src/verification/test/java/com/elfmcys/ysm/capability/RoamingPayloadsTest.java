package com.elfmcys.ysm.capability;

import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.elfmcys.ysm.util.StringPool;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RoamingPayloadsTest {
    @Test
    void networkAdapterUsesTheSourceAndTargetLayouts() {
        int key = StringPool.computeIfAbsent("custom_state");
        var network = new Int2FloatOpenHashMap();
        network.put(key, 5.0f);
        var fields = List.of("other", "custom_state");

        RoamingPayloads.validateNetwork(network);
        var payload = RoamingPayloads.fromNetwork(network, fields);

        assertEquals(Map.of("custom_state", 5.0f), named(payload, fields));
        assertEquals(5.0f, RoamingPayloads.toNetwork(payload, fields).get(key));
    }

    @Test
    void ingressRejectsInvalidValuesBeforeFilteringUnknownNames() {
        var network = new Int2FloatOpenHashMap();
        network.put(StringPool.computeIfAbsent("known"), 5.0f);
        network.put(StringPool.computeIfAbsent("unknown"), Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> RoamingPayloads.validateNetwork(network));
    }

    @Test
    void ingressRejectsNamesAndCountsOutsideWireCapacity() {
        var network = new Int2FloatOpenHashMap();
        for (int index = 0; index <= 64; index++)
            network.put(StringPool.computeIfAbsent("r" + index), 1.0f);
        assertThrows(IllegalArgumentException.class, () -> RoamingPayloads.validateNetwork(network));

        network.clear();
        network.put(StringPool.computeIfAbsent("r".repeat(33)), 1.0f);
        assertThrows(IllegalArgumentException.class, () -> RoamingPayloads.validateNetwork(network));
    }
}
