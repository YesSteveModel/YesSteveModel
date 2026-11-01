package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.NativeStorage;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PreviewPixelsTest {
    @Test
    void sharedBackingSurvivesUntilItsLastIndependentUseEnds() {
        try (var owner = new PreviewPixels.Owner()) {
            var backing = new TrackedStorage(8);
            var first = owner.adopt(backing, 2, 1);
            var second = first.acquire();
            first.close();
            first.close();
            assertEquals(0, backing.closes);
            second.borrow(bytes -> assertEquals(8, bytes.remaining()));
            second.close();
            assertEquals(1, backing.closes);
        }
    }

    @Test
    void shutdownSealsNewUsesButPreservesTheActiveBorrow() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner = new PreviewPixels.Owner();
        var backing = new TrackedStorage(4);
        backing.nio().putInt(0, 123);
        var pixels = owner.adopt(backing, 1, 1);
        var failure = new AtomicReference<Throwable>();
        var borrower = new Thread(() -> {
            try {
                pixels.borrow(bytes -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    assertEquals(123, bytes.getInt(0));
                    assertEquals(0, backing.closes);
                });
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            borrower.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            owner.close();
            assertEquals(0, backing.closes);
            assertThrows(IllegalStateException.class, pixels::acquire);
            assertThrows(IllegalStateException.class, () -> pixels.borrow(ignored -> fail()));
            pixels.close();
            assertEquals(0, backing.closes);
        } finally {
            release.countDown();
            borrower.join(5000);
            owner.close();
            pixels.close();
        }
        assertFalse(borrower.isAlive());
        assertNull(failure.get());
        assertEquals(1, backing.closes);
    }

    @Test
    void rejectedAdoptionLeavesTheDecoderResponsibleForItsBuffer() {
        var owner = new PreviewPixels.Owner();
        owner.close();
        try (var backing = NativeBuffer.allocate(4)) {
            assertThrows(IllegalStateException.class, () -> owner.adopt(backing, 1, 1));
            assertDoesNotThrow(backing::ptr);
        }
        try (var open = new PreviewPixels.Owner(); var backing = NativeBuffer.allocate(4)) {
            assertThrows(IllegalArgumentException.class, () -> open.adopt(backing, 2, 1));
            assertDoesNotThrow(backing::ptr);
        }
    }

    @Test
    void reloadUsesASerialGateIndependentOfRetainedPixels() {
        try (var owner = new PreviewPixels.Owner(); var pixels = owner.adopt(NativeBuffer.allocate(4), 1, 1)) {
            try (var reload = owner.beginReload()) {
                assertThrows(IllegalStateException.class, owner::beginReload);
                pixels.borrow(bytes -> assertEquals(4, bytes.remaining()));
            }
            try (var next = owner.beginReload()) {
                owner.close();
                assertThrows(IllegalStateException.class, owner::beginReload);
            }
        }
    }

    private static final class TrackedStorage implements NativeStorage {
        private final NativeStorage storage;
        private volatile int closes;

        private TrackedStorage(int size) {
            storage = NativeBuffer.allocate(size);
        }

        @Override
        public long ptr() {
            return storage.ptr();
        }

        @Override
        public int size() {
            return storage.size();
        }

        @Override
        public java.nio.ByteBuffer nio() {
            return storage.nio();
        }

        @Override
        public void close() {
            closes++;
            storage.close();
        }
    }
}
