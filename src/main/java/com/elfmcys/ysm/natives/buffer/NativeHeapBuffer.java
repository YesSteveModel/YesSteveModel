package com.elfmcys.ysm.natives.buffer;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.util.CleanerUtil;
import io.netty.util.internal.PlatformDependent;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NativeHeapBuffer implements NativeBuffer {
    private final long headPtr;
    private final ByteBuffer data;
    private final Ownership ownership;

    public NativeHeapBuffer(int size) {
        this(size, 0);
    }

    public NativeHeapBuffer(int size, int alignment) {
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("Size too large");
        }
        var allocated = nAlloc(size, alignment);
        if (allocated == null) {
            throw new OutOfMemoryError();
        }
        var adopted = adopt(allocated);
        headPtr = adopted.headPtr;
        data = adopted.data;
        ownership = adopted.ownership;
    }

    /**
     * mimalloc cannot identify an arbitrary pointer's heap region; callers must pass its owning base view.
     */
    public NativeHeapBuffer(ByteBuffer ownedMem) {
        var adopted = adopt(ownedMem);
        headPtr = adopted.headPtr;
        data = adopted.data;
        ownership = adopted.ownership;
    }

    /** Consumes an owning base view, including when wrapper or Cleaner registration fails. */
    public static NativeHeapBuffer adopt(ByteBuffer ownedMem) {
        if (!ownedMem.isDirect()) {
            throw new IllegalArgumentException("Buffer is not direct");
        }
        var base = PlatformDependent.directBufferAddress(ownedMem);
        Backing backing = null;
        State state = null;
        try {
            backing = new Backing(base);
            state = new State(backing);
            var ownership = new Ownership(backing, state);
            return new NativeHeapBuffer(base + ownedMem.position(), ownedMem.slice().order(ByteOrder.nativeOrder()),
                ownership);
        } catch (RuntimeException | Error failure) {
            if (state != null) {
                state.clean();
            } else if (backing != null) {
                backing.release();
            } else {
                nFree(base);
            }
            throw failure;
        }
    }

    private NativeHeapBuffer(long headPtr, ByteBuffer data, Ownership ownership) {
        this.headPtr = headPtr;
        this.data = data;
        this.ownership = ownership;
    }

    private void checkOpen() {
        if (!ownership.open()) {
            throw new IllegalStateException("Buffer has been closed");
        }
    }

    @Override
    public NativeBuffer acquire() {
        checkOpen();
        return new NativeHeapBuffer(headPtr, data.duplicate(), ownership.acquire());
    }

    @Override
    public NativeHeapBuffer slice(int offset, int size) {
        checkOpen();
        if (offset < 0 || size < 0 || offset > data.remaining() - size) {
            throw new IndexOutOfBoundsException();
        }
        return new NativeHeapBuffer(headPtr + offset, data.slice(offset, size).order(ByteOrder.nativeOrder()),
            ownership);
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
        ownership.close();
    }

    private static final class Backing {
        private final long ptr;
        private final AtomicInteger references = new AtomicInteger(1);

        private Backing(long ptr) {
            this.ptr = ptr;
        }

        private void retain() {
            references.incrementAndGet();
        }

        private void release() {
            if (references.decrementAndGet() == 0) {
                nFree(ptr);
            }
        }
    }

    private static final class Ownership {
        private final Backing backing;
        private final State state;
        private final Cleaner.Cleanable cleanable;

        private Ownership(Backing backing) {
            this(backing, new State(backing));
        }

        private Ownership(Backing backing, State state) {
            this.backing = backing;
            this.state = state;
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

    private static native ByteBuffer nAlloc(int size, int alignment);

    private static native void nFree(long addr);
}
