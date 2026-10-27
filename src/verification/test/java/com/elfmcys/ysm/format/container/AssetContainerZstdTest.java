package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetContainerZstdTest {
    @Test
    void compressedChunksRoundTripThroughContainerReader() throws Exception {
        var payloads = payloads();
        var container = writeContainer(payloads);

        try (var channel = new ByteArraySeekableChannel(container)) {
            var view = AssetContainerReader.read(channel);
            for (var entry : payloads.entrySet()) {
                var chunk = view.getChunkInfo(entry.getKey());
                for (var outputType : BufferType.values()) {
                    try (var decoded = InlineChunkReader.readPayload(channel, chunk, outputType)) {
                        assertArrayEquals(entry.getValue(), bytes(decoded));
                    }
                }
                assertArrayEquals(entry.getValue(), InlineChunkReader.readPayloadBytes(channel, chunk));
            }
        }
    }

    @Test
    void storedZstdReadsReturnOriginalFrameAfterDecodedContentValidation() throws Exception {
        var payload = new byte[4096];
        Arrays.fill(payload, (byte)7);
        var container = writeContainer(Map.of("definition", payload));

        try (var channel = new ByteArraySeekableChannel(container)) {
            var chunk = AssetContainerReader.read(channel).getChunkInfo("definition");
            var expectedFrame = Arrays.copyOfRange(container, chunk.offset(), chunk.offset() + chunk.size());
            assertTrue(expectedFrame.length < payload.length);
            for (var outputType : BufferType.values()) {
                try (var stored = InlineChunkReader.readStoredVerified(channel, chunk, outputType)) {
                    assertArrayEquals(expectedFrame, bytes(stored));
                }
            }
            assertArrayEquals(expectedFrame, InlineChunkReader.readStoredVerifiedBytes(channel, chunk));

            var wrongHash = chunk.hash().clone();
            wrongHash[0] ^= 1;
            var invalid = new AssetContainerView.ChunkInfo(chunk.type(), chunk.encoding(), chunk.offset(), chunk.size(),
                chunk.decodeSize(), chunk.flags(), chunk.alignSize(), chunk.alignmentShift(), wrongHash);
            assertThrows(IOException.class,
                () -> InlineChunkReader.readStoredVerified(channel, invalid, BufferType.ARRAY));
        }
    }

    @Test
    void mediaEncodingUsesStoredBytesAsItsDirectPayload() throws Exception {
        var payload = new byte[] {10, 20, 30, 40};
        var output = new ByteArrayOutputStream();
        try (var writer = new AssetContainerWriter(); var channel = Channels.newChannel(output);
            var data = ArrayBuffer.move(payload.clone())) {
            writer.setSchema("test/media");
            writer.addChunk("image", "png", (1 << 16) | 1, 0, 0, data, 0);
            writer.write(channel);
        }

        try (var channel = new ByteArraySeekableChannel(output.toByteArray())) {
            var chunk = AssetContainerReader.read(channel).getChunkInfo("image");
            assertArrayEquals(payload, InlineChunkReader.readPayloadBytes(channel, chunk));
            assertArrayEquals(payload, InlineChunkReader.readStoredVerifiedBytes(channel, chunk));
        }
    }

    @Test
    void corruptChunkReportsTypeSizesAndNativeBoundaryFailure() throws Exception {
        var container = writeContainer(Map.of("definition", new byte[] {1, 2, 3, 4}));
        AssetContainerView.ChunkInfo chunk;
        try (var channel = new ByteArraySeekableChannel(container)) {
            chunk = AssetContainerReader.read(channel).getChunkInfo("definition");
        }
        container[chunk.offset()] ^= 0x7f;

        try (var channel = new ByteArraySeekableChannel(container)) {
            var error =
                assertThrows(IOException.class, () -> InlineChunkReader.readPayload(channel, chunk, BufferType.ARRAY));
            assertTrue(error.getMessage().contains("type=definition"));
            assertTrue(error.getMessage().contains("encoding=zstd"));
            assertTrue(error.getMessage().contains("encodedSize=" + chunk.size()));
            assertTrue(error.getMessage().contains("decodedSize=" + chunk.decodeSize()));
            assertTrue(error.getMessage().contains("nativeStatus=Native zstd decompression returned no result"));
        }
    }

    private static Map<String, byte[]> payloads() {
        var large = new byte[1024 * 1024];
        for (var index = 0; index < large.length; index++) {
            large[index] = (byte)(index * 17);
        }
        var payloads = new LinkedHashMap<String, byte[]>();
        payloads.put("empty", new byte[0]);
        payloads.put("small", new byte[] {1, 2, 3, 4, 5});
        payloads.put("large", large);
        return payloads;
    }

    private static byte[] writeContainer(Map<String, byte[]> payloads) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var writer = new AssetContainerWriter(); var channel = Channels.newChannel(output)) {
            writer.setSchema("test/zstd");
            for (var entry : payloads.entrySet()) {
                try (var payload = ArrayBuffer.move(entry.getValue().clone())) {
                    writer.addChunk(entry.getKey(), "", 0, 0, 0, payload, 16);
                }
            }
            writer.write(channel);
        }
        return output.toByteArray();
    }

    private static byte[] bytes(UniBuffer buffer) {
        var result = new byte[buffer.size()];
        buffer.nio().get(result);
        return result;
    }
}
