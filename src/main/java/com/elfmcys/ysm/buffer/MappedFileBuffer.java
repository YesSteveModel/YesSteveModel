package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.UnsafeUtil;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only mapped buffer with Cleaner-backed ownership. */
class MappedFileBuffer implements NativeBuffer {
    private final ByteBuffer data;
    private final long headPtr;
    private final Ownership ownership;

    MappedFileBuffer(FileChannel file, long offset, long size) throws IOException {
        var mapped = file.map(FileChannel.MapMode.READ_ONLY, offset, size);
        data = mapped.duplicate();
        headPtr = PlatformDependent.directBufferAddress(mapped);
        ownership = new Ownership(new Backing(mapped));
    }

    private MappedFileBuffer(ByteBuffer data, long headPtr, Ownership ownership) {
        this.data = data;
        this.headPtr = headPtr;
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
        return new MappedFileBuffer(data.duplicate(), headPtr, ownership.acquire());
    }

    @Override
    public MappedFileBuffer slice(int offset, int size) {
        checkOpen();
        int currentSize = data.remaining();
        if (offset < 0 || size < 0 || offset > currentSize - size) {
            throw new IndexOutOfBoundsException();
        }
        var sliced = data.slice(offset, size);
        return new MappedFileBuffer(sliced, PlatformDependent.directBufferAddress(sliced), ownership);
    }

    @Override
    public ByteBuffer nio() {
        checkOpen();
        return data.asReadOnlyBuffer();
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
        private final MappedByteBuffer mapped;
        private final AtomicInteger references = new AtomicInteger(1);

        private Backing(MappedByteBuffer mapped) {
            this.mapped = mapped;
        }

        private void retain() {
            references.incrementAndGet();
        }

        private void release() {
            if (references.decrementAndGet() == 0) {
                UnsafeUtil.getUnsafe().invokeCleaner(mapped);
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
