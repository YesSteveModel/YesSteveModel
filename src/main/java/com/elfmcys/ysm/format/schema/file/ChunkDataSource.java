package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.SharedBuffer;
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

/**
 * Acquires and validates bytes for descriptors of one content representation.
 *
 * <p>Requires: descriptors come from the matching validated container, and the content owner keeps
 * this source usable for the read. Descriptor validation does not prove that its bytes are available.
 *
 * <p>Ownership: returned shared buffers retain their backing independently and must not be modified.
 * Source handles and read phases have separate close obligations; a metadata view does not own them.
 *
 * <p>Failure: access, content, and cancellation failures remain distinct. A failed read is not an
 * empty payload and does not authorize deleting stored content. Concrete sources define their admission
 * and concurrent-use rules; implementations must not expose unverified bytes through a verified read.
 *
 * <p>Seam: implements [per-chunk validation](docs/architecture/asset-pipeline/overview.md#分层验证与发布) after metadata admission.
 */
public interface ChunkDataSource {
    /**
     * Opens a caller-owned lexical read phase. Close it after the last read; phase closure does not
     * revoke already returned shared buffers. A failed phase must not be reused for another read.
     */
    default ReadPhase openReadPhase() throws IOException {
        return new ReadPhase() {
            private volatile boolean closed;

            private void requireOpen() throws IOException {
                if (closed)
                    throw new java.nio.channels.ClosedChannelException();
            }

            @Override
            public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
                requireOpen();
                try {
                    return ChunkDataSource.this.readPayload(chunk, type);
                } catch (IOException | RuntimeException | Error failure) {
                    closed = true;
                    throw failure;
                }
            }

            @Override
            public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type)
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
            public Map<String, SharedBuffer> readPayloads(BooleanSupplier cancelled,
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
        default SharedBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk, BufferType type)
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

    /**
     * Returns non-null shared decoded or direct media bytes after size/hash checks for this descriptor.
     * Media payload validity still belongs to its decoder; consumers do not mutate the returned bytes.
     */
    SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException;

    /** Returns the validated stored representation, retaining its encoding; consumers do not mutate shared bytes. */
    SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException;

    /**
     * Checks cancellation before and after the synchronous read; it does not promise to interrupt I/O.
     *
     * @throws CancellationException if cancellation is observed, including after bytes have been read
     */
    default SharedBuffer readPayload(BooleanSupplier cancelled, AssetContainerView.ChunkInfo chunk,
        BufferType bufferType) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk read was cancelled");
        }
        var result = readPayload(chunk, bufferType);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk read was cancelled");
        }
        return result;
    }

    /**
     * Returns one shared payload per chunk type; equivalent duplicates coalesce and conflicting
     * descriptors fail. No partial map is returned on failure. The map belongs to the caller and its
     * buffer values remain shared and read-only by contract.
     */
    default Map<String, SharedBuffer> readPayloads(BooleanSupplier cancelled,
        Collection<AssetContainerView.ChunkInfo> chunks, BufferType bufferType) throws IOException {
        return collectPayloads(cancelled, chunks, chunk -> readPayload(cancelled, chunk, bufferType));
    }

    static Map<String, SharedBuffer> collectPayloads(BooleanSupplier cancelled,
        Collection<AssetContainerView.ChunkInfo> chunks, PayloadReader reader) throws IOException {
        var descriptors = new LinkedHashMap<String, AssetContainerView.ChunkInfo>();
        for (var chunk : chunks) {
            var previous = descriptors.putIfAbsent(chunk.type(), chunk);
            if (previous != null && !sameChunk(previous, chunk)) {
                throw AssetLoadException.content("Conflicting chunk descriptors: " + chunk.type());
            }
        }
        var result = new LinkedHashMap<String, SharedBuffer>();
        for (var chunk : descriptors.values()) {
            requireActive(cancelled);
            result.put(chunk.type(), reader.read(chunk));
        }
        requireActive(cancelled);
        return result;
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
        SharedBuffer read(AssetContainerView.ChunkInfo chunk) throws IOException;
    }

    default byte[] readPayloadBytes(AssetContainerView.ChunkInfo chunk) throws IOException {
        return toByteArray(readPayload(chunk, BufferType.ARRAY));
    }

    default byte[] readStoredVerifiedBytes(AssetContainerView.ChunkInfo chunk) throws IOException {
        return toByteArray(readStoredVerified(chunk, BufferType.ARRAY));
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
