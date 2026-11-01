package com.elfmcys.ysm.natives.buffer;

import com.elfmcys.ysm.buffer.NativeStorage;
import io.netty.util.internal.PlatformDependent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Owns one allocation from the native buffer allocator.
 *
 * <p>Execution: the fixed owner ends all reads, writes, and JNI borrows before close().
 * Storage has no Cleaner or closed-state guard; close is a single release obligation.
 *
 * <p>Ownership: nio() and slices borrow the allocation and do not acquire release authority.
 * Keeping such a view reachable does not prevent explicit close.
 *
 * <p>Seam: implements owning allocation transfer in the
 * [JNI buffer handoff](docs/architecture/native-runtime/implementation/jni-and-memory.md#buffer-传递).
 */
public final class NativeHeapBuffer implements NativeStorage {
    private final long base;
    private final ByteBuffer data;

    public NativeHeapBuffer(int size) {
        this(size, 0);
    }

    /**
     * @param size allocation size in bytes, in [0, MAX_SIZE]
     * @param alignment nonpositive for default allocation; otherwise an allocator-supported power of two
     * @throws OutOfMemoryError if the native allocation cannot be returned
     */
    public NativeHeapBuffer(int size, int alignment) {
        if (size < 0 || size > MAX_SIZE)
            throw new IllegalArgumentException("Invalid size");
        var allocated = nAlloc(size, alignment);
        if (allocated == null)
            throw new OutOfMemoryError();
        base = PlatformDependent.directBufferAddress(allocated);
        try {
            data = allocated.slice().order(ByteOrder.nativeOrder());
        } catch (RuntimeException | Error failure) {
            nFree(base);
            throw failure;
        }
    }

    /**
     * <p>Requires: ownedMem is the native allocator's owning base view, not a slice or another
     * allocator's buffer. No other owner may free that base address.
     *
     * <p>Ownership: consumes the allocation on entry for a valid carrier, including wrapper
     * allocation failure. The remaining range becomes this Storage's accessible range.
     *
     * @return the allocation owner; the supplied view must no longer be used independently
     * @throws IllegalArgumentException if ownedMem is not direct
     */
    public static NativeHeapBuffer adopt(ByteBuffer ownedMem) {
        if (!ownedMem.isDirect())
            throw new IllegalArgumentException("Buffer is not direct");
        var base = PlatformDependent.directBufferAddress(ownedMem);
        ByteBuffer view;
        try {
            view = ownedMem.slice().order(ByteOrder.nativeOrder());
        } catch (RuntimeException | Error failure) {
            nFree(base);
            throw failure;
        }
        try {
            return new NativeHeapBuffer(base, view);
        } catch (RuntimeException | Error failure) {
            nFree(base);
            throw failure;
        }
    }

    private NativeHeapBuffer(long base, ByteBuffer data) {
        this.base = base;
        this.data = data;
    }

    @Override
    public long ptr() {
        return PlatformDependent.directBufferAddress(data);
    }

    @Override
    public int size() {
        return data.capacity();
    }

    @Override
    public ByteBuffer nio() {
        return data.duplicate().order(data.order());
    }

    /** Releases the allocation. Repeated close or access after close violates the Storage lifetime. */
    @Override
    public void close() {
        nFree(base);
    }

    private static native ByteBuffer nAlloc(int size, int alignment);

    private static native void nFree(long addr);
}
