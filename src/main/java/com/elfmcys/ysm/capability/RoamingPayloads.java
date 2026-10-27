package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.network.protocol.ProtocolLimits;
import com.elfmcys.ysm.util.StringPool;
import dev.semaweave.RoamingPayload;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

final class RoamingPayloads {
    private RoamingPayloads() {}

    static RoamingPayload fromNetwork(Int2FloatMap values) {
        if (values.size() > ProtocolLimits.MAX_ROAMING_VARIABLES) {
            throw new IllegalArgumentException("roaming field count exceeds wire capacity");
        }
        var result = new TreeMap<String, Float>();
        for (var entry : values.int2FloatEntrySet()) {
            var name = StringPool.getString(entry.getIntKey());
            if (name == null || name.isBlank()
                || name.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES) {
                throw new IllegalArgumentException("invalid roaming field name");
            }
            var value = entry.getFloatValue();
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("invalid roaming field value");
            }
            result.put(name, value);
        }
        return new RoamingPayload(result);
    }

    static Int2FloatOpenHashMap toNetwork(RoamingPayload payload) {
        if (payload.values().size() > ProtocolLimits.MAX_ROAMING_VARIABLES) {
            throw new IllegalArgumentException("roaming field count exceeds wire capacity");
        }
        var result = new Int2FloatOpenHashMap(payload.values().size());
        payload.values().forEach((name, value) -> {
            if (name.isBlank()
                || name.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES
                || !Float.isFinite(value.floatValue())) {
                throw new IllegalArgumentException("invalid roaming field for wire delivery");
            }
            result.put(StringPool.computeIfAbsent(name), value.floatValue());
        });
        return result;
    }
}
