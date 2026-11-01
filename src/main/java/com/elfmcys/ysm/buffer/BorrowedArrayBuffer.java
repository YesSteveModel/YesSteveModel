package com.elfmcys.ysm.buffer;

import java.nio.ByteBuffer;
import java.util.Objects;

/** A range borrowed until its source owner releases or reuses the backing. */
public final class BorrowedArrayBuffer implements ArrayBuffer {
    private final Object anchor;
    private final byte[] array;
    private final int offset;
    private final int size;

    private BorrowedArrayBuffer(Object anchor, byte[] array, int offset, int size) {
        this.anchor = Objects.requireNonNull(anchor);
        this.array = array;
        this.offset = offset;
        this.size = size;
    }

    static BorrowedArrayBuffer of(Object anchor, byte[] array, int base, int capacity, int offset, int size) {
        Objects.checkFromIndexSize(base, capacity, array.length);
        Objects.checkFromIndexSize(offset, size, capacity);
        return new BorrowedArrayBuffer(anchor, array, base + offset, size);
    }

    public static BorrowedArrayBuffer of(Object anchor, byte[] array, int offset, int size) {
        return of(anchor, array, 0, array.length, offset, size);
    }

    @Override
    public byte[] array() {
        return array;
    }

    @Override
    public int arrayOffset() {
        return offset;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public ByteBuffer nio() {
        return ByteBuffer.wrap(array, offset, size).slice();
    }

    @Override
    public BorrowedArrayBuffer slice(int offset, int size) {
        return of(anchor, array, this.offset, this.size, offset, size);
    }
}
