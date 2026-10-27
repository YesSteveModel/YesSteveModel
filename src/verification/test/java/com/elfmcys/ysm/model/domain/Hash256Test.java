package com.elfmcys.ysm.model.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hash256Test {
    @Test
    void preservesBytesHexAndArrayHashCode() {
        var source = bytes(0);
        var expectedHashCode = Arrays.hashCode(source);
        var value = new Hash256(source);

        source[0] = 99;
        assertArrayEquals(bytes(0), value.bytes());
        assertEquals(expectedHashCode, value.hashCode());
        assertEquals(value, Hash256.parse(value.toString()));

        var returned = value.bytes();
        returned[1] = 99;
        assertArrayEquals(bytes(0), value.bytes());
    }

    @Test
    void offsetConstructorAndMatchesKeepTheirExistingSemantics() {
        var expected = bytes(32);
        var source = new byte[Hash256.SIZE + 8];
        System.arraycopy(expected, 0, source, 4, expected.length);
        var value = new Hash256(source, 4, Hash256.SIZE);

        assertArrayEquals(expected, value.bytes());
        assertTrue(value.matches(source, 4, Hash256.SIZE));
        assertTrue(value.matches(expected));
        assertFalse(value.matches(source, 3, Hash256.SIZE));
        assertFalse(value.matches(source, -1, Hash256.SIZE));
        assertFalse(value.matches(source, 4, Hash256.SIZE - 1));
    }

    @Test
    void comparisonIsUnsignedAcrossAllFourWords() {
        for (var word = 0; word < 4; word++) {
            var lower = new byte[Hash256.SIZE];
            var higher = new byte[Hash256.SIZE];
            lower[word * Long.BYTES] = 0x7f;
            higher[word * Long.BYTES] = (byte)0x80;

            assertTrue(new Hash256(lower).compareTo(new Hash256(higher)) < 0);
            assertTrue(new Hash256(higher).compareTo(new Hash256(lower)) > 0);
        }
    }

    @Test
    void roamingHashUsesTheFirstFourBytesInBigEndianOrder() {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)0x89;
        bytes[1] = (byte)0xab;
        bytes[2] = (byte)0xcd;
        bytes[3] = (byte)0xef;

        assertEquals(0x89abcdef, new Hash256(bytes).roamingHash());
    }

    private static byte[] bytes(int start) {
        var result = new byte[Hash256.SIZE];
        for (var index = 0; index < result.length; index++) {
            result[index] = (byte)(start + index);
        }
        return result;
    }
}
