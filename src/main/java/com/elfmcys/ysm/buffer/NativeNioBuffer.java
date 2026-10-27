package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.UnsafeUtil;
import io.netty.util.internal.PlatformDependent;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class NativeNioBuffer implements NativeBuffer {
    private final ByteBuffer data;
    private final long headPtr;
    private final Ownership ownership;

    NativeNioBuffer(ByteBuffer data, boolean owning) {
        if (!data.isDirect()) {
            throw new IllegalArgumentException("Buffer is not direct");
        }
        var view = data.slice();
        this.data = view;
        headPtr = PlatformDependent.directBufferAddress(view);
        ownership = owning ? new Ownership(new Backing(data)) : null;
    }

    private NativeNioBuffer(ByteBuffer data, long headPtr, Ownership ownership) {
        this.data = data;
        this.headPtr = headPtr;
        this.ownership = ownership;
    }

    private void checkOpen() {
        if (ownership != null && !ownership.open()) {
            throw new IllegalStateException("Buffer has been closed");
        }
    }

    @Override
    public NativeBuffer acquire() {
        checkOpen();
        if (ownership == null) {
            return copy();
        }
        return new NativeNioBuffer(data.duplicate(), headPtr, ownership.acquire());
    }

    @Override
    public NativeBuffer slice(int offset, int size) {
        checkOpen();
        int currentSize = data.remaining();
        if (offset < 0 || size < 0 || offset > currentSize - size) {
            throw new IndexOutOfBoundsException();
        }
        var sliced = data.slice(offset, size);
        return new NativeNioBuffer(sliced, PlatformDependent.directBufferAddress(sliced), ownership);
    }

    @Override
    public ByteBuffer nio() {
        checkOpen();
        return data.duplicate();
    }

    @Override
    public long ptr() {
        checkOpen();
        return headPtr;
    }

    @Override
    public int size() {
        checkOpen();
        return data.remaining();
    }

    @Override
    public void close() {
        if (ownership != null) {
            ownership.close();
        }
    }

    private static final class Backing {
        private final ByteBuffer owner;
        private final AtomicInteger references = new AtomicInteger(1);

        private Backing(ByteBuffer owner) {
            this.owner = owner;
        }

        private void retain() {
            references.incrementAndGet();
        }

        private void release() {
            if (references.decrementAndGet() != 0) {
                return;
            }
            try {
                UnsafeUtil.getUnsafe().invokeCleaner(owner);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static final class Ownership {
        private final Backing backing;
        private final State state;
        private final Cleaner.Cleanable cleanable;

        private Ownership(Backing backing) {
            this.backing = backing;
            state = new State(backing);
            cleanable = CleanerUtil.ref(this, state, State::clean);
        }

        private boolean open() {
            return state.open.get();
        }

        private Ownership acquire() {
            backing.retain();
            return new Ownership(backing);
        }

        private void close() {
            cleanable.clean();
        }
    }

    private static final class State {
        private final Backing backing;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private State(Backing backing) {
            this.backing = backing;
        }

        private void clean() {
            if (open.compareAndSet(true, false)) {
                backing.release();
            }
        }
    }
}
