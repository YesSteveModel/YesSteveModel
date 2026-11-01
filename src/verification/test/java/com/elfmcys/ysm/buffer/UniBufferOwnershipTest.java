package com.elfmcys.ysm.buffer;

import io.netty.util.internal.PlatformDependent;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UniBufferOwnershipTest {
    @Test
    void sharingSharedViewsPreservesIdentityAndContentAliases() {
        UniBuffer array = SharedArrayBuffer.allocate(12).ownedSlice(3, 6).ownedSlice(1, 3);
        UniBuffer nativeBuffer = SharedNativeBuffer.allocate(12).ownedSlice(3, 6).ownedSlice(1, 3);
        assertSame(array, array.share());
        assertSame(array, array.shareArray());
        assertSame(nativeBuffer, nativeBuffer.share());
        assertSame(nativeBuffer, nativeBuffer.shareNative());
        array.shareArray().nio().put(0, (byte)42);
        assertEquals(42, array.nio().get(0));
        assertEquals(3, array.share().size());
        assertEquals(3, nativeBuffer.share().size());
    }

    @Test
    void sharingConvertsRepresentationsAndCopiesBorrowedRanges() {
        for (var type : BufferType.values()) {
            try (var storage = UniBuffer.allocate(12, type)) {
                for (int index = 0; index < storage.size(); index++)
                    storage.nio().put(index, (byte)index);
                var shared = storage.share();
                assertEquals(type, shared.type());
                assertNotSame(storage, shared);
                var borrowed = storage.slice(2, 8).slice(1, 3);
                assertSharedCopies(borrowed);
                assertSharedCopies(shared.ownedSlice(3, 3));
                storage.nio().put(0, (byte)99);
                assertEquals(0, shared.nio().get(0));
            }
        }
    }

    private static void assertSharedCopies(UniBuffer source) {
        var array = source.shareArray();
        var nativeBuffer = source.shareNative();
        assertEquals(BufferType.ARRAY, array.type());
        assertEquals(BufferType.NATIVE, nativeBuffer.type());
        assertEquals(3, array.size());
        assertEquals(3, nativeBuffer.size());
        assertEquals(3, array.nio().get(0));
        assertEquals(5, nativeBuffer.nio().get(2));
        if (!(source instanceof SharedBuffer)) {
            var shared = source.share();
            assertNotSame(source, shared);
            source.nio().put(1, (byte)88);
            assertEquals(4, shared.nio().get(1));
            assertEquals(4, array.nio().get(1));
            assertEquals(4, nativeBuffer.nio().get(1));
        } else if (source.type() == BufferType.ARRAY) {
            array.nio().put(1, (byte)88);
            assertEquals(4, nativeBuffer.nio().get(1));
        } else {
            nativeBuffer.nio().put(1, (byte)88);
            assertEquals(4, array.nio().get(1));
        }
        Reference.reachabilityFence(source);
    }

    @Test
    void sharingStorageDoesNotTransferItsCloseResponsibility() {
        var releases = new AtomicInteger();
        var storage = new TrackingStorage(releases, false);
        storage.array()[0] = 42;
        var shared = storage.shareArray();
        assertEquals(0, releases.get());
        storage.close();
        assertEquals(1, releases.get());
        assertEquals(42, shared.nio().get(0));
        assertNotSame(storage.array(), shared.array());
    }

    @Test
    void storageAndGcSlicesAreBorrowedAndKeepExactArrayRanges() {
        try (var storage = ArrayBuffer.allocate(16)) {
            storage.array()[storage.arrayOffset() + 6] = 42;
            var slice = storage.slice(4, 8).slice(2, 3);
            assertInstanceOf(BorrowedArrayBuffer.class, slice);
            assertEquals(storage.arrayOffset() + 6, slice.arrayOffset());
            assertEquals(3, slice.size());
            assertEquals(42, slice.nio().get(0));
            assertThrows(IndexOutOfBoundsException.class, () -> slice.slice(2, 2));
        }
        var gc = SharedArrayBuffer.allocate(16);
        assertInstanceOf(BorrowedArrayBuffer.class, gc.slice(2, 4));
        assertInstanceOf(SharedArrayBuffer.class, gc.ownedSlice(2, 4));
        assertFalse(AutoCloseable.class.isAssignableFrom(SharedBuffer.class));
        assertFalse(AutoCloseable.class.isAssignableFrom(BorrowedArrayBuffer.class));
        assertFalse(AutoCloseable.class.isAssignableFrom(BorrowedNativeBuffer.class));
        Reference.reachabilityFence(gc);
    }

    @Test
    void directBorrowAndStorageSlicesKeepSourcePositionAndByteRange() {
        var direct = ByteBuffer.allocateDirect(24);
        direct.position(3).limit(19);
        var borrowed = NativeBuffer.borrow(direct);
        assertEquals(16, borrowed.size());
        assertEquals(PlatformDependent.directBufferAddress(direct) + 3, borrowed.ptr());
        var nested = borrowed.slice(2, 8).slice(1, 4);
        assertEquals(borrowed.ptr() + 3, nested.ptr());
        assertEquals(4, nested.nio().remaining());
        assertThrows(IndexOutOfBoundsException.class, () -> nested.slice(-1, 0));
        Reference.reachabilityFence(direct);
        try (var storage = NativeBuffer.move(ByteBuffer.allocateDirect(16))) {
            assertInstanceOf(BorrowedNativeBuffer.class, storage.slice(2, 4));
        }
    }

    @Test
    void ownedSliceRetainsAllocationAfterRootViewIsCollected() throws Exception {
        var releases = new AtomicInteger();
        var held = ownedSlice(releases);
        var root = held.root;
        await(() -> root.get() == null);
        assertEquals(0, releases.get());
        assertEquals(4, held.slice.size());
        held = null;
        await(() -> releases.get() == 1);
        assertEquals(1, releases.get());
    }

    private static Held ownedSlice(AtomicInteger releases) {
        var root = SharedArrayBuffer.adopt(new TrackingStorage(releases, false));
        return new Held(root.ownedSlice(2, 4), new WeakReference<>(root));
    }

    @Test
    void nativeOwnedSliceKeepsNestedOffsetsAndAbsoluteBorrowIgnoresCursor() {
        var source = ByteBuffer.allocateDirect(24);
        source.position(3).limit(19);
        var gc = SharedNativeBuffer.adopt(NativeBuffer.move(source));
        var owned = gc.ownedSlice(2, 8).ownedSlice(1, 4);
        assertEquals(gc.ptr() + 3, owned.ptr());
        assertEquals(4, owned.nio().remaining());
        assertInstanceOf(BorrowedNativeBuffer.class, owned.slice(1, 2));
        assertEquals(PlatformDependent.directBufferAddress(source) + 5,
            BorrowedNativeBuffer.at(source, source, 5, 2).ptr());
        Reference.reachabilityFence(gc);
        Reference.reachabilityFence(owned);
    }

    @Test
    void copyingReadOnlyHeapNioKeepsSourceCursorAndCreatesIndependentRanges() {
        var source = ByteBuffer.wrap(new byte[] {1, 2, 3, 4}).asReadOnlyBuffer();
        source.position(1).limit(3);
        try (var array = ArrayBuffer.copyOf(source); var nativeCopy = NativeBuffer.copyOf(source)) {
            assertEquals(1, source.position());
            assertEquals(2, array.size());
            assertEquals(2, array.nio().get(0));
            assertEquals(3, nativeCopy.nio().get(1));
            array.array()[array.arrayOffset()] = 9;
            assertEquals(2, source.get(1));
        }
    }

    @Test
    void borrowedSliceDoesNotRetainGcOwnership() throws Exception {
        var releases = new AtomicInteger();
        var borrowed = borrowedSlice(releases);
        await(() -> releases.get() == 1);
        assertEquals(1, releases.get());
        Reference.reachabilityFence(borrowed);
    }

    private static BorrowedArrayBuffer borrowedSlice(AtomicInteger releases) {
        return SharedArrayBuffer.adopt(new TrackingStorage(releases, false)).slice(2, 4);
    }

    @Test
    void adoptionFailureReleasesUnpublishedStorageOnce() {
        var releases = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> SharedArrayBuffer.adopt(new TrackingStorage(releases, true)));
        assertEquals(1, releases.get());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "Cleaner did not complete before the deadline");
    }

    private record Held(SharedArrayBuffer slice, WeakReference<SharedArrayBuffer> root) {
    }

    private static final class TrackingStorage implements ArrayStorage {
        private final byte[] bytes = new byte[16];
        private final AtomicInteger releases;
        private final boolean failSize;

        private TrackingStorage(AtomicInteger releases, boolean failSize) {
            this.releases = releases;
            this.failSize = failSize;
        }

        @Override
        public byte[] array() {
            return bytes;
        }

        @Override
        public int arrayOffset() {
            return 0;
        }

        @Override
        public int size() {
            if (failSize)
                throw new IllegalStateException("Rejected size");
            return bytes.length;
        }

        @Override
        public ByteBuffer nio() {
            return ByteBuffer.wrap(bytes);
        }

        @Override
        public void close() {
            releases.incrementAndGet();
        }
    }
}
