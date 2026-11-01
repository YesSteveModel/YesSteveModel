package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.BufferStorage;
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
    public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        return read(chunk, bufferType, InlineChunkReader::readPayload);
    }

    @Override
    public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
        throws IOException {
        return read(chunk, bufferType, InlineChunkReader::readStoredVerified);
    }

    @Override
    public Map<String, SharedBuffer> readPayloads(BooleanSupplier cancelled,
        Collection<AssetContainerView.ChunkInfo> chunks, BufferType bufferType) throws IOException {
        if (chunks.isEmpty())
            return ChunkDataSource.super.readPayloads(cancelled, chunks, bufferType);
        try (var phase = openReadPhase()) {
            return phase.readPayloads(cancelled, chunks, bufferType);
        } catch (IOException | RuntimeException | Error failure) {
            if (failure instanceof AssetLoadException asset)
                throw asset;
            if (failure instanceof IOException || failure instanceof SecurityException)
                throw AssetLoadException.access("Failed to read model chunk batch: " + file, failure);
            throw failure;
        }
    }

    private SharedBuffer read(AssetContainerView.ChunkInfo chunk, BufferType bufferType, ChannelRead operation)
        throws IOException {
        BufferStorage result = null;
        try (var phase = ChannelReadPhase.open(opener, this::verifyExpectedSize)) {
            result = phase.read(channel -> operation.read(channel, chunk, bufferType), BufferStorage::close);
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
        return SharedBuffer.adopt(result);
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
                public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type)
                    throws IOException {
                    return read(chunk, type, InlineChunkReader::readPayload);
                }

                @Override
                public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type)
                    throws IOException {
                    return read(chunk, type, InlineChunkReader::readStoredVerified);
                }

                private SharedBuffer read(AssetContainerView.ChunkInfo chunk, BufferType type, ChannelRead reader)
                    throws IOException {
                    try {
                        return SharedBuffer
                            .adopt(phase.read(channel -> reader.read(channel, chunk, type), BufferStorage::close));
                    } catch (AssetLoadException failure) {
                        throw failure;
                    } catch (IOException | SecurityException failure) {
                        throw readFailure(chunk, failure);
                    }
                }

                @Override
                public Map<String, SharedBuffer> readPayloads(BooleanSupplier cancelled,
                    Collection<AssetContainerView.ChunkInfo> chunks, BufferType type) throws IOException {
                    return phase.read(
                        channel -> ChunkDataSource.collectPayloads(cancelled, chunks,
                            chunk -> SharedBuffer.adopt(InlineChunkReader.readPayload(channel, chunk, type))),
                        Map::clear);
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

    private static void close(BufferStorage buffer) {
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
        BufferStorage read(SeekableByteChannel channel, AssetContainerView.ChunkInfo chunk, BufferType bufferType)
            throws IOException;
    }
}
