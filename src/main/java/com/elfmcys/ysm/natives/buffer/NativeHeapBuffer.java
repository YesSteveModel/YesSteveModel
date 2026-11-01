package com.elfmcys.ysm.natives.buffer;

import com.elfmcys.ysm.buffer.NativeStorage;
import io.netty.util.internal.PlatformDependent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NativeHeapBuffer implements NativeStorage {
    private final long base;
    private final ByteBuffer data;

    public NativeHeapBuffer(int size) {
        this(size, 0);
    }

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

    /** Consumes the allocator's owning base view, including when wrapper allocation fails. */
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

    @Override
    public void close() {
        nFree(base);
    }

    private static native ByteBuffer nAlloc(int size, int alignment);

    private static native void nFree(long addr);
}
