package com.elfmcys.ysm.buffer;

import io.netty.util.internal.PlatformDependent;
import java.nio.ByteBuffer;
import java.util.Objects;

/** The anchor prevents GC of the source, but cannot prevent explicit release or reallocation. */
public final class BorrowedNativeBuffer implements NativeBuffer {
    private final Object anchor;
    private final ByteBuffer data;
    private final long address;

    private BorrowedNativeBuffer(Object anchor, ByteBuffer data) {
        this.anchor = Objects.requireNonNull(anchor);
        this.data = data;
        address = PlatformDependent.directBufferAddress(data);
    }

    public static BorrowedNativeBuffer of(Object anchor, ByteBuffer source, int offset, int size) {
        if (!source.isDirect())
            throw new IllegalArgumentException("Buffer is not direct");
        Objects.checkFromIndexSize(offset, size, source.remaining());
        return at(anchor, source, source.position() + offset, size);
    }

    /** Borrows absolute indices without changing the source cursor. */
    public static BorrowedNativeBuffer at(Object anchor, ByteBuffer source, int index, int size) {
        if (!source.isDirect())
            throw new IllegalArgumentException("Buffer is not direct");
        Objects.checkFromIndexSize(index, size, source.limit());
        return new BorrowedNativeBuffer(anchor, source.slice(index, size).order(source.order()));
    }

    @Override
    public long ptr() {
        return address;
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
    public BorrowedNativeBuffer slice(int offset, int size) {
        return of(anchor, data, offset, size);
    }
}
