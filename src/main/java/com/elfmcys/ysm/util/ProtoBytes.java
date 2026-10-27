package com.elfmcys.ysm.util;

import com.elfmcys.ysm.model.domain.Hash256;
import java.nio.ByteBuffer;

public final class ProtoBytes {
    private ProtoBytes() {}

    public static byte[] copy(ByteBuffer bytes) {
        var source = bytes.duplicate();
        var result = new byte[source.remaining()];
        source.get(result);
        return result;
    }

    public static boolean equals(byte[] expected, ByteBuffer actual) {
        if (expected.length != actual.remaining()) {
            return false;
        }
        var source = actual.duplicate();
        for (var value : expected) {
            if (value != source.get()) {
                return false;
            }
        }
        return true;
    }

    public static boolean equals(Hash256 expected, ByteBuffer actual) {
        return expected.matches(copy(actual));
    }

    public static ByteBuffer wrap(Hash256 source) {
        return ByteBuffer.wrap(source.bytes());
    }
}
