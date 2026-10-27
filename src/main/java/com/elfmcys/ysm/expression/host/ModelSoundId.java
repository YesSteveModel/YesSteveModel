package com.elfmcys.ysm.expression.host;

import java.util.Objects;

/** Immutable sound key whose numeric and string namespaces never alias. */
public sealed interface ModelSoundId permits ModelSoundId.Numeric, ModelSoundId.StringKey {
    static ModelSoundId numeric(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("numeric sound id must be finite");
        }
        return new Numeric((int)value);
    }

    static ModelSoundId string(String value) {
        return new StringKey(value);
    }

    boolean playable();

    record Numeric(int value) implements ModelSoundId {
        @Override
        public boolean playable() {
            return value >= 0;
        }
    }

    record StringKey(String value) implements ModelSoundId {
        public StringKey {
            value = Objects.requireNonNull(value, "value");
            requireUnicode(value);
        }

        @Override
        public boolean playable() {
            return true;
        }
    }

    private static void requireUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index < value.length() && Character.isLowSurrogate(value.charAt(index)))
                    continue;
                throw new IllegalArgumentException("sound id contains malformed UTF-16");
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("sound id contains malformed UTF-16");
            }
        }
    }
}
