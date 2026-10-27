package com.elfmcys.ysm.format.schema.file;

import java.io.IOException;
import java.util.IdentityHashMap;

/** Groups exact source capabilities only for one synchronous consumer batch. */
public final class ReadPhaseSet implements AutoCloseable {
    private final IdentityHashMap<Object, AutoCloseable> phases = new IdentityHashMap<>();
    private boolean closed;
    private long generation;

    @SuppressWarnings("unchecked")
    public synchronized <T extends AutoCloseable> T acquire(Object capability, Opener<T> opener) throws IOException {
        if (closed)
            throw new java.nio.channels.ClosedChannelException();
        var existing = phases.get(capability);
        if (existing != null)
            return (T)existing;
        var phase = opener.open();
        try {
            phases.put(capability, phase);
            return phase;
        } catch (RuntimeException | Error failure) {
            try {
                phase.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public synchronized long generation() {
        return generation;
    }

    /** The batch owner ends a failed stage before invoking an existing fallback. */
    public synchronized void endPhase() throws IOException {
        if (closed)
            throw new java.nio.channels.ClosedChannelException();
        release();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed)
            return;
        closed = true;
        release();
    }

    private void release() throws IOException {
        IOException failure = null;
        for (var phase : phases.values()) {
            try {
                phase.close();
            } catch (Exception cleanup) {
                if (failure == null)
                    failure = new IOException("Failed to close a read batch", cleanup);
                else
                    failure.addSuppressed(cleanup);
            }
        }
        phases.clear();
        generation++;
        if (failure != null) {
            closed = true;
            throw failure;
        }
    }

    @FunctionalInterface
    public interface Opener<T> {
        T open() throws IOException;
    }
}
