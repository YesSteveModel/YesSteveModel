package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.util.CleanerUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

class ArrayHeapBuffer implements ArrayBuffer {
    private final ByteBuf data;
    private final Ownership ownership;

    ArrayHeapBuffer(int size, boolean pooled) {
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("Size too large");
        }
        data = pooled ? PooledByteBufAllocator.DEFAULT.heapBuffer(size, size).writerIndex(size)
            : Unpooled.wrappedBuffer(new byte[size], 0, size).writerIndex(size);
        ownership = new Ownership(data);
    }

    ArrayHeapBuffer(byte[] array, boolean owning) {
        data = Unpooled.wrappedBuffer(array, 0, array.length).writerIndex(array.length);
        ownership = owning ? new Ownership(data) : null;
    }

    ArrayHeapBuffer(ByteBuffer buffer, boolean owning) {
        if (!buffer.hasArray()) {
            throw new IllegalArgumentException("Buffer has no backing array");
        }
        data = Unpooled.wrappedBuffer(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
            .writerIndex(buffer.remaining());
        ownership = owning ? new Ownership(data) : null;
    }

    ArrayHeapBuffer(byte[] array, int offset, int size, boolean owning) {
        if (array == null) {
            throw new NullPointerException("array");
        }
        if (offset < 0 || size < 0 || size > array.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        data = Unpooled.wrappedBuffer(array, offset, size).writerIndex(size);
        ownership = owning ? new Ownership(data) : null;
    }

    private ArrayHeapBuffer(ByteBuf data, Ownership ownership) {
        this.data = data;
        this.ownership = ownership;
    }

    private void checkOpen() {
        if (ownership != null && !ownership.open()) {
            throw new IllegalStateException("Buffer is closed");
        }
    }

    @Override
    public ArrayBuffer acquire() {
        checkOpen();
        if (ownership == null) {
            return copy();
        }
        var retained = data.retainedSlice();
        return new ArrayHeapBuffer(retained, new Ownership(retained));
    }

    @Override
    public ArrayBuffer slice(int offset, int size) {
        checkOpen();
        int currentSize = data.readableBytes();
        if (offset < 0 || size < 0 || offset > currentSize - size) {
            throw new IndexOutOfBoundsException();
        }
        return new ArrayHeapBuffer(data.slice(offset, size).writerIndex(size), ownership);
    }

    @Override
    public ByteBuffer nio() {
        checkOpen();
        return data.nioBuffer(data.readerIndex(), data.readableBytes());
    }

    @Override
    public byte[] array() {
        checkOpen();
        return data.array();
    }

    @Override
    public int arrayOffset() {
        checkOpen();
        return data.arrayOffset() + data.readerIndex();
    }

    @Override
    public int size() {
        checkOpen();
        return data.readableBytes();
    }

    @Override
    public void close() {
        if (ownership != null) {
            ownership.close();
        }
    }

    private static final class Ownership {
        private final State state;
        private final Cleaner.Cleanable cleanable;

        private Ownership(ByteBuf owner) {
            state = new State(owner);
            cleanable = CleanerUtil.ref(this, state, State::clean);
        }

        private boolean open() {
            return state.open.get();
        }

        private void close() {
            cleanable.clean();
        }
    }

    private static final class State {
        private final ByteBuf owner;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private State(ByteBuf owner) {
            this.owner = owner;
        }

        private void clean() {
            if (open.compareAndSet(true, false)) {
                owner.release();
            }
        }
    }
}
