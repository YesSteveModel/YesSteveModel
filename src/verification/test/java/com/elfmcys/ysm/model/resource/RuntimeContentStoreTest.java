package com.elfmcys.ysm.model.resource;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContentStoreTest {
    @Test
    void exactWrapperPreservesOnePhysicalChannelForTheWholePhase(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var file = root.resolve("chunks");
        java.nio.file.Files.write(file, new byte[] {1, 2, 3});
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var source = new com.elfmcys.ysm.format.schema.file.FileChunkDataSource(file, 3, () -> {
            var physical = java.nio.channels.FileChannel.open(file);
            opens.incrementAndGet();
            return (java.nio.channels.SeekableByteChannel)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.nio.channels.SeekableByteChannel.class},
                (proxy, method, args) -> {
                    try {
                        var result = method.invoke(physical, args);
                        if (method.getName().equals("close"))
                            closes.incrementAndGet();
                        return result == physical ? proxy : result;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        });
        var descriptor = new AssetContainerView.ChunkInfo("test", "", 0, 3, 0, 0, 0, 0,
            com.elfmcys.ysm.model.storage.ModelHashing.blake3(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3})).bytes());
        var reports = new AtomicInteger();
        var store = new RuntimeContentStore(ignored -> reports.incrementAndGet());
        var exact = store.exact(managed(identity(3, 13), source));
        try (var phase = exact.chunks().openReadPhase()) {
            try (var first = phase.readPayload(descriptor, BufferType.ARRAY);
                var second = phase.readStoredVerified(descriptor, BufferType.ARRAY)) {
                assertEquals(3, first.size());
                assertEquals(3, second.size());
                assertEquals(1, opens.get());
                assertEquals(0, closes.get());
            }
        }
        assertEquals(1, closes.get());
        var batch = exact.chunks().readPayloads(() -> false, java.util.List.of(descriptor), BufferType.ARRAY);
        batch.values().forEach(UniBuffer::close);
        assertEquals(2, opens.get());
        assertEquals(2, closes.get());
        store.tick();
        assertEquals(0, reports.get());
    }

    @Test
    void corruptionIsCommittedOnTickAndDoesNotInvalidateAnAlreadyStartedRead() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sourceReads = new AtomicInteger();
        var reports = new AtomicInteger();
        var chunk = chunk();
        ChunkDataSource chunks = new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo ignored, BufferType bufferType)
                throws IOException {
                return readStoredVerified(ignored, bufferType);
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo ignored, BufferType bufferType)
                throws IOException {
                var call = sourceReads.incrementAndGet();
                if (call == 1) {
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    return ArrayBuffer.move(new byte[] {1, 2, 3});
                }
                throw AssetLoadException.content("controlled corruption");
            }
        };
        var original = managed(identity(1, 11), chunks);
        var store = new RuntimeContentStore(ignored -> reports.incrementAndGet());
        var exact = store.exact(original);

        var admitted = CompletableFuture.supplyAsync(() -> {
            try (var value = exact.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                var bytes = new byte[value.size()];
                value.nio().get(bytes);
                return bytes;
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(AssetLoadException.class, () -> exact.chunks().readStoredVerified(chunk, BufferType.ARRAY));
        assertEquals(0, reports.get());

        store.tick();
        assertEquals(1, reports.get());
        release.countDown();
        assertArrayEquals(new byte[] {1, 2, 3}, admitted.get(5, TimeUnit.SECONDS));

        assertThrows(AssetLoadException.class, () -> exact.chunks().readStoredVerified(chunk, BufferType.ARRAY));
        assertEquals(2, sourceReads.get());
    }

    @Test
    void aNewExactSourceInstanceDoesNotInheritTheOldCorruptionFact() throws Exception {
        var identity = identity(2, 12);
        var failing = managed(identity, failingChunks());
        var store = new RuntimeContentStore(ignored -> {
        });
        var oldExact = store.exact(failing);
        assertThrows(AssetLoadException.class, () -> oldExact.chunks().readStoredVerified(chunk(), BufferType.ARRAY));
        store.tick();

        var healthy = managed(identity, constantChunks(new byte[] {4, 5}));
        var newExact = store.exact(healthy);
        try (var bytes = newExact.chunks().readStoredVerified(chunk(), BufferType.ARRAY)) {
            assertEquals(2, bytes.size());
        }
        assertSame(newExact, store.exact(healthy));
        assertThrows(AssetLoadException.class, () -> oldExact.chunks().readStoredVerified(chunk(), BufferType.ARRAY));
    }

    private static ManagedContainer managed(ModelFileIdentity identity, ChunkDataSource chunks) throws Exception {
        var value = (ManagedContainer)UnsafeUtil.getUnsafe().allocateInstance(ManagedContainer.class);
        put(value, ManagedContainer.class, "representation", representation(identity));
        put(value, ManagedContainer.class, "chunks", chunks);
        return value;
    }

    private static void put(Object target, Class<?> owner, String name, Object value)
        throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        UnsafeUtil.getUnsafe().putObject(target, UnsafeUtil.getUnsafe().objectFieldOffset(field), value);
    }

    private static ModelRepresentation representation(ModelFileIdentity identity) throws Exception {
        var value = (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
        put(value, ModelRepresentation.class, "identity", identity);
        return value;
    }

    private static ChunkDataSource failingChunks() {
        return new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
                throw AssetLoadException.content("controlled corruption");
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                throws IOException {
                throw AssetLoadException.content("controlled corruption");
            }
        };
    }

    private static ChunkDataSource constantChunks(byte[] bytes) {
        return new ChunkDataSource() {
            @Override
            public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return ArrayBuffer.move(bytes.clone());
            }

            @Override
            public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) {
                return ArrayBuffer.move(bytes.clone());
            }
        };
    }

    private static AssetContainerView.ChunkInfo chunk() {
        return new AssetContainerView.ChunkInfo("test", "raw", 0, 3, 3, 0, 3, 0, hash(3).bytes());
    }

    private static ModelFileIdentity identity(int model, int container) {
        return new ModelFileIdentity(hash(model), hash(container));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }
}
