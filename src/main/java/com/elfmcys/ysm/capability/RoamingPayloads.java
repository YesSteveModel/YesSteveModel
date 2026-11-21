package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.network.protocol.ProtocolLimits;
import com.elfmcys.ysm.util.StringPool;
import dev.semaweave.RoamingPayload;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Adapts the StringPool wire format used by projectiles and vehicles. Inbound owners validate the complete map before
 * retaining or mapping it; buffered maps stay read-only. Outbound values are finite module exports, and each caller
 * supplies the exact module layout that owns the local field ids.
 */
final class RoamingPayloads {
    private RoamingPayloads() {}

    static void validateNetwork(Int2FloatMap values) {
        if (values.size() > ProtocolLimits.MAX_ROAMING_VARIABLES) {
            throw new IllegalArgumentException("roaming field count exceeds wire capacity");
        }
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
        }
    }

    static RoamingPayload fromNetwork(Int2FloatMap values, List<String> targetFields) {
        int count = 0;
        for (var entry : values.int2FloatEntrySet()) {
            if (targetFields.contains(StringPool.getString(entry.getIntKey())))
                count++;
        }
        var fields = new short[count];
        var numbers = new float[count];
        int next = 0;
        for (var entry : values.int2FloatEntrySet()) {
            int field = targetFields.indexOf(StringPool.getString(entry.getIntKey()));
            if (field >= 0) {
                fields[next] = (short)field;
                numbers[next++] = entry.getFloatValue();
            }
        }
        return new RoamingPayload(fields, numbers);
    }

    static Int2FloatOpenHashMap toNetwork(RoamingPayload payload, List<String> sourceFields) {
        var result = new Int2FloatOpenHashMap(payload.values().length);
        for (int index = 0; index < payload.fields().length; index++) {
            var name = sourceFields.get(payload.fields()[index]);
            result.put(StringPool.computeIfAbsent(name), payload.values()[index]);
        }
        return result;
    }
}
