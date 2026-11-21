package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.StorageResult;
import org.jetbrains.annotations.Nullable;

/**
 * Owns consumption of one immutable query result. Queued storage work holds only a weak reference to this handle.
 * Polling belongs to the business owner's tick, never to animation or render workers.
 *
 * <p>
 * Close and publication share one decision point. Closing does not revoke snapshots already returned by poll and does
 * not prove that an active native call has ended.
 */
public final class StorageQuery<T> implements AutoCloseable {
    public record Completion<T>(@Nullable StorageResult<T> result, @Nullable Throwable failure, boolean closed) {
    }

    private Completion<T> completion;
    private boolean closed;

    /** Returns null while waiting. Failure and ended consumption are distinct from a successful Missing result. */
    public synchronized @Nullable Completion<T> poll() {
        return closed ? new Completion<>(null, null, true) : completion;
    }

    synchronized boolean active() {
        return !closed;
    }

    synchronized void publish(StorageResult<T> result, Throwable failure) {
        if (!closed)
            completion = new Completion<>(result, failure, false);
    }

    @Override
    public synchronized void close() {
        closed = true;
        completion = null;
    }
}
