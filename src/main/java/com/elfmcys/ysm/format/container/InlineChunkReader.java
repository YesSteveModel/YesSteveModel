package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/** Reads and validates inline payloads described by an AssetContainerView. */
public final class InlineChunkReader {
    private InlineChunkReader() {}

    public static BufferStorage readPayload(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk,
        BufferType bufferType) throws IOException {
        if (!ChunkDecoding.isZstd(chunk)) {
            return readStoredVerified(channel, chunk, bufferType);
        }
        try (var stored = readStored(channel, chunk, BufferType.NATIVE)) {
            return ChunkDecoding.decodeZstd(stored, chunk, bufferType);
        }
    }

    public static byte[] readPayloadBytes(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk)
        throws IOException {
        try (var payload = readPayload(channel, chunk, BufferType.ARRAY)) {
            return toByteArray(payload);
        }
    }

    public static BufferStorage readStoredVerified(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk,
        BufferType bufferType) throws IOException {
        var readType = ChunkDecoding.isZstd(chunk) ? BufferType.NATIVE : bufferType;
        try (var scope = com.elfmcys.ysm.util.ScopeGuard.create(readStored(channel, chunk, readType))) {
            var stored = scope.get();
            ChunkDecoding.validateStored(stored, chunk);
            if (stored.type() == bufferType)
                return scope.release();
            return bufferType == BufferType.NATIVE ? stored.copyNative() : stored.copyArray();
        }
    }

    public static byte[] readStoredVerifiedBytes(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk)
        throws IOException {
        try (var stored = readStoredVerified(channel, chunk, BufferType.ARRAY)) {
            return toByteArray(stored);
        }
    }

    private static BufferStorage readStored(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk,
        BufferType bufferType) throws IOException {
        validate(channel, chunk);
        try (var scope = UniBuffer.allocateWithScope(chunk.size(), bufferType)) {
            readFully(channel, chunk.offset(), scope.get().nio());
            return scope.release();
        }
    }

    private static byte[] toByteArray(UniBuffer buffer) {
        var result = new byte[buffer.size()];
        buffer.nio().get(result);
        return result;
    }

    private static void validate(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk) throws IOException {
        if ((long)chunk.offset() + chunk.size() > channel.size()) {
            throw AssetLoadException.content("Inline chunk data overflow");
        }
        if (chunk.alignSize() == 0) {
            return;
        }
        var remaining = chunk.alignSize();
        var position = chunk.offset() - remaining;
        var scratch = ByteBuffer.allocate(Math.min(remaining, 256));
        while (remaining > 0) {
            var length = Math.min(remaining, scratch.capacity());
            scratch.clear().limit(length);
            readFully(channel, position, scratch);
            scratch.flip();
            while (scratch.hasRemaining()) {
                if (scratch.get() != 0) {
                    throw AssetLoadException.content("Illegal chunk align data");
                }
            }
            position += length;
            remaining -= length;
        }
    }

    private static void readFully(SeekableByteChannel channel, long offset, ByteBuffer target) throws IOException {
        channel.position(offset);
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw AssetLoadException.content("Unexpected end of chunk data");
            }
        }
    }
}
