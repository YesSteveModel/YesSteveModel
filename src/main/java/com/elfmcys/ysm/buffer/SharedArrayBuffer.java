package com.elfmcys.ysm.buffer;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class SharedArrayBuffer extends SharedBuffer implements ArrayBuffer {
    private final int offset;
    private final int size;

    SharedArrayBuffer(ArrayStorage storage) {
        super(storage);
        offset = 0;
        size = storage.size();
    }

    private SharedArrayBuffer(SharedArrayBuffer source, int offset, int size) {
        super(source);
        this.offset = offset;
        this.size = size;
    }

    /** Consumes storage on success and failure. */
    public static SharedArrayBuffer adopt(ArrayStorage storage) {
        return (SharedArrayBuffer)SharedBuffer.adopt(storage);
    }

    public static SharedArrayBuffer allocate(int size) {
        return adopt(ArrayBuffer.allocate(size));
    }

    public static SharedArrayBuffer copyOf(UniBuffer source) {
        return adopt(ArrayBuffer.copyOf(source));
    }

    @Override
    public byte[] array() {
        return ((ArrayStorage)storage()).array();
    }

    @Override
    public int arrayOffset() {
        return ((ArrayStorage)storage()).arrayOffset() + offset;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public ByteBuffer nio() {
        var data = storage().nio();
        return data.slice(offset, size).order(data.order());
    }

    @Override
    public BorrowedArrayBuffer slice(int offset, int size) {
        Objects.checkFromIndexSize(offset, size, this.size);
        return BorrowedArrayBuffer.of(storage(), array(), arrayOffset(), this.size, offset, size);
    }

    @Override
    public SharedArrayBuffer ownedSlice(int offset, int size) {
        Objects.checkFromIndexSize(offset, size, this.size);
        return new SharedArrayBuffer(this, this.offset + offset, size);
    }
}
