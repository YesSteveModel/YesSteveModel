package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.NativeArtifactIndex;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.File;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/**
 * Pins a business index to one guarded cache region. Synchronous calls belong to file or catalog workers; database
 * callbacks must not wait for this adapter.
 *
 * <p>
 * Ownership: the cache retains this capability, and DatabaseWork closes its lazy resource after draining work.
 *
 * <p>
 * Seam: consumes [database execution](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public final class StorageIndex<R extends NativeArtifactIndex> {
    private final AtomicSharedCache cache;
    private final DatabaseWork.Resource<R> resource;

    StorageIndex(AtomicSharedCache cache, BiFunction<Path, NativeCancellation, R> factory) {
        this.cache = cache;
        resource = cache.databaseWork().createResource(cancelled -> {
            try {
                var guard = cache.guard();
                guard.verify();
                return factory.apply(cache.layoutRoot(), cancelled);
            } catch (IOException | SecurityException failure) {
                throw new StorageResult.OpenException(accessFailure());
            }
        });
    }

    public <T> StorageResult<T> execute(DatabaseWork.Operation<R, T> operation) throws IOException {
        return execute(operation, true);
    }

    private <T> StorageResult<T> execute(DatabaseWork.Operation<R, T> operation, boolean mutation) throws IOException {
        var job = submitJob(operation, mutation);
        try {
            return job.result().toCompletableFuture().get();
        } catch (InterruptedException failure) {
            job.cancel();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Storage index operation interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error error)
                throw error;
            if (failure.getCause() instanceof RuntimeException error)
                throw error;
            if (failure.getCause() instanceof IOException error)
                throw error;
            throw new IOException("Storage index operation failed", failure.getCause());
        }
    }

    public <T> CompletionStage<StorageResult<T>> submit(DatabaseWork.Operation<R, T> operation) {
        try {
            return submitJob(operation, true).result();
        } catch (IOException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    private <T> DatabaseWork.Job<T> submitJob(DatabaseWork.Operation<R, T> operation, boolean mutation)
        throws IOException {
        var use = cache.coordinator().borrow();
        try {
            var job = cache.databaseWork().submitTask(cancelled -> run(operation, cancelled, mutation));
            job.result().whenComplete((result, error) -> use.close());
            return job;
        } catch (RuntimeException | Error failure) {
            use.close();
            throw failure;
        }
    }

    <T> StorageResult<T> run(DatabaseWork.Operation<R, T> operation, NativeCancellation token) {
        return run(operation, token, true);
    }

    private <T> StorageResult<T> run(DatabaseWork.Operation<R, T> operation, NativeCancellation token,
        boolean mutation) {
        return resource.run((index, cancelled) -> {
            if (mutation) {
                try {
                    cache.guard().verify();
                } catch (IOException | SecurityException failure) {
                    return accessFailure();
                }
            }
            return operation.run(index, cancelled);
        }, token);
    }

    private static <T> StorageResult<T> accessFailure() {
        return new StorageResult<>(StorageResult.Outcome.FAILED, StorageResult.Failure.ACCESS_IO, false, List.of());
    }

    public static <T> StorageResult<T> require(StorageResult<T> result) throws IOException {
        if (result.failure() == StorageResult.Failure.CANCELLED || result.outcome() == StorageResult.Outcome.CANCELLED)
            throw new java.util.concurrent.CancellationException("Storage index operation cancelled");
        if (result.failure() == StorageResult.Failure.CLOSED)
            throw new java.nio.channels.ClosedChannelException();
        if (result.failure() == StorageResult.Failure.INTERNAL)
            throw new IllegalStateException("Storage index reported an internal failure");
        if (!result.succeeded())
            throw new IOException("Storage index failed: " + result.failure() + " committed=" + result.committed());
        return result;
    }

    public Optional<File> find(NativeArtifactIndex.ExactKind kind, String key) throws IOException {
        return cache.coordinator().acquire(() -> {
            var result = require(execute((index, cancelled) -> index.lookupExact(kind, key, cancelled), false));
            if (result.outcome() == StorageResult.Outcome.MISSING)
                return Optional.empty();
            var value = result.rows().get(0);
            cache.coordinator().retain(kind == NativeArtifactIndex.ExactKind.PREVIEW ? Pool.PREVIEW : Pool.REMOTE,
                value.location());
            return Optional.of(value);
        });
    }

    public void publish(NativeArtifactIndex.ExactKind kind, String key, Location location) throws IOException {
        publish(kind, key, location, ArrayBuffer.borrow(new byte[0]));
    }

    public void publish(NativeArtifactIndex.ExactKind kind, String key, Location location, UniBuffer metadata)
        throws IOException {
        cache.coordinator().acquire(() -> {
            var file = new File(location, com.elfmcys.ysm.buffer.SharedArrayBuffer.copyOf(metadata));
            require(execute((index, cancelled) -> index.publishExact(kind, key, file, cancelled)));
            return null;
        });
    }
}
