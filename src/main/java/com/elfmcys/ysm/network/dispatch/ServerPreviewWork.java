package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Server-wide, request-only image budget; no state survives an accepted request. */
public final class ServerPreviewWork implements AutoCloseable {
    public static final int MAX_REQUESTS = 2;
    public static final long MAX_ENCODED_BYTES = 32L * 1024 * 1024;
    public static final long MAX_ASSEMBLY_BYTES = 16L * 1024 * 1024;
    private final ThreadPoolExecutor worker =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), task -> {
            var thread = new Thread(ThreadScheduling.entry(ThreadRole.SERVER_PREVIEW, ThreadPolicy.BELOW_NORMAL, task),
                "YSM Preview Image Worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private int requests;
    private long bytes;
    private boolean query;
    private boolean closed;

    public synchronized Lease acquire(boolean list, int encodedBytes) {
        if (closed || requests == MAX_REQUESTS || list && query || encodedBytes < 0
            || bytes + encodedBytes > MAX_ASSEMBLY_BYTES)
            return null;
        requests++;
        bytes += encodedBytes;
        if (list)
            query = true;
        return new Lease(list, encodedBytes);
    }

    public synchronized int activeRequests() {
        return requests;
    }

    public synchronized long retainedEncodedBytes() {
        return bytes;
    }

    public final class Lease implements AutoCloseable {
        private final boolean list;
        private final int encodedBytes;
        private int references = 1;

        private Lease(boolean list, int encodedBytes) {
            this.list = list;
            this.encodedBytes = encodedBytes;
        }

        public void execute(Runnable task) {
            synchronized (ServerPreviewWork.this) {
                if (references == 0)
                    throw new IllegalStateException("Preview request retired");
                references++;
            }
            try {
                worker.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        release();
                    }
                });
            } catch (RuntimeException failure) {
                release();
                throw failure;
            }
        }

        public Runnable retain() {
            synchronized (ServerPreviewWork.this) {
                if (references == 0)
                    throw new IllegalStateException("Preview request retired");
                references++;
            }
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true))
                    release();
            };
        }

        private void release() {
            synchronized (ServerPreviewWork.this) {
                if (--references == 0) {
                    requests--;
                    bytes -= encodedBytes;
                    if (list)
                        query = false;
                }
            }
        }

        private boolean requestClosed;

        @Override
        public void close() {
            synchronized (ServerPreviewWork.this) {
                if (requestClosed)
                    return;
                requestClosed = true;
                release();
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        worker.shutdown();
        var interrupted = false;
        while (!worker.isTerminated()) {
            try {
                worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
