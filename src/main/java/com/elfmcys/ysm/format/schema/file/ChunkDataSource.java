package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public interface ChunkDataSource {
    default ReadPhase openReadPhase() throws IOException {
        return new ReadPhase() {
            private volatile boolean closed;

            private void requireOpen() throws IOException {
                if (closed)
                    throw new java.nio.channels.ClosedChannelException();
            }

            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
                requireOpen();
                try {
                    return ChunkDataSource.this.readPayload(chunk, type);
                } catch (IOException | RuntimeException | Error failure) {
                    closed = true;
                    throw failure;
                }
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type)
                throws IOException {
                requireOpen();
                try {
                    return ChunkDataSource.this.readStoredVerified(chunk, type);
                } catch (IOException | RuntimeException | Error failure) {
                    closed = true;
                    throw failure;
                }
            }

            @Override
            public Map<String, UniBuffer> readPayloads(BooleanSupplier cancelled,
                Collection<AssetContainerView.ChunkInfo> chunks, BufferType type) throws IOException {
                requireOpen();
                try {
                    return ChunkDataSource.this.readPayloads(cancelled, chunks, type);
                } catch (IOException | RuntimeException | Error failure) {
                    closed = true;
                    throw failure;
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    interface ReadPhase extends ChunkDataSource, AutoCloseable {
        @Override
        default UniBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk, BufferType type)
            throws IOException {
            try {
                return ChunkDataSource.super.readPayload(cancelled, chunk, type);
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        void close() throws IOException;
    }

    /** Returns the decoded logical payload, or the stored bytes for direct media encodings. */
    UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException;

    /** Returns the exact stored representation after validating its logical content. */
    UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException;

    default UniBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk, BufferType bufferType)
        throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk read was cancelled");
        }
        var result = readPayload(chunk, bufferType);
        if (cancelled.getAsBoolean()) {
            result.close();
            throw new CancellationException("Chunk read was cancelled");
        }
        return result;
    }

    /** Returns one owned payload per chunk type, or releases the entire unpublished batch on failure. */
    default Map<String, UniBuffer> readPayloads(BooleanSupplier cancelled,
        Collection<AssetContainerView.ChunkInfo> chunks, BufferType bufferType) throws IOException {
        return collectPayloads(cancelled, chunks, chunk -> readPayload(cancelled, chunk, bufferType));
    }

    static Map<String, UniBuffer> collectPayloads(BooleanSupplier cancelled,
        Collection<AssetContainerView.ChunkInfo> chunks, PayloadReader reader) throws IOException {
        var descriptors = new LinkedHashMap<String, AssetContainerView.ChunkInfo>();
        for (var chunk : chunks) {
            var previous = descriptors.putIfAbsent(chunk.type(), chunk);
            if (previous != null && !sameChunk(previous, chunk)) {
                throw AssetLoadException.content("Conflicting chunk descriptors: " + chunk.type());
            }
        }
        var result = new LinkedHashMap<String, UniBuffer>();
        try {
            for (var chunk : descriptors.values()) {
                requireActive(cancelled);
                result.put(chunk.type(), reader.read(chunk));
            }
            requireActive(cancelled);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            result.values().forEach(UniBuffer::close);
            throw failure;
        }
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new CancellationException("Chunk batch read was cancelled");
    }

    private static boolean sameChunk(AssetContainerView.ChunkInfo a, AssetContainerView.ChunkInfo b) {
        return a.type().equals(b.type()) && a.encoding().equals(b.encoding()) && a.offset() == b.offset()
            && a.size() == b.size() && a.decodeSize() == b.decodeSize() && a.flags() == b.flags()
            && a.alignSize() == b.alignSize() && a.alignmentShift() == b.alignmentShift()
            && Arrays.equals(a.hash(), b.hash());
    }

    @FunctionalInterface
    interface PayloadReader {
        UniBuffer read(AssetContainerView.ChunkInfo chunk) throws IOException;
    }

    default byte[] readPayloadBytes(AssetContainerView.ChunkInfo chunk) throws IOException {
        try (var payload = readPayload(chunk, BufferType.ARRAY)) {
            return toByteArray(payload);
        }
    }

    default byte[] readStoredVerifiedBytes(AssetContainerView.ChunkInfo chunk) throws IOException {
        try (var stored = readStoredVerified(chunk, BufferType.ARRAY)) {
            return toByteArray(stored);
        }
    }

    default InputStream openPayloadStream(AssetContainerView.ChunkInfo chunk) throws IOException {
        return new ByteArrayInputStream(readPayloadBytes(chunk));
    }

    private static byte[] toByteArray(UniBuffer buffer) {
        var result = new byte[buffer.size()];
        buffer.nio().get(result);
        return result;
    }
}
