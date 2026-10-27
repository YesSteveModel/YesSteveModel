package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class FileChunkBatchTest {
    @TempDir
    Path root;

    @Test
    void forwardingPhaseSealsOnFailureWithoutPoisoningANewIndependentPhase() throws Exception {
        var reads = new AtomicInteger();
        var source = new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
                return readStoredVerified(chunk, type);
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type)
                throws IOException {
                if (reads.incrementAndGet() == 1)
                    throw new IOException("failed single stream");
                return com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[] {1});
            }
        };
        var descriptor = chunk("test", 0, new byte[] {1});
        try (var phase = source.openReadPhase()) {
            assertThrows(IOException.class, () -> phase.readPayload(descriptor, BufferType.ARRAY));
            assertThrows(java.nio.channels.ClosedChannelException.class,
                () -> phase.readPayload(descriptor, BufferType.ARRAY));
            assertEquals(1, reads.get());
        }
        try (var phase = source.openReadPhase(); var value = phase.readPayload(descriptor, BufferType.ARRAY)) {
            assertEquals(1, value.nio().get());
        }
        assertEquals(2, reads.get());
    }

    @Test
    void lexicalPhaseSharesOnePhysicalOpenAndClosesBeforeAtomicReplacement() throws Exception {
        var file = root.resolve("chunks");
        Files.write(file, new byte[] {1, 2});
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var source = new FileChunkDataSource(file, 2, () -> ChannelReadPhaseTest.counting(file, opens, closes));
        UniBuffer verified;
        try (var phase = source.openReadPhase()) {
            verified = phase.readPayload(chunk("first", 0, new byte[] {1}), BufferType.ARRAY);
            try (var second = phase.readPayload(chunk("second", 1, new byte[] {2}), BufferType.ARRAY)) {
                assertEquals(2, second.nio().get());
            }
            assertEquals(1, opens.get());
            assertEquals(0, closes.get());
        }
        try (verified) {
            assertEquals(1, closes.get());
            var replacement = root.resolve("replacement");
            Files.write(replacement, new byte[] {9, 8});
            Files.move(replacement, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertEquals(1, verified.nio().get());
            assertThrows(AssetLoadException.class,
                () -> source.readPayload(chunk("first", 0, new byte[] {1}), BufferType.ARRAY));
            assertEquals(2, opens.get());
            assertEquals(2, closes.get());
            try (var current = source.readPayload(chunk("first", 0, new byte[] {9}), BufferType.ARRAY)) {
                assertEquals(9, current.nio().get());
            }
            assertEquals(3, closes.get());
        }
    }

    @Test
    void mutationTruncationAndCancellationSealTheCurrentPhase() throws Exception {
        var file = root.resolve("chunks");
        for (int mutation : List.of(0, 1, 2)) {
            Files.write(file, new byte[] {1, 2});
            var opens = new AtomicInteger();
            var closes = new AtomicInteger();
            var source = new FileChunkDataSource(file, 2, () -> ChannelReadPhaseTest.counting(file, opens, closes));
            try (var phase = source.openReadPhase();
                var verified = phase.readPayload(chunk("first", 0, new byte[] {1}), BufferType.ARRAY)) {
                if (mutation == 2) {
                    assertThrows(CancellationException.class, () -> phase.readPayloads(() -> true,
                        List.of(chunk("second", 1, new byte[] {2})), BufferType.ARRAY));
                } else {
                    Files.write(file, mutation == 0 ? new byte[] {1, 9} : new byte[] {1});
                    assertThrows(AssetLoadException.class,
                        () -> phase.readPayload(chunk("second", 1, new byte[] {2}), BufferType.ARRAY));
                }
                assertEquals(1, verified.nio().get());
                assertEquals(1, closes.get());
                assertThrows(AssetLoadException.class,
                    () -> phase.readPayload(chunk("first", 0, new byte[] {1}), BufferType.ARRAY));
            }
            assertEquals(1, opens.get());
            assertEquals(1, closes.get());
        }
    }

    @Test
    void oneChannelReadsEachUniquePayloadAndChecksIntegrity() throws Exception {
        var file = root.resolve("chunks");
        Files.write(file, new byte[] {1, 2, 3, 4});
        var first = chunk("first", 0, new byte[] {1, 2});
        var second = chunk("second", 2, new byte[] {3, 4});
        var opens = new AtomicInteger();
        var bytes = new AtomicLong();
        var source = new FileChunkDataSource(file, 4, () -> {
            opens.incrementAndGet();
            return new CountingChannel(FileChannel.open(file), bytes, () -> {
            });
        });
        var batch = source.readPayloads(() -> false, List.of(first, second, first), BufferType.ARRAY);
        try {
            assertEquals(1, opens.get());
            assertEquals(4, bytes.get());
            assertEquals(2, batch.size());
            assertEquals(1, batch.get("first").nio().get());
        } finally {
            batch.values().forEach(UniBuffer::close);
        }
        Files.write(file, new byte[] {1, 2, 9, 4});
        assertEquals(AssetLoadException.Reason.CONTENT, assertThrows(AssetLoadException.class,
            () -> source.readPayloads(() -> false, List.of(first, second), BufferType.ARRAY)).reason());
    }

    @Test
    void cancellationAndLateFailureReleaseEveryUnpublishedReference() throws Exception {
        var first = chunk("first", 0, new byte[] {1});
        var second = chunk("second", 1, new byte[] {2});
        for (boolean cancel : List.of(false, true)) {
            var cancelled = new AtomicBoolean();
            var closed = new AtomicInteger();
            var buffers = new ArrayList<UniBuffer>();
            var source = new ChunkDataSource() {
                @Override
                public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType type) throws IOException {
                    if (chunk == second)
                        throw AssetLoadException.access("Source expired");
                    var delegate = UniBuffer.allocate(1, BufferType.ARRAY);
                    var buffer = (UniBuffer)Proxy.newProxyInstance(UniBuffer.class.getClassLoader(),
                        new Class<?>[] {UniBuffer.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("close"))
                                closed.incrementAndGet();
                            return method.invoke(delegate, arguments);
                        });
                    buffers.add(buffer);
                    cancelled.set(cancel);
                    return buffer;
                }

                @Override
                public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType type) {
                    throw new AssertionError();
                }
            };
            var failure = assertThrows(Exception.class,
                () -> source.readPayloads(cancelled::get, List.of(first, second), BufferType.ARRAY));
            assertEquals(cancel ? CancellationException.class : AssetLoadException.class, failure.getClass());
            assertEquals(1, buffers.size());
            assertEquals(1, closed.get());
        }
    }

    @Test
    void sourceLengthChangeAndCloseFailureRejectTheWholeBatch() throws Exception {
        var file = root.resolve("chunks");
        Files.write(file, new byte[] {1, 2});
        var first = chunk("first", 0, new byte[] {1});
        var second = chunk("second", 1, new byte[] {2});
        var mutated = new AtomicBoolean();
        var source =
            new FileChunkDataSource(file, 2, () -> new CountingChannel(FileChannel.open(file), new AtomicLong(), () -> {
                if (mutated.compareAndSet(false, true)) {
                    try (var writer = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        writer.truncate(1);
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }
            }));
        assertThrows(AssetLoadException.class,
            () -> source.readPayloads(() -> false, List.of(first, second), BufferType.ARRAY));
        Files.write(file, new byte[] {1, 2});
        var closeFails =
            new FileChunkDataSource(file, 2, () -> new CountingChannel(FileChannel.open(file), new AtomicLong(), () -> {
            }) {
                @Override
                public void close() throws IOException {
                    super.close();
                    throw new IOException("Channel close failed");
                }
            });
        assertEquals(AssetLoadException.Reason.ACCESS, assertThrows(AssetLoadException.class,
            () -> closeFails.readPayloads(() -> false, List.of(first, second), BufferType.ARRAY)).reason());
    }

    @Test
    void conflictingDescriptorsAreRejectedBeforePayloadAllocation() {
        var first = chunk("same", 0, new byte[] {1});
        var conflicting = chunk("same", 1, new byte[] {1});
        assertThrows(AssetLoadException.class,
            () -> ChunkDataSource.collectPayloads(() -> false, List.of(first, conflicting), chunk -> {
                throw new AssertionError("Conflicting inputs must not allocate payloads");
            }));
    }

    private static AssetContainerView.ChunkInfo chunk(String type, int offset, byte[] value) {
        return new AssetContainerView.ChunkInfo(type, "", offset, value.length, 0, 0, 0, 0,
            ModelHashing.blake3(ByteBuffer.wrap(value)).bytes());
    }

    static class CountingChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;
        private final AtomicLong bytes;
        private final Runnable afterRead;

        CountingChannel(SeekableByteChannel delegate, AtomicLong bytes, Runnable afterRead) {
            this.delegate = delegate;
            this.bytes = bytes;
            this.afterRead = afterRead;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            int count = delegate.read(target);
            if (count > 0)
                bytes.addAndGet(count);
            afterRead.run();
            return count;
        }

        @Override
        public int write(ByteBuffer value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long value) throws IOException {
            delegate.position(value);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
