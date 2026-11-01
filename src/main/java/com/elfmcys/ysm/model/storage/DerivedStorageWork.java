package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.util.ThreadScheduling;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Owns bounded optional persistence work through the end of its last input borrow.
 *
 * <p><b>Invariant:</b> Job cancellation is a logical terminal state. A running job retains its inputs
 * and capacity until its Work returns. Persistence failure does not undo an already usable resource.
 *
 * <p><b>Execution:</b> One worker runs accepted Work. Close seals admission, interrupts active work
 * and waits for that worker; Work must not call close on its own owner or wait for owner-thread progress.
 *
 * <p><b>Seam:</b> [optional persistence ownership](docs/architecture/model-management/storage/overview.md#精确缓存与持久工作).
 */
public final class DerivedStorageWork implements AutoCloseable {
    public static final int DEFAULT_MAXIMUM_JOBS = 16;

    private final int maximumJobs;
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private Thread worker;
    private Job running;
    private boolean closed;
    private int jobs;
    private long succeeded;
    private long failed;
    private long cancelled;
    private long shutdown;

    public DerivedStorageWork() {
        this(DEFAULT_MAXIMUM_JOBS);
    }

    public DerivedStorageWork(int maximumJobs) {
        if (maximumJobs <= 0)
            throw new IllegalArgumentException("Derived storage job limit must be positive");
        this.maximumJobs = maximumJobs;
    }

    /**
     * Offers optional persistence without waiting for its I/O.
     *
     * @param work the non-null operation, which retains its inputs until its run method returns
     * @return the accepted Job, or null when closed or full; null leaves all input responsibility with the caller
     */
    public synchronized Job submit(Work work) {
        Objects.requireNonNull(work, "work");
        if (closed || jobs == maximumJobs)
            return null;
        var job = new Job(work);
        pending.addLast(job);
        jobs++;
        if (worker == null) {
            worker = new Thread(ThreadScheduling.entry(ThreadScheduling.ThreadRole.DERIVED_STORAGE,
                ThreadScheduling.ThreadPolicy.BELOW_NORMAL, this::run), "ysm-derived-storage");
            worker.setDaemon(true);
            worker.start();
        }
        return job;
    }

    private void run() {
        boolean drained = false;
        try {
            while (true) {
                final Job job;
                synchronized (this) {
                    job = pending.pollFirst();
                    if (job == null) {
                        worker = null;
                        drained = true;
                        notifyAll();
                        return;
                    }
                    running = job;
                }
                try {
                    job.work.run(replacement -> commit(job, replacement));
                } catch (IOException | RuntimeException failure) {
                    synchronized (this) {
                        if (job.outcome == null) {
                            job.failure = failure;
                            finish(job, Outcome.FAILED);
                        }
                    }
                } finally {
                    synchronized (this) {
                        // Terminal is not release: a cancelled JNI/I/O borrow can still be returning.
                        if (job.outcome == null)
                            finish(job, Outcome.FAILED);
                        job.work = null;
                        running = null;
                        release(job);
                    }
                    Thread.interrupted();
                }
            }
        } finally {
            if (!drained) {
                synchronized (this) {
                    closed = true;
                    for (var job : pending) {
                        finish(job, Outcome.SHUTDOWN);
                        job.work = null;
                        release(job);
                    }
                    pending.clear();
                    worker = null;
                    notifyAll();
                }
            }
        }
    }

    private synchronized void commit(Job job, AtomicSharedCache.AtomicReplacement replacement) throws IOException {
        if (job.outcome != null || closed)
            throw new IOException("Derived storage commit capability has retired");
        replacement.run();
        finish(job, Outcome.SUCCEEDED);
    }

    private void finish(Job job, Outcome outcome) {
        job.outcome = outcome;
        switch (outcome) {
            case SUCCEEDED -> succeeded++;
            case FAILED -> failed++;
            case CANCELLED -> cancelled++;
            case SHUTDOWN -> shutdown++;
        }
    }

    private void release(Job job) {
        jobs--;
        notifyAll();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(jobs, succeeded, failed, cancelled, shutdown, closed);
    }

    /** Verification and process owners only; resource preparation never waits here. */
    public synchronized void awaitIdle() throws InterruptedException {
        while (jobs != 0)
            wait();
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (this) {
            closed = true;
            for (var job : pending) {
                finish(job, Outcome.SHUTDOWN);
                job.work = null;
                release(job);
            }
            pending.clear();
            if (running != null && running.outcome == null)
                finish(running, Outcome.SHUTDOWN);
            if (worker != null)
                worker.interrupt();
            while (worker != null) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    public enum Outcome {
        SUCCEEDED, FAILED, CANCELLED, SHUTDOWN
    }

    public record Snapshot(int jobs, long succeeded, long failed, long cancelled, long shutdown,
        boolean closed) {
    }

    /**
     * Prepares and validates the output before invoking the supplied commit gate.
     * Only the final prepared atomic replacement belongs inside the gate. I/O failures remain storage failures;
     * the worker records IOException and RuntimeException as FAILED. Interruption requests cancellation but
     * does not permit another thread to close data still borrowed by this operation.
     */
    @FunctionalInterface
    public interface Work {
        void run(AtomicSharedCache.CommitGate gate) throws IOException;
    }

    public final class Job {
        private Work work;
        private Outcome outcome;
        private Throwable failure;

        private Job(Work work) {
            this.work = work;
        }

        public Outcome outcome() {
            synchronized (DerivedStorageWork.this) {
                return outcome;
            }
        }

        public Throwable failure() {
            synchronized (DerivedStorageWork.this) {
                return failure;
            }
        }

        public boolean cancel() {
            synchronized (DerivedStorageWork.this) {
                if (outcome != null)
                    return false;
                finish(this, Outcome.CANCELLED);
                if (pending.remove(this)) {
                    work = null;
                    release(this);
                } else {
                    worker.interrupt();
                }
                return true;
            }
        }
    }
}
