package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.Closeable;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Owns one typed native handle released by explicit close or Cleaner.
 *
 * <p>Execution: the owner must exclude close from every handle borrow.
 * The atomic cleanup state prevents duplicate destruction; it does not make get-then-use atomic.
 * JNI callers retain this wrapper through their synchronous call.
 *
 * <p>Ownership: raw handles and derived views do not acquire release authority or retain this wrapper.
 *
 * <p>Seam: implements opaque-handle lifetime in the
 * [JNI ownership contract](docs/architecture/native-runtime/overview.md#jni-能力与结果交付).
 */
public class NativeObject implements Closeable {
    private final State state;
    private final Cleaner.Cleanable cleanable;

    /**
     * @param ptr an exclusively owned, live handle compatible with nDestroy; never an arbitrary address
     */
    public NativeObject(long ptr) {
        this(new State(ptr));
    }

    protected NativeObject(State state) {
        this.state = state;
        cleanable = CleanerUtil.ref(this, state, State::clean);
    }

    /**
     * Adopts an owning native result with construction rollback.
     *
     * <p>Requires: ptr has one release obligation. The factory uses the supplied state and exposes
     * its wrapper only after construction succeeds.
     *
     * <p>Failure: wrapper or Cleaner registration failure destroys the handle through the same
     * cleanup state. The caller must not release ptr after this method takes ownership.
     *
     * @return the complete owner; ownership transfers on entry, including exceptional exit
     */
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

    /**
     * @return a borrowed handle, valid only while the owner excludes close and remains reachable
     * @throws IllegalStateException if cleanup has already revoked the handle
     */
    public long get() {
        if (!state.open.get()) {
            throw new IllegalStateException("Native object is closed");
        }
        return state.ptr;
    }

    /**
     * Releases this handle once; repeated calls do not destroy it again.
     *
     * <p>Requires: all handle and derived-view borrows have ended.
     * This method does not wait for native work or for another thread that already called get().
     */
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
