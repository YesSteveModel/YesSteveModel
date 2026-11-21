package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.natives.Blake3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class UnsignedPropertyVectorTest {
    @TempDir
    Path temporary;

    @Test
    void fullAndPreambleReadersAgreeOnHandEncodedPropertyBoundaries() throws Exception {
        for (var length : new int[] {0, 127, 128, 194, 255}) {
            var wire = vector(length);
            var expectedOffset = 149 + length;
            assertEquals((byte)length, wire[77]);
            var prefix = AssetContainerReader.readPreamble(wire);
            assertEquals("x".repeat(length), prefix.getSchemaProperty((short)2));
            assertEquals("end", prefix.getSchemaProperty((short)3));
            assertEquals(expectedOffset, prefix.getChunkInfo("verification").offset());
            var path = Files.write(temporary.resolve("vector-" + length), wire);
            try (var channel = FileChannel.open(path)) {
                assertTrue(prefix.sameContents(AssetContainerReader.read(channel)));
            }
        }
    }

    @Test
    void rejectsCrossRegionTruncationAndInvalidAsciiBeforeVerification() throws Exception {
        var missingLength = vector(194);
        ByteBuffer.wrap(missingLength).order(ByteOrder.LITTLE_ENDIAN).putInt(67, 2);
        reject(missingLength);
        var crossing = vector(194);
        ByteBuffer.wrap(crossing).order(ByteOrder.LITTLE_ENDIAN).putInt(67, 197);
        reject(crossing);
        var nul = vector(128);
        nul[78] = 0;
        reject(nul);
        var nonAscii = vector(255);
        nonAscii[78] = (byte)0x80;
        reject(nonAscii);
        reject(Arrays.copyOf(vector(194), 100));
    }

    private void reject(byte[] wire) throws Exception {
        assertThrows(IOException.class, () -> AssetContainerReader.readPreamble(wire));
        var path = Files.write(temporary.resolve("invalid"), wire);
        try (var channel = FileChannel.open(path)) {
            assertThrows(IOException.class, () -> AssetContainerReader.read(channel));
        }
    }

    // This fixture writes the format directly, independent of AssetContainerWriter and BinaryUtil.
    private static byte[] vector(int length) throws IOException {
        var dataOffset = 149 + length;
        var wire = ByteBuffer.allocate(dataOffset + 34).order(ByteOrder.LITTLE_ENDIAN);
        wire.put(new byte[] {(byte)0xef, (byte)0xbb, (byte)0xbf, 'Y', 'S', 'G', 'P', '2', '\n', '\n', 0});
        wire.putShort((short)64).put((byte)0).putShort((short)1).putShort((short)1);
        fixed(wire, "unstable", 13);
        fixed(wire, "test", 32);
        wire.putShort((short)2).putShort((short)1).putInt(9 + length).putInt(65);
        wire.putShort((short)2).put((byte)length);
        wire.put("x".repeat(length).getBytes(StandardCharsets.US_ASCII));
        wire.putShort((short)3).put((byte)3).put(new byte[] {'e', 'n', 'd'});
        wire.put((byte)12).put("verification".getBytes(StandardCharsets.US_ASCII));
        wire.put((byte)6).put("BLAKE3".getBytes(StandardCharsets.US_ASCII));
        wire.putInt(34).putInt(34).putInt(0).put((byte)0).put(new byte[32]);
        assertEquals(dataOffset, wire.position());
        wire.put(Blake3.computeHash(ArrayBuffer.borrow(wire.array(), 0, dataOffset))).putShort((short)0);
        return wire.array();
    }

    private static void fixed(ByteBuffer output, String value, int width) {
        output.put(value.getBytes(StandardCharsets.US_ASCII));
        output.put(new byte[width - value.length()]);
    }
}
