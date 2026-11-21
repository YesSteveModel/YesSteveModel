package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.StorageResult;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly advances the same logical owner used by the host. No storage work advances itself in tests. */
public final class PreferenceTestDriver {
    private static final AtomicLong CLOCK = new AtomicLong();

    private PreferenceTestDriver() {}

    public static void tick(PlayerPreferences preferences) {
        preferences.writes().tick(CLOCK.addAndGet(1_000_000_000L));
    }

    public static <T> StorageResult<T> await(PlayerPreferences preferences, StorageWriteBuffer.Operation<T> operation)
        throws Exception {
        return await(preferences, operation.result());
    }

    public static <T> StorageResult<T> await(PlayerPreferences preferences, CompletionStage<StorageResult<T>> stage)
        throws Exception {
        var future = stage.toCompletableFuture();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone() && System.nanoTime() < deadline) {
            tick(preferences);
            Thread.sleep(1);
        }
        tick(preferences);
        return future.get(1, TimeUnit.SECONDS);
    }

    public static <T> StorageResult<T> await(PlayerPreferences preferences, StorageQuery<T> query) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (query) {
            while (System.nanoTime() < deadline) {
                tick(preferences);
                var completion = query.poll();
                if (completion != null) {
                    if (completion.closed())
                        throw new IllegalStateException("Query consumption ended");
                    if (completion.failure() != null)
                        throw new java.util.concurrent.ExecutionException(completion.failure());
                    return completion.result();
                }
                Thread.sleep(1);
            }
        }
        throw new java.util.concurrent.TimeoutException("Storage query did not finish");
    }
}
