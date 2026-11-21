package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/** New-layout typed database access. Callers hold the root mutation gate for changes affecting file ownership. */
public final class CacheDatabase<R extends AutoCloseable> {
    private final AtomicSharedCache cache;
    private final DatabaseWork.Resource<R> resource;

    CacheDatabase(AtomicSharedCache cache, BiFunction<Path, NativeCancellation, R> factory) {
        this.cache = cache;
        resource = cache.databaseWork().createResource(token -> factory.apply(cache.layoutRoot(), token));
    }

    public <T> StorageResult<T> execute(DatabaseWork.Operation<R, T> operation) throws IOException {
        return execute(operation, true);
    }

    /** Native database access owns its path checks; a lookup does not qualify any candidate payload path. */
    public <T> StorageResult<T> read(DatabaseWork.Operation<R, T> operation) throws IOException {
        return execute(operation, false);
    }

    private <T> StorageResult<T> execute(DatabaseWork.Operation<R, T> operation, boolean mutation) throws IOException {
        var job = enqueue(operation, mutation);
        try {
            return job.result().toCompletableFuture().get();
        } catch (InterruptedException failure) {
            job.cancel();
            // A borrowed cleanup window cannot end while its cancelled transaction still runs.
            try {
                job.result().toCompletableFuture().join();
            } catch (java.util.concurrent.CompletionException terminal) {
                if (terminal.getCause() instanceof Error fatal)
                    throw fatal;
                failure.addSuppressed(terminal.getCause());
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new InterruptedIOException("Cache database operation interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error error)
                throw error;
            if (failure.getCause() instanceof RuntimeException error)
                throw error;
            if (failure.getCause() instanceof IOException error)
                throw error;
            throw new IOException("Cache database operation failed", failure.getCause());
        }
    }

    /** Holds admission through physical execution and completion, including caller cancellation. */
    public <T> CompletionStage<StorageResult<T>> submit(DatabaseWork.Operation<R, T> operation) throws IOException {
        return enqueue(operation, true).result();
    }

    private <T> DatabaseWork.Job<T> enqueue(DatabaseWork.Operation<R, T> operation, boolean mutation)
        throws IOException {
        var use = cache.coordinator().borrow();
        try {
            var job = cache.databaseWork().submit(resource, (db, token) -> {
                if (mutation) {
                    try {
                        cache.guard().verify();
                    } catch (IOException | SecurityException failure) {
                        return new StorageResult<T>(StorageResult.Outcome.FAILED, StorageResult.Failure.ACCESS_IO,
                            false, java.util.List.of());
                    }
                }
                return operation.run(db, token);
            });
            job.result().whenComplete((result, error) -> use.close());
            return job;
        } catch (RuntimeException | Error failure) {
            use.close();
            throw failure;
        }
    }
}
