package com.elfmcys.ysm.network.frame;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameContractTest {
    @Test
    void simpleFrameUsesOneByteTagAndStrictBoundary() {
        try (var frame = FrameCodec.encode(7, new byte[] {1, 2, 3}, null)) {
            assertArrayEquals(new byte[] {14, 1, 2, 3}, frame.bytes());
            try (var decoded = FrameCodec.decode(frame.bytes(), id -> id == 7)) {
                assertEquals(7, decoded.messageId());
                assertArrayEquals(new byte[] {1, 2, 3}, bytes(decoded.protobuf()));
                assertFalse(decoded.compressed());
            }
        }
        var incompressible = new byte[FrameCodec.MAX_BODY_BYTES];
        new Random(1).nextBytes(incompressible);
        assertThrows(IllegalArgumentException.class, () -> FrameCodec.encode(1, incompressible, null));
    }

    @Test
    void fullHeaderIsLittleEndianAndBodyMustMatchExactly() {
        try (var frame = FrameCodec.encode(3, new byte[] {5, 6, 7}, new byte[] {8, 9})) {
            var wire = frame.bytes();
            assertEquals(7, Byte.toUnsignedInt(wire[0]));
            assertEquals(0, Byte.toUnsignedInt(wire[1]));
            assertArrayEquals(new byte[] {3, 0, 0, 0}, Arrays.copyOfRange(wire, 2, 6));
            assertArrayEquals(new byte[] {3, 0, 0, 0}, Arrays.copyOfRange(wire, 6, 10));
            assertArrayEquals(new byte[] {2, 0, 0, 0}, Arrays.copyOfRange(wire, 10, 14));
            try (var decoded = FrameCodec.decode(wire, id -> id == 3)) {
                assertArrayEquals(new byte[] {5, 6, 7}, bytes(decoded.protobuf()));
                assertArrayEquals(new byte[] {8, 9}, bytes(decoded.attachment()));
            }
            wire[1] = 2;
            assertThrows(IllegalArgumentException.class, () -> FrameCodec.decode(wire, id -> true));
        }
    }

    @Test
    void compressionIsChosenOnlyWhenStrictlySmallerAndRejectsTrailingBytes() {
        var protobuf = new byte[4096];
        Arrays.fill(protobuf, (byte)42);
        try (var frame = FrameCodec.encode(2, protobuf, new byte[] {1});
            var decoded = FrameCodec.decode(frame.bytes(), id -> id == 2)) {
            assertTrue(decoded.compressed());
            assertArrayEquals(protobuf, bytes(decoded.protobuf()));

            var trailing = Arrays.copyOf(frame.bytes(), frame.size() + 1);
            assertThrows(IllegalArgumentException.class, () -> FrameCodec.decode(trailing, id -> true));
        }
    }

    @Test
    void rejectsReservedIdsAndDecodedPayloadAboveOneMiB() {
        try (var frame = FrameCodec.encode(8, new byte[] {1}, null)) {
            assertThrows(IllegalArgumentException.class, () -> FrameCodec.decode(frame.bytes(), id -> false));
        }
        assertThrows(IllegalArgumentException.class,
            () -> FrameCodec.encode(0, new byte[FrameCodec.MAX_DECODED_PROTO_BYTES + 1], new byte[0]));
    }

    @Test
    void sharedInputSlicesSurviveFrameCloseWithoutCopying() {
        try (var encoded = FrameCodec.encode(3, new byte[] {5, 6, 7}, new byte[] {8, 9})) {
            var input = SharedArrayBuffer.adopt(ArrayBuffer.move(encoded.bytes()));
            var decoded = FrameCodec.decode(input, id -> id == 3);
            var protobuf = (SharedArrayBuffer)decoded.protobuf();
            var attachment = (SharedArrayBuffer)decoded.attachment();
            assertSame(input.array(), protobuf.array());
            assertSame(input.array(), attachment.array());
            decoded.close();
            assertArrayEquals(new byte[] {5, 6, 7}, bytes(protobuf));
            assertArrayEquals(new byte[] {8, 9}, bytes(attachment));
        }
    }

    @Test
    void borrowedInputIsIsolatedBeforeReturningDecodedSlices() {
        var input = new byte[] {14, 1, 2, 3};
        try (var decoded = FrameCodec.decode(input, id -> id == 7)) {
            Arrays.fill(input, (byte)0);
            assertArrayEquals(new byte[] {1, 2, 3}, bytes(decoded.protobuf()));
        }
    }

    private static byte[] bytes(UniBuffer buffer) {
        try (var array = buffer.copyArray()) {
            return Arrays.copyOfRange(array.array(), array.arrayOffset(), array.arrayOffset() + array.size());
        }
    }
}
