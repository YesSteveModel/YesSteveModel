package com.elfmcys.ysm.buffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.nio.ByteBuffer;
import java.util.Objects;

final class ArrayHeapBuffer implements ArrayStorage {
    private final ByteBuf pooled;
    private final byte[] array;
    private final int offset;
    private final int size;

    ArrayHeapBuffer(int size, boolean pool) {
        if (size < 0 || size > MAX_SIZE)
            throw new IllegalArgumentException("Invalid size");
        pooled = pool ? PooledByteBufAllocator.DEFAULT.heapBuffer(size, size) : null;
        array = pooled == null ? new byte[size] : pooled.array();
        offset = pooled == null ? 0 : pooled.arrayOffset();
        this.size = size;
    }

    ArrayHeapBuffer(byte[] array, int offset, int size) {
        Objects.checkFromIndexSize(offset, size, array.length);
        this.array = array;
        this.offset = offset;
        this.size = size;
        pooled = null;
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
    public void close() {
        if (pooled != null)
            pooled.release();
    }
}
