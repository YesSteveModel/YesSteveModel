package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZstdTest {
    @Test
    void roundTripsArrayAndNativeBuffersIncludingEmptyPayload() {
        var large = new byte[1024 * 1024];
        for (var index = 0; index < large.length; index++) {
            large[index] = (byte)(index * 31);
        }

        for (var payload : new byte[][] {new byte[0], new byte[] {1, 2, 3, 4, 5}, large}) {
            for (var inputType : BufferType.values()) {
                for (var compressedType : BufferType.values()) {
                    for (var decodedType : BufferType.values()) {
                        assertRoundTrip(payload, inputType, compressedType, decodedType);
                    }
                }
            }
        }
    }

    @Test
    void rejectsInvalidCompressedDataThroughNullBoundaryValue() {
        var payload = new byte[4096];
        Arrays.fill(payload, (byte)7);
        var hash = new byte[Blake3.HASH_SIZE];
        byte[] compressedBytes;
        try (var source = buffer(payload, BufferType.ARRAY);
            var compressed = Zstd.compressAndHash(source, hash, BufferType.ARRAY, 16)) {
            compressedBytes = bytes(compressed);
        }

        var corruptFrame = compressedBytes.clone();
        corruptFrame[0] ^= 0x7f;
        try (var source = buffer(corruptFrame, BufferType.ARRAY)) {
            var error = assertThrows(IllegalStateException.class,
                () -> Zstd.decompressAndValidate(source, payload.length, hash, BufferType.ARRAY));
            assertEquals("Native zstd decompression returned no result", error.getMessage());
        }

        try (var source = buffer(compressedBytes, BufferType.NATIVE)) {
            var error = assertThrows(IllegalStateException.class,
                () -> Zstd.decompressAndValidate(source, payload.length + 1, hash, BufferType.NATIVE));
            assertEquals("Native zstd decompression returned no result", error.getMessage());
        }

        try (var source = buffer(compressedBytes, BufferType.ARRAY)) {
            var error = assertThrows(IllegalStateException.class,
                () -> Zstd.decompressAndValidate(source, payload.length - 1, hash, BufferType.ARRAY));
            assertEquals("Native zstd decompression returned no result", error.getMessage());
        }

        var incorrectHash = hash.clone();
        incorrectHash[0] ^= 0x7f;
        try (var source = buffer(compressedBytes, BufferType.ARRAY)) {
            var error = assertThrows(IllegalStateException.class,
                () -> Zstd.decompressAndValidate(source, payload.length, incorrectHash, BufferType.ARRAY));
            assertEquals("Native zstd decompression returned no result", error.getMessage());
        }
    }

    @Test
    void temporaryNativeOwnerSurvivesConcurrentGcDuringJniCall() throws Exception {
        var payload = new byte[4 * 1024 * 1024];
        for (var index = 0; index < payload.length; index++) {
            payload[index] = (byte)(index * 31);
        }
        var running = new AtomicBoolean(true);
        var gc = new Thread(() -> {
            while (running.get()) {
                System.gc();
                Thread.onSpinWait();
            }
        }, "zstd-owner-gc-pressure");
        gc.setDaemon(true);
        gc.start();
        try {
            for (var iteration = 0; iteration < 4; iteration++) {
                var hash = new byte[Blake3.HASH_SIZE];
                try (var compressed = compressTemporaryNative(payload, hash);
                    var decoded = Zstd.decompressAndValidate(compressed, payload.length, hash, BufferType.ARRAY)) {
                    assertArrayEquals(payload, bytes(decoded));
                }
            }
        } finally {
            running.set(false);
            gc.join();
        }
    }

    private static BufferStorage compressTemporaryNative(byte[] payload, byte[] hash) {
        return Zstd.compressAndHash(buffer(payload, BufferType.NATIVE), hash, BufferType.NATIVE, 16);
    }

    private static void assertRoundTrip(byte[] payload, BufferType inputType, BufferType compressedType,
        BufferType decodedType) {
        var hash = new byte[Blake3.HASH_SIZE];
        try (var source = buffer(payload, inputType);
            var compressed = Zstd.compressAndHash(source, hash, compressedType, 16);
            var decoded = Zstd.decompressAndValidate(compressed, payload.length, hash, decodedType)) {
            assertArrayEquals(Blake3.computeHash(source), hash);
            assertEquals(compressedType, compressed.type());
            assertEquals(decodedType, decoded.type());
            assertArrayEquals(payload, bytes(decoded));
        }
    }

    private static BufferStorage buffer(byte[] value, BufferType type) {
        var result = UniBuffer.allocate(value.length, type);
        result.nio().put(value);
        return result;
    }

    private static byte[] bytes(UniBuffer buffer) {
        var result = new byte[buffer.size()];
        buffer.nio().get(result);
        return result;
    }
}
