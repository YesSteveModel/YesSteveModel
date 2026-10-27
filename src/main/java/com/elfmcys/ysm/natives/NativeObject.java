package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.Closeable;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class NativeObject implements Closeable {
    private final State state;
    private final Cleaner.Cleanable cleanable;

    public NativeObject(long ptr) {
        this(new State(ptr));
    }

    protected NativeObject(State state) {
        this.state = state;
        cleanable = CleanerUtil.ref(this, state, State::clean);
    }

    /** The factory must finish construction before exposing the owner. */
    protected static <T extends NativeObject> T adopt(long ptr, Function<State, T> factory) {
        State state = null;
        try {
            state = new State(ptr);
            return factory.apply(state);
        } catch (RuntimeException | Error failure) {
            if (state == null) {
                nDestroy(ptr);
            } else {
                state.clean();
            }
            throw failure;
        }
    }

    public long get() {
        if (!state.open.get()) {
            throw new IllegalStateException("Native object is closed");
        }
        return state.ptr;
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    protected static final class State {
        private final long ptr;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private State(long ptr) {
            this.ptr = ptr;
        }

        private void clean() {
            if (open.compareAndSet(true, false)) {
                nDestroy(ptr);
            }
        }
    }

    private static native void nDestroy(long ptr);
}
