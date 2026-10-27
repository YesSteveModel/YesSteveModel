package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Long-lived capability; only a bounded read phase owns its channel. */
public final class FileChunkDataSource implements ChunkDataSource {
    private final Path file;
    private final ChannelOpener opener;
    private final long expectedFileSize;

    public FileChunkDataSource(Path file) {
        this(file, -1);
    }

    public FileChunkDataSource(Path file, long expectedFileSize) {
        this(file, expectedFileSize, () -> FileChannel.open(file, StandardOpenOption.READ));
    }

    public FileChunkDataSource(Path file, long expectedFileSize, ChannelOpener opener) {
        this.opener = java.util.Objects.requireNonNull(opener);
        if (expectedFileSize < -1) {
            throw new IllegalArgumentException("Expected file size must not be less than -1");
        }
        this.file = file.toAbsolutePath().normalize();
        this.expectedFileSize = expectedFileSize;
    }

    @Override
    public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        return read(chunk, bufferType, InlineChunkReader::readPayload);
    }

    @Override
    public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        return read(chunk, bufferType, InlineChunkReader::readStoredVerified);
    }

    @Override
    public Map<String, UniBuffer> readPayloads(BooleanSupplier cancelled,
        Collection<AssetContainerView.ChunkInfo> chunks, BufferType bufferType) throws IOException {
        if (chunks.isEmpty())
            return ChunkDataSource.super.readPayloads(cancelled, chunks, bufferType);
        Map<String, UniBuffer> result = null;
        try (var phase = openReadPhase()) {
            result = phase.readPayloads(cancelled, chunks, bufferType);
        } catch (IOException | RuntimeException | Error failure) {
            if (result != null)
                result.values().forEach(UniBuffer::close);
            if (failure instanceof AssetLoadException asset)
                throw asset;
            if (failure instanceof IOException || failure instanceof SecurityException)
                throw AssetLoadException.access("Failed to read model chunk batch: " + file, failure);
            throw failure;
        }
        return result;
    }

    private UniBuffer read(AssetContainerView.ChunkInfo chunk, BufferType bufferType, ChannelRead operation)
        throws IOException {
        UniBuffer result = null;
        try (var phase = ChannelReadPhase.open(opener, this::verifyExpectedSize)) {
            result = phase.read(channel -> operation.read(channel, chunk, bufferType), UniBuffer::close);
        } catch (AssetLoadException error) {
            close(result);
            throw error;
        } catch (IOException | SecurityException error) {
            close(result);
            throw readFailure(chunk, error);
        } catch (RuntimeException | Error error) {
            close(result);
            throw error;
        }
        return result;
    }

    @Override
    public ReadPhase openReadPhase() throws IOException {
        final ChannelReadPhase phase;
        try {
            phase = ChannelReadPhase.open(opener, this::verifyExpectedSize);
        } catch (AssetLoadException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw AssetLoadException.access("Failed to open model read phase: " + file, failure);
        }
        try {
            return new ReadPhase() {
                @Override
                public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
                    return read(chunk, type, InlineChunkReader::readPayload);
                }

                @Override
                public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type)
                    throws IOException {
                    return read(chunk, type, InlineChunkReader::readStoredVerified);
                }

                private UniBuffer read(AssetContainerView.ChunkInfo chunk, BufferType type, ChannelRead reader)
                    throws IOException {
                    try {
                        return phase.read(channel -> reader.read(channel, chunk, type), UniBuffer::close);
                    } catch (AssetLoadException failure) {
                        throw failure;
                    } catch (IOException | SecurityException failure) {
                        throw readFailure(chunk, failure);
                    }
                }

                @Override
                public Map<String, UniBuffer> readPayloads(BooleanSupplier cancelled,
                    Collection<AssetContainerView.ChunkInfo> chunks, BufferType type) throws IOException {
                    return phase.read(
                        channel -> ChunkDataSource.collectPayloads(cancelled, chunks,
                            chunk -> InlineChunkReader.readPayload(channel, chunk, type)),
                        values -> values.values().forEach(UniBuffer::close));
                }

                @Override
                public void close() throws IOException {
                    phase.close();
                }
            };
        } catch (RuntimeException | Error failure) {
            try {
                phase.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void verifyExpectedSize(SeekableByteChannel channel) throws IOException {
        if (expectedFileSize >= 0 && channel.size() != expectedFileSize) {
            throw AssetLoadException.content("Model file length changed after source admission: " + file);
        }
    }

    private AssetLoadException readFailure(AssetContainerView.ChunkInfo chunk, Throwable cause) {
        var message = "Failed to read model chunk file=" + file + " type=" + chunk.type() + " encoding="
            + chunk.encoding() + " encodedSize=" + chunk.size() + " decodedSize=" + chunk.decodeSize();
        return AssetLoadException.access(message, cause);
    }

    private static void close(UniBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    @FunctionalInterface
    public interface ChannelOpener {
        SeekableByteChannel open() throws IOException;
    }

    @FunctionalInterface
    private interface ChannelRead {
        UniBuffer read(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException;
    }
}
