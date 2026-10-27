package com.elfmcys.ysm.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.elfmcys.ysm.util.StringPool;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import dev.semaweave.RoamingPayload;

final class RoamingPayloadsTest {
    @Test
    void networkAdapterPreservesTargetDeclaredNames() {
        int key = StringPool.computeIfAbsent("custom_state");
        var network = new Int2FloatOpenHashMap();
        network.put(key, 5.0f);

        var payload = RoamingPayloads.fromNetwork(network);

        assertEquals(Map.of("custom_state", 5.0f), payload.values());
        assertEquals(5.0f, RoamingPayloads.toNetwork(payload).get(key));
    }

    @Test
    void networkAdapterRejectsPayloadsOutsideWireCapacity() {
        var tooMany = new TreeMap<String, Float>();
        for (int index = 0; index <= 64; index++) {
            tooMany.put("r" + index, 1.0f);
        }
        assertThrows(IllegalArgumentException.class, () -> RoamingPayloads.toNetwork(new RoamingPayload(tooMany)));

        var longName = "r".repeat(33);
        assertThrows(IllegalArgumentException.class,
            () -> RoamingPayloads.toNetwork(new RoamingPayload(new TreeMap<>(Map.of(longName, 1.0f)))));
    }
}
