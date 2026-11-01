package com.elfmcys.ysm.buffer;

import io.netty.util.internal.PlatformDependent;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Borrows a fixed byte range and the source's access permissions.
 *
 * <p>Requires: the owner keeps the allocation live and excludes reallocation, release, and
 * conflicting access throughout the borrow. A GC-managed source must itself remain reachable;
 * an anchor to its Storage does not retain its GC owner.
 *
 * <p>Ownership: this view retains the supplied anchor, not release authority.
 * The anchor cannot prevent explicit release, pool return, or reuse of the bytes.
 * All child slices share this anchor and the same lifetime limit.
 *
 * <p>Seam: supplies bounded addresses for the
 * [synchronous borrow contract](docs/architecture/native-runtime/implementation/jni-and-memory.md#帧内借用).
 */
public final class BorrowedNativeBuffer implements NativeBuffer {
    private final Object anchor;
    private final ByteBuffer data;
    private final long address;

    private BorrowedNativeBuffer(Object anchor, ByteBuffer data) {
        this.anchor = Objects.requireNonNull(anchor);
        this.data = data;
        address = PlatformDependent.directBufferAddress(data);
    }

    /**
     * @param anchor the source owner or anchor retained for the borrow
     * @param offset byte offset relative to source.position()
     * @param size byte count within source.remaining()
     * @return a zero-position view without changing the source cursor
     * @throws IllegalArgumentException if source is not direct
     * @throws IndexOutOfBoundsException if the requested range exceeds the remaining bytes
     */
    public static BorrowedNativeBuffer of(Object anchor, ByteBuffer source, int offset, int size) {
        if (!source.isDirect())
            throw new IllegalArgumentException("Buffer is not direct");
        Objects.checkFromIndexSize(offset, size, source.remaining());
        return at(anchor, source, source.position() + offset, size);
    }

    /**
     * @param index absolute byte index in [0, source.limit()]
     * @param size byte count, with index + size at most source.limit()
     * @return a zero-position view without changing the source cursor
     * @throws IllegalArgumentException if source is not direct
     * @throws IndexOutOfBoundsException if the absolute range exceeds the limit
     */
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
