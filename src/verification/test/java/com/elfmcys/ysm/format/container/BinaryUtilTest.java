package com.elfmcys.ysm.format.container;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BinaryUtilTest {
    @Test
    void consumesIndependentUnsignedLengthVectorsAtTheExactBoundary() throws Exception {
        for (var length : new int[] {0, 127, 128, 194, 255}) {
            var wire = new byte[length + 2];
            wire[0] = (byte)length;
            Arrays.fill(wire, 1, length + 1, (byte)'x');
            wire[length + 1] = 0x37;
            var input = new DataInputStream(new ByteArrayInputStream(wire));
            assertEquals("x".repeat(length), BinaryUtil.readShortString(input));
            assertEquals(0x37, input.readUnsignedByte());
            var output = new ByteArrayOutputStream();
            BinaryUtil.writeShortString(new DataOutputStream(output), "x".repeat(length));
            assertArrayEquals(Arrays.copyOf(wire, length + 1), output.toByteArray());
        }
    }

    @Test
    void rejectsMissingTruncatedNonAsciiAndNulPayloads() {
        for (var wire : new byte[][] {{}, {(byte)128}, {2, 'a'}, {1, 0}, {1, (byte)128}, {(byte)255, 'x'}}) {
            assertThrows(IOException.class,
                () -> BinaryUtil.readShortString(new DataInputStream(new ByteArrayInputStream(wire))));
        }
        for (var text : new String[] {"x".repeat(256), "a\0b", "名"}) {
            var output = new ByteArrayOutputStream();
            assertThrows(IllegalArgumentException.class,
                () -> BinaryUtil.writeShortString(new DataOutputStream(output), text));
            assertEquals(0, output.size());
        }
    }
}
