package com.elfmcys.ysm.buffer;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class SharedNativeBuffer extends SharedBuffer implements NativeBuffer {
    private final int offset;
    private final int size;

    SharedNativeBuffer(NativeStorage storage) {
        super(storage);
        offset = 0;
        size = storage.size();
    }

    private SharedNativeBuffer(SharedNativeBuffer source, int offset, int size) {
        super(source);
        this.offset = offset;
        this.size = size;
    }

    /** Consumes storage on success and failure. */
    public static SharedNativeBuffer adopt(NativeStorage storage) {
        return (SharedNativeBuffer)SharedBuffer.adopt(storage);
    }

    public static SharedNativeBuffer allocate(int size) {
        return adopt(NativeBuffer.allocate(size));
    }

    public static SharedNativeBuffer copyOf(UniBuffer source) {
        return adopt(NativeBuffer.copyOf(source));
    }

    @Override
    public long ptr() {
        return ((NativeStorage)storage()).ptr() + offset;
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
    public BorrowedNativeBuffer slice(int offset, int size) {
        Objects.checkFromIndexSize(offset, size, this.size);
        return BorrowedNativeBuffer.of(storage(), nio(), offset, size);
    }

    @Override
    public SharedNativeBuffer ownedSlice(int offset, int size) {
        Objects.checkFromIndexSize(offset, size, this.size);
        return new SharedNativeBuffer(this, this.offset + offset, size);
    }
}
