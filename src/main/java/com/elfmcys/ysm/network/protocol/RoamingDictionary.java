package com.elfmcys.ysm.network.protocol;

import dev.semaweave.RoamingPayload;
import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Owns one selection's ordered names. The caller transfers a stable, read-only name array. Module mappings belong to
 * the selection/installation relation and can be reused for every batch.
 */
public final class RoamingDictionary {
    private final String[] names;

    public RoamingDictionary(String[] names) {
        Objects.requireNonNull(names, "names");
        if (names.length > ProtocolLimits.MAX_ROAMING_VARIABLES)
            throw new IllegalArgumentException("Roaming dictionary exceeds 64 names");
        var ids = new Object2ShortOpenHashMap<String>(names.length);
        ids.defaultReturnValue((short)-1);
        for (short index = 0; index < names.length; index++) {
            var name = Objects.requireNonNull(names[index], "name");
            validateName(name);
            if (ids.put(name, index) != -1)
                throw new IllegalArgumentException("Duplicate roaming name");
        }
        this.names = names;
    }

    public static RoamingDictionary declarations(List<String> fields) {
        return new RoamingDictionary(fields.toArray(String[]::new));
    }

    public static void validateName(String name) {
        try {
            if (name.isEmpty() || StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(name)).remaining() > ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES)
                throw new IllegalArgumentException("Invalid roaming name");
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid roaming name", failure);
        }
    }

    public int size() {
        return names.length;
    }

    public String[] names() {
        return names;
    }

    /** The source MAIN layout must be the layout that established this dictionary. */
    public RoamingValues encode(RoamingPayload payload) {
        return new RoamingValues(payload.fields(), payload.values());
    }

    public Mapping mapTo(List<String> fields) {
        var wireToLocal = new short[names.length];
        for (int index = 0; index < names.length; index++) {
            wireToLocal[index] = (short)fields.indexOf(names[index]);
        }
        return new Mapping(wireToLocal);
    }

    public static final class Mapping {
        private final short[] wireToLocal;

        private Mapping(short[] wireToLocal) {
            this.wireToLocal = wireToLocal;
        }

        public RoamingPayload decode(RoamingValues payload) {
            int count = 0;
            for (short id : payload.ids()) {
                if (wireToLocal[id] >= 0)
                    count++;
            }
            var resultFields = new short[count];
            var resultValues = new float[count];
            int next = 0;
            for (int index = 0; index < payload.ids().length; index++) {
                short field = wireToLocal[payload.ids()[index]];
                if (field >= 0) {
                    resultFields[next] = field;
                    resultValues[next++] = payload.values()[index];
                }
            }
            return new RoamingPayload(resultFields, resultValues);
        }
    }
}
