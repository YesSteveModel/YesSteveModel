package com.elfmcys.ysm.natives;

import java.lang.ref.Reference;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * Owns a shared, one-way native cancellation state.
 *
 * <p>
 * Execution: request and query may overlap consumers. Use {@link #borrow} for each native call; inherited {@link #get}
 * does not acquire a borrow. Close rejects new borrows and waits for active borrows, without requesting cancellation or
 * waiting for independently retained native work.
 *
 * <p>
 * Seam: implements [cooperative cancellation](docs/architecture/native-runtime/overview.md#协作取消).
 */
public final class NativeCancellation extends NativeObject {
    private boolean closing;
    private int borrowers;

    private NativeCancellation(State state) {
        super(state);
    }

    /**
     * @return a new, unrequested state owned by the caller
     * @throws IllegalStateException if native creation fails
     */
    public static NativeCancellation create() {
        Function<State, NativeCancellation> factory = NativeCancellation::new;
        long handle = nCreate();
        if (handle == 0) {
            throw new IllegalStateException("Failed to create native cancellation state");
        }
        return adopt(handle, factory);
    }

    /**
     * Requests cancellation; repeated requests succeed without resetting the state.
     *
     * @throws IllegalStateException if closing has started or native delivery fails
     */
    public void request() {
        borrow(handle -> {
            if (!nRequest(handle)) {
                throw new IllegalStateException("Failed to request native cancellation");
            }
            return null;
        });
    }

    /**
     * @return a snapshot; checks started after request returns observe true
     * @throws IllegalStateException if closing has started or native delivery fails
     */
    public boolean isRequested() {
        return borrow(handle -> switch (nQuery(handle)) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IllegalStateException("Failed to query native cancellation");
        });
    }

    /**
     * Calls operation synchronously with a live typed handle, without holding the borrow gate.
     *
     * <p>
     * Requires: operation must not retain the raw handle or call or wait for this owner's close. A native consumer that
     * retains state beyond the callback must acquire shared ownership while this borrow is valid. The callback may
     * request cancellation or nest another borrow.
     *
     * <p>
     * Ownership: this wrapper remains reachable until the callback ends, including exceptional exit. The callback
     * receives no release authority.
     *
     * @return the callback result; exceptions propagate after the borrow ends
     * @throws IllegalStateException if closing has started
     */
    public <T> T borrow(LongFunction<T> operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (this) {
            if (closing) {
                throw new IllegalStateException("Native cancellation state is closing");
            }
            borrowers++;
        }
        try {
            return operation.apply(super.get());
        } finally {
            synchronized (this) {
                if (--borrowers == 0) {
                    notifyAll();
                }
            }
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Rejects new borrows, waits for active callbacks, and releases the handle exactly once.
     *
     * <p>
     * Requires: the caller is outside this token's borrow callbacks and does not prevent them from ending. Close is
     * idempotent. Interrupted waits finish cleanup and restore interruption.
     */
    @Override
    public void close() {
        boolean interrupted = false;
        try {
            synchronized (this) {
                closing = true;
                while (borrowers != 0) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                super.close();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static native long nCreate();

    private static native boolean nRequest(long handle);

    private static native int nQuery(long handle);
}
