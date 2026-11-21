package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.util.ThreadScheduling;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Owns bounded storage scheduling and the terminal release of its registered business resources.
 *
 * <p>
 * Execution: submit does not wait for capacity or execute storage inline. One worker runs typed operations; resources
 * survive worker idle exit. Operations and completion callbacks must not wait for this domain, a host thread, or
 * file-generation work. Close seals admission, terminates queued work, requests active cancellation, waits for native
 * calls and result copies, then closes every business resource.
 *
 * <p>
 * Seam: implements [storage execution](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public final class DatabaseWork implements AutoCloseable {
    public static final int MAXIMUM_JOBS = 64;
    private final ArrayDeque<Job<?>> pending = new ArrayDeque<>();
    private final List<Resource<?>> resources = new ArrayList<>();
    private Thread worker;
    private Job<?> active;
    private boolean closed;

    /** The resource and token are synchronous borrows; the operation must not retain or close them. */
    @FunctionalInterface
    public interface Operation<R, T> {
        StorageResult<T> run(R resource, NativeCancellation cancelled);
    }

    /** Registers a lazy business owner without opening its storage or creating an idle worker. */
    public synchronized <R extends AutoCloseable> Resource<R> createResource(Function<NativeCancellation, R> factory) {
        if (closed)
            throw new IllegalStateException("Storage execution is closed");
        var resource = new Resource<R>(this, Objects.requireNonNull(factory));
        resources.add(resource);
        return resource;
    }

    /** Rejects capacity and closure explicitly; unexpected execution or delivery failures complete exceptionally. */
    public synchronized <R extends AutoCloseable, T> Job<T> submit(Resource<R> resource, Operation<R, T> operation) {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(operation);
        if (resource.owner != this)
            throw new IllegalArgumentException("Business resource belongs to another storage execution owner");
        return submitTask(token -> resource.run(operation, token));
    }

    /** Executes an already ordered, bounded domain batch on the same worker as all other database users. */
    synchronized <T> Job<T> submitTask(Function<NativeCancellation, StorageResult<T>> operation) {
        if (closed || pending.size() + (active == null ? 0 : 1) >= MAXIMUM_JOBS)
            return new Job<>(
                StorageResult.refused(closed ? StorageResult.Failure.CLOSED : StorageResult.Failure.CAPACITY));

        // Creation failure precedes queue admission and cannot leave a job without cancellation ownership.
        var token = NativeCancellation.create();
        Job<T> job;
        try {
            job = new Job<>(token, () -> operation.apply(token));
            pending.addLast(job);
        } catch (RuntimeException | Error failure) {
            token.close();
            throw failure;
        }
        notifyAll();
        if (worker == null) {
            try {
                worker = new Thread(this::run, "ysm-database-storage");
                worker.setDaemon(true);
                worker.start();
            } catch (RuntimeException | Error failure) {
                worker = null;
                pending.remove(job);
                job.abandon(null, failure);
                throw failure;
            }
        }
        return job;
    }

    private void run() {
        try {
            // Scheduling can fail before its body starts; retirement must cover that window too.
            ThreadScheduling.entry(ThreadScheduling.ThreadRole.DATABASE_STORAGE,
                ThreadScheduling.ThreadPolicy.BELOW_NORMAL, this::drain).run();
        } finally {
            List<Job<?>> abandoned = null;
            synchronized (this) {
                if (worker == Thread.currentThread()) {
                    closed = true;
                    abandoned = new ArrayList<>(pending);
                    pending.clear();
                }
            }
            if (abandoned != null) {
                try {
                    abandoned.forEach(job -> job.abandon(StorageResult.Failure.CLOSED, null));
                } finally {
                    synchronized (this) {
                        worker = null;
                        notifyAll();
                    }
                }
            }
        }
    }

    private void drain() {
        while (true) {
            final Job<?> job;
            synchronized (this) {
                job = pending.pollFirst();
                if (job == null) {
                    worker = null;
                    notifyAll();
                    return;
                }
                active = job;
            }
            try {
                job.execute();
            } finally {
                synchronized (this) {
                    active = null;
                    notifyAll();
                }
                Thread.interrupted();
            }
        }
    }

    /** Verification and closure only; waits for actual operations and their result delivery. */
    public synchronized void awaitIdle() throws InterruptedException {
        if (Thread.currentThread() == worker)
            throw new IllegalStateException("Storage worker cannot wait for itself");
        while (active != null || !pending.isEmpty())
            wait();
    }

    @Override
    public void close() {
        final List<Job<?>> cancelled;
        final Job<?> running;
        final Thread runningWorker;
        synchronized (this) {
            if (Thread.currentThread() == worker)
                throw new IllegalStateException("Storage worker cannot close itself");
            closed = true;
            cancelled = new ArrayList<>(pending);
            pending.clear();
            running = active;
            runningWorker = worker;
        }
        cancelled.forEach(job -> job.abandon(StorageResult.Failure.CLOSED, null));
        if (running != null)
            running.requestCancellation();
        if (runningWorker != null)
            runningWorker.interrupt();

        boolean interrupted = false;
        synchronized (this) {
            while (worker != null) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        RuntimeException releaseFailure = null;
        try {
            for (var resource : resources) {
                try {
                    resource.close();
                } catch (RuntimeException failure) {
                    if (releaseFailure == null)
                        releaseFailure = failure;
                    else
                        releaseFailure.addSuppressed(failure);
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
        if (releaseFailure != null)
            throw releaseFailure;
    }

    /** A lazy typed business resource; only its execution owner may borrow it or close it. */
    public static final class Resource<R extends AutoCloseable> {
        private final DatabaseWork owner;
        private final Function<NativeCancellation, R> factory;
        private R value;
        private boolean closed;

        private Resource(DatabaseWork owner, Function<NativeCancellation, R> factory) {
            this.owner = owner;
            this.factory = factory;
        }

        synchronized <T> StorageResult<T> run(Operation<R, T> operation, NativeCancellation token) {
            if (closed)
                return StorageResult.refused(StorageResult.Failure.CLOSED);
            if (token.isRequested())
                return StorageResult.refused(StorageResult.Failure.CANCELLED);
            if (value == null)
                value = Objects.requireNonNull(factory.apply(token), "Business resource factory returned null");
            return Objects.requireNonNull(operation.run(value, token), "Storage operation returned null");
        }

        private synchronized void close() {
            if (closed)
                return;
            closed = true;
            if (value != null) {
                try {
                    value.close();
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("Business resource close failed", failure);
                } finally {
                    value = null;
                }
            }
        }
    }

    public final class Job<T> {
        private final NativeCancellation token;
        private java.util.function.Supplier<StorageResult<T>> operation;
        private final CompletableFuture<StorageResult<T>> completion = new CompletableFuture<>();
        private final CompletionStage<StorageResult<T>> resultStage = completion.minimalCompletionStage();
        private boolean tokenClosed;

        private Job(StorageResult<T> rejection) {
            token = null;
            tokenClosed = true;
            completion.complete(rejection);
        }

        private Job(NativeCancellation token, java.util.function.Supplier<StorageResult<T>> operation) {
            this.token = token;
            this.operation = operation;
        }

        public CompletionStage<StorageResult<T>> result() {
            return resultStage;
        }

        /** Requests cancellation; an active operation still reports its actual commit outcome. */
        public void cancel() {
            final boolean removed;
            synchronized (DatabaseWork.this) {
                removed = pending.remove(this);
                if (removed)
                    DatabaseWork.this.notifyAll();
            }
            if (removed)
                abandon(StorageResult.Failure.CANCELLED, null);
            else
                requestCancellation();
        }

        private synchronized void requestCancellation() {
            if (!tokenClosed)
                token.request();
        }

        private synchronized void releaseToken() {
            if (!tokenClosed) {
                tokenClosed = true;
                token.close();
            }
        }

        private void abandon(StorageResult.Failure reason, Throwable cause) {
            operation = null;
            releaseToken();
            if (cause == null)
                completion.complete(StorageResult.refused(reason));
            else
                completion.completeExceptionally(cause);
        }

        private void execute() {
            StorageResult<T> result = null;
            Throwable failed = null;
            try {
                result = token.isRequested() ? StorageResult.refused(StorageResult.Failure.CANCELLED) : operation.get();
            } catch (StorageResult.OpenException failure) {
                var opened = failure.result();
                result = new StorageResult<>(opened.outcome(), opened.failure(), opened.committed(), List.of());
            } catch (RuntimeException | Error failure) {
                failed = failure;
            } finally {
                operation = null;
                releaseToken();
            }
            if (failed == null)
                completion.complete(result);
            else
                completion.completeExceptionally(failed);
            if (failed instanceof Error fatal)
                throw fatal;
        }
    }
}
