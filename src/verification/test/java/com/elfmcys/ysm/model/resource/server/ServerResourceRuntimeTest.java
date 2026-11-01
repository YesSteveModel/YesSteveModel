package com.elfmcys.ysm.model.resource.server;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.PreviewStore;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.elfmcys.ysm.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerResourceRuntimeTest {
    @TempDir
    Path temp;

    @Test
    void idleWorkersRetireAndLaterReadsCreateNewWorkers() throws Exception {
        var firstWorker = new AtomicReference<Thread>();
        var secondWorker = new AtomicReference<Thread>();
        var first = source(identity(9, 19), bytes(1), () -> firstWorker.set(Thread.currentThread()));
        var second = source(identity(10, 20), bytes(2), () -> secondWorker.set(Thread.currentThread()));
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        })) {
            var field = ServerResourceRuntime.class.getDeclaredField("workers");
            field.setAccessible(true);
            var workers = (ThreadPoolExecutor)field.get(runtime);
            assertEquals(30, workers.getKeepAliveTime(TimeUnit.SECONDS));
            workers.setKeepAliveTime(50, TimeUnit.MILLISECONDS);

            try (var lease = finish(runtime, runtime.acquireChunk(first.content(), first.chunk()))) {
                assertArrayEquals(bytes(1), copy(lease));
            }
            var retired = firstWorker.get();
            retired.join(5_000);
            assertFalse(retired.isAlive());
            assertEquals(0, workers.getPoolSize());

            try (var lease = finish(runtime, runtime.acquireChunk(second.content(), second.chunk()))) {
                assertArrayEquals(bytes(2), copy(lease));
            }
            assertNotSame(retired, secondWorker.get());
        }
    }

    @Test
    void idleEvictionDropsOnlyCacheReachabilityWhileAnActiveLeaseStaysValid() throws Exception {
        var now = new AtomicLong();
        var source = source(identity(1, 11), bytes(1, 2, 3));
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, now::get, 16)) {
            var lease = finish(runtime, runtime.acquireChunk(source.content(), source.chunk()));
            assertEquals(1, runtime.cachedCount());

            now.set(ServerResourceRuntime.IDLE_NANOS - 1);
            runtime.tick();
            assertEquals(1, runtime.cachedCount());
            now.incrementAndGet();
            runtime.tick();

            assertEquals(0, runtime.cachedCount());
            assertArrayEquals(bytes(1, 2, 3), copy(lease));
            lease.close();
        }
    }

    @Test
    void bytePressureEvictsTheLruCacheEntryWithoutClosingEitherLease() throws Exception {
        var now = new AtomicLong();
        var first = source(identity(2, 12), bytes(1, 2, 3));
        var second = source(identity(3, 13), bytes(4, 5, 6));
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, now::get, 5)) {
            var firstLease = finish(runtime, runtime.acquireChunk(first.content(), first.chunk()));
            now.incrementAndGet();
            var secondLease = finish(runtime, runtime.acquireChunk(second.content(), second.chunk()));

            assertEquals(1, runtime.cachedCount());
            assertEquals(3, runtime.cachedBytes());
            assertArrayEquals(bytes(1, 2, 3), copy(firstLease));
            assertArrayEquals(bytes(4, 5, 6), copy(secondLease));

            var firstAgain = finish(runtime, runtime.acquireChunk(first.content(), first.chunk()));
            assertEquals(2, first.reads().get());
            firstAgain.close();
            firstLease.close();
            secondLease.close();
        }
    }

    @Test
    void oversizedChunkIsDeliveredWithoutEnteringTheResidentCache() throws Exception {
        var source = source(identity(4, 14), bytes(1, 2, 3));
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, () -> 0L, 2)) {
            var lease = finish(runtime, runtime.acquireChunk(source.content(), source.chunk()));

            assertEquals(0, runtime.cachedCount());
            assertEquals(0, runtime.cachedBytes());
            assertArrayEquals(bytes(1, 2, 3), copy(lease));
            lease.close();
        }
    }

    @Test
    void pendingReadsCoalesceOnlyForTheSameExactSourceButReadyBytesAreReusable() throws Exception {
        var identity = identity(5, 15);
        var first = source(identity, bytes(7, 8, 9));
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, () -> 0L, 16)) {
            var sameSourceA = runtime.acquireChunk(first.content(), first.chunk());
            var sameSourceB = runtime.acquireChunk(first.content(), first.chunk());
            var firstLease = finish(runtime, sameSourceA);
            var secondLease = finish(runtime, sameSourceB);
            assertEquals(1, first.reads().get());

            var equivalent = source(identity, bytes(7, 8, 9));
            var reused = finish(runtime, runtime.acquireChunk(equivalent.content(), equivalent.chunk()));
            assertEquals(0, equivalent.reads().get());
            assertEquals(3, runtime.cachedBytes());

            firstLease.close();
            secondLease.close();
            reused.close();
        }
    }

    @Test
    void concurrentEquivalentExactSourcesDoNotDoubleCountOneReadyEntry() throws Exception {
        var identity = identity(6, 16);
        var first = source(identity, bytes(1, 1, 1));
        var second = source(identity, bytes(1, 1, 1));
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, () -> 0L, 16)) {
            var a = runtime.acquireChunk(first.content(), first.chunk());
            var b = runtime.acquireChunk(second.content(), second.chunk());
            var firstLease = finish(runtime, a);
            var secondLease = finish(runtime, b);

            assertEquals(1, first.reads().get());
            assertEquals(1, second.reads().get());
            assertEquals(1, runtime.cachedCount());
            assertEquals(3, runtime.cachedBytes());
            firstLease.close();
            secondLease.close();
        }
    }

    @Test
    void acquireAfterRuntimeCloseFailsWithoutQueuingWork() {
        var source = source(identity(7, 17), bytes(1));
        var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        });
        runtime.close();

        var failure = runtime.acquireChunk(source.content(), source.chunk());
        assertTrue(failure.isCompletedExceptionally());
        assertThrows(Exception.class, () -> failure.get(1, TimeUnit.SECONDS));
        assertEquals(0, source.reads().get());
    }

    @Test
    void closeWaitsForAnInterruptedReadToActuallyExit() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = source(identity(8, 18), bytes(1), () -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
        });
        var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        });
        var acquired = runtime.acquireChunk(source.content(), source.chunk());
        runtime.tick();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var finished = new CompletableFuture<Void>();
        var closer = new Thread(() -> {
            try {
                runtime.close();
                finished.complete(null);
            } catch (Throwable failure) {
                finished.completeExceptionally(failure);
            }
        });
        closer.start();
        try {
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(finished.isDone());
            assertTrue(acquired.isCompletedExceptionally());
            assertTrue(runtime.acquireChunk(source.content(), source.chunk()).isCompletedExceptionally());
        } finally {
            release.countDown();
            closer.join(5_000);
            runtime.close();
        }
        finished.get(5, TimeUnit.SECONDS);
    }

    @Test
    void previewReadsShareOneFlightAndCancellationReleasesOnlyOneInterest() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(21);
        writePreview(id, bytes(1, 2, 3));
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            var cancelled = runtime.acquirePreview(id);
            var first = runtime.acquirePreview(id);
            var second = runtime.acquirePreview(id);
            runtime.tick();
            assertEquals(1, workers.size());
            assertTrue(cancelled.cancel(false));
            workers.runFirst();
            runtime.tick();
            try (var a = first.join().orElseThrow(); var b = second.join().orElseThrow()) {
                assertNotSame(a, b);
                assertArrayEquals(bytes(1, 2, 3), copy(a));
                a.close();
                assertArrayEquals(bytes(1, 2, 3), copy(b));
            }
            assertTrue(cancelled.isCancelled());
            assertEquals(1, runtime.cachedCount());
            var hit = runtime.acquirePreview(id);
            runtime.tick();
            try (var lease = hit.join().orElseThrow()) {
                assertArrayEquals(bytes(1, 2, 3), copy(lease));
            }
            assertEquals(0, workers.size());
        }
    }

    @Test
    void chunkAndPreviewShareBudgetAndIdleEvictionWithoutRevokingLeases() throws Exception {
        var now = new AtomicLong();
        var workers = new ControlledWorkers();
        var chunk = source(identity(22, 32), bytes(1, 2, 3));
        var id = hash(22);
        writePreview(id, bytes(4, 5, 6));
        try (var runtime = runtime(workers, now, 5)) {
            var chunkResult = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            var chunkLease = chunkResult.join();
            now.incrementAndGet();
            var previewResult = runtime.acquirePreview(id);
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            var previewLease = previewResult.join().orElseThrow();
            assertEquals(1, runtime.cachedCount());
            assertEquals(3, runtime.cachedBytes());
            now.addAndGet(ServerResourceRuntime.IDLE_NANOS);
            runtime.tick();
            assertEquals(0, runtime.cachedCount());
            assertArrayEquals(bytes(1, 2, 3), copy(chunkLease));
            assertArrayEquals(bytes(4, 5, 6), copy(previewLease));
            chunkLease.close();
            previewLease.close();
        }
    }

    @Test
    void successfulPreviewLeaseRefreshesTheIdleDeadline() throws Exception {
        var now = new AtomicLong();
        var workers = new ControlledWorkers();
        var id = hash(23);
        writePreview(id, bytes(1));
        try (var runtime = runtime(workers, now, 16)) {
            var initial = runtime.acquirePreview(id);
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            initial.join().orElseThrow().close();
            now.set(ServerResourceRuntime.IDLE_NANOS - 1);
            var hit = runtime.acquirePreview(id);
            runtime.tick();
            hit.join().orElseThrow().close();
            now.incrementAndGet();
            runtime.tick();
            assertEquals(1, runtime.cachedCount());
            now.addAndGet(ServerResourceRuntime.IDLE_NANOS - 1);
            runtime.tick();
            assertEquals(0, runtime.cachedCount());
        }
    }

    @Test
    void missingPreviewIsNeverNegativeCachedAndBoundedInvalidMediaRemainsEncodedData() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(24);
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            var missing = runtime.acquirePreview(id);
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            assertTrue(missing.join().isEmpty());
            assertEquals(0, runtime.cachedCount());
            writePreview(id, bytes(7, 8));
            var acquired = runtime.acquirePreview(id);
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            try (var lease = acquired.join().orElseThrow()) {
                assertArrayEquals(bytes(7, 8), copy(lease));
            }
        }
    }

    @Test
    void oldCompletionCannotRemoveReplacementFlightOrRepublishItsSnapshot() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(25);
        writePreview(id, bytes(1));
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            var old = runtime.acquirePreview(id);
            runtime.tick();
            workers.runFirst();
            runtime.invalidatePreview(id);
            writePreview(id, bytes(2));
            var replacement = runtime.acquirePreview(id);
            runtime.tick();
            assertEquals(1, workers.size());
            assertEquals(1, runtime.activeFlightCount());
            assertEquals(0, runtime.cachedCount());
            try (var lease = old.join().orElseThrow()) {
                assertArrayEquals(bytes(1), copy(lease));
            }
            var joined = runtime.acquirePreview(id);
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            try (var lease = replacement.join().orElseThrow(); var other = joined.join().orElseThrow()) {
                assertArrayEquals(bytes(2), copy(lease));
                assertArrayEquals(bytes(2), copy(other));
            }
            assertEquals(1, runtime.cachedBytes());
        }
    }

    @Test
    void detachedCompletionAfterNewReadyCannotReplaceOrDoubleCountIt() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(26);
        writePreview(id, bytes(1));
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            var old = runtime.acquirePreview(id);
            runtime.tick();
            runtime.invalidatePreview(id);
            writePreview(id, bytes(2));
            var replacement = runtime.acquirePreview(id);
            runtime.tick();
            workers.runLast();
            runtime.tick();
            replacement.join().orElseThrow().close();
            writePreview(id, bytes(3, 3));
            workers.runFirst();
            runtime.tick();
            try (var lease = old.join().orElseThrow()) {
                assertArrayEquals(bytes(3, 3), copy(lease));
            }
            assertEquals(1, runtime.cachedBytes());
            var hit = runtime.acquirePreview(id);
            runtime.tick();
            try (var lease = hit.join().orElseThrow()) {
                assertArrayEquals(bytes(2), copy(lease));
            }
        }
    }

    @Test
    void repeatedInvalidationKeepsDetachedWorkersWithinTheSameFlightCapacity() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(27);
        writePreview(id, bytes(1));
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            for (var count = 0; count < ServerResourceRuntime.MAX_IN_FLIGHT; count++) {
                runtime.acquirePreview(id);
                runtime.tick();
                runtime.invalidatePreview(id);
            }
            assertEquals(ServerResourceRuntime.MAX_IN_FLIGHT, runtime.activeFlightCount());
            var rejected = runtime.acquirePreview(id);
            runtime.tick();
            var failure = assertThrows(ExecutionException.class, rejected::get);
            assertTrue(failure.getCause() instanceof ServerResourceRuntime.BusyException);
            assertEquals(ServerResourceRuntime.MAX_IN_FLIGHT, workers.size());
            workers.runFirst();
            runtime.tick();
            var accepted = runtime.acquirePreview(id);
            runtime.tick();
            assertFalse(accepted.isDone());
            assertEquals(ServerResourceRuntime.MAX_IN_FLIGHT, runtime.activeFlightCount());
        }
    }

    @Test
    void invalidatingAllPreviewsPreservesChunksAndOtherPreviewLeases() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(28);
        var chunk = source(identity(28, 38), bytes(5));
        writePreview(id, bytes(1));
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            var preview = runtime.acquirePreview(id);
            var stored = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            workers.runFirst();
            workers.runFirst();
            runtime.tick();
            var lease = preview.join().orElseThrow();
            stored.join().close();
            runtime.invalidatePreviews();
            assertEquals(1, runtime.cachedCount());
            var hit = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            hit.join().close();
            assertEquals(1, chunk.reads().get());
            assertArrayEquals(bytes(1), copy(lease));
            lease.close();
        }
    }

    @Test
    void closeTerminatesDetachedQueuedAndUndeliveredPreviewRequests() throws Exception {
        var workers = new ControlledWorkers();
        var runtime = runtime(workers, new AtomicLong(), 16);
        var id = hash(29);
        writePreview(id, bytes(1));
        var detached = runtime.acquirePreview(id);
        runtime.tick();
        runtime.invalidatePreview(id);
        var completion = runtime.acquirePreview(id);
        runtime.tick();
        workers.runLast();
        var queued = runtime.acquirePreview(hash(30));
        runtime.close();
        assertTrue(detached.isCompletedExceptionally());
        assertTrue(completion.isCompletedExceptionally());
        assertTrue(queued.isCompletedExceptionally());
        assertEquals(0, runtime.activeFlightCount());
        assertEquals(0, runtime.cachedCount());
        assertTrue(runtime.acquirePreview(id).isCompletedExceptionally());
        runtime.invalidatePreview(id);
        runtime.invalidatePreviews();
    }

    @Test
    void successfulStoreAcceptAndCommitInvalidateOnlyTheirPreview() throws Exception {
        var png = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var workers = new ControlledWorkers();
        var owner = new AtomicReference<ServerResourceRuntime>();
        var store = new PreviewStore(temp, id -> owner.get().invalidatePreview(id));
        var id = hash(31);
        var otherId = hash(32);
        writePreview(id, bytes(1));
        writePreview(otherId, bytes(2));
        try (var runtime = new ServerResourceRuntime(store, ignored -> {
        }, () -> 0L, 1_024, workers)) {
            owner.set(runtime);
            var old = runtime.acquirePreview(id);
            var other = runtime.acquirePreview(otherId);
            runtime.tick();
            workers.runFirst();
            workers.runFirst();
            runtime.tick();
            var oldLease = old.join().orElseThrow();
            other.join().orElseThrow().close();
            try (var ignored = store.accept(id, png)) {
                assertEquals(1, runtime.cachedCount());
            }
            assertArrayEquals(bytes(1), copy(oldLease));
            oldLease.close();
            var updated = runtime.acquirePreview(id);
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            try (var lease = updated.join().orElseThrow()) {
                assertArrayEquals(png, copy(lease));
            }
            assertEquals(2, runtime.cachedCount());
            try (var encoded = ArrayBuffer.move(png)) {
                assertThrows(IOException.class, () -> store.commit(id, encoded, replacement -> {
                    throw new IOException("Effect retired");
                }));
                assertEquals(2, runtime.cachedCount());
                store.commit(id, encoded, replacement -> replacement.run());
                assertEquals(1, runtime.cachedCount());
            }
            var retained = runtime.acquirePreview(otherId);
            runtime.tick();
            try (var lease = retained.join().orElseThrow()) {
                assertArrayEquals(bytes(2), copy(lease));
            }
            assertEquals(0, workers.size());
        }
    }

    @Test
    void requestBacklogHasTheSameTypedCapacityFailureForPreviewAndChunk() {
        var workers = new ControlledWorkers();
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            for (var count = 0; count < ServerResourceRuntime.MAX_REQUEST_BACKLOG; count++)
                runtime.acquirePreview(hash(33));
            var preview = runtime.acquirePreview(hash(33));
            var source = source(identity(33, 43), bytes(1));
            var chunk = runtime.acquireChunk(source.content(), source.chunk());
            assertTrue(assertThrows(java.util.concurrent.CompletionException.class, preview::join)
                .getCause() instanceof ServerResourceRuntime.BusyException);
            assertTrue(assertThrows(java.util.concurrent.CompletionException.class, chunk::join)
                .getCause() instanceof ServerResourceRuntime.BusyException);
            assertEquals(0, workers.size());
        }
    }

    @Test
    void closeTerminatesAHotHitAfterItLeavesTheRequestQueueBeforeLeaseDelivery() throws Exception {
        var workers = new ControlledWorkers();
        var pause = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var id = hash(34);
        writePreview(id, bytes(1));
        var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, () -> {
            if (pause.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("Hot-hit delivery was not released");
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
            }
            return 0L;
        }, 16, workers);
        var initial = runtime.acquirePreview(id);
        runtime.tick();
        workers.runFirst();
        runtime.tick();
        initial.join().orElseThrow().close();
        var hot = runtime.acquirePreview(id);
        pause.set(true);
        var ticker = new Thread(runtime::tick);
        ticker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(hot.isDone());
            runtime.close();
            assertTrue(hot.isCompletedExceptionally());
        } finally {
            release.countDown();
            ticker.join(5_000);
            runtime.close();
        }
        assertFalse(ticker.isAlive());
        assertEquals(0, runtime.cachedCount());
    }

    @Test
    void flightCapacityAndResultRemainPendingUntilTheChunkReadCompletes() throws Exception {
        var closing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = source(identity(35, 45), bytes(1), () -> {
            closing.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Source read was not released");
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
        });
        try (var runtime = new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        })) {
            var acquired = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            try {
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                runtime.tick();
                assertFalse(acquired.isDone());
                assertEquals(1, runtime.activeFlightCount());
            } finally {
                release.countDown();
            }
            try (var lease = finish(runtime, acquired)) {
                assertArrayEquals(bytes(1), copy(lease));
            }
            assertEquals(0, runtime.activeFlightCount());
        }
    }

    @Test
    void sourceReadFailureProducesOneFailureWithoutPublishingCandidateBytes() throws Exception {
        var workers = new ControlledWorkers();
        var expected = new IllegalStateException("Source read failed");
        var source = source(identity(36, 46), bytes(1), () -> {
            throw expected;
        });
        try (var runtime = runtime(workers, new AtomicLong(), 16)) {
            var acquired = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            var failure = assertThrows(ExecutionException.class, acquired::get);
            org.junit.jupiter.api.Assertions.assertSame(expected, failure.getCause());
            assertEquals(0, runtime.activeFlightCount());
            assertEquals(0, runtime.cachedCount());
            runtime.tick();
            assertEquals(0, runtime.cachedBytes());
        }
    }

    private ServerResourceRuntime runtime(ControlledWorkers workers, AtomicLong now, long budget) {
        return new ServerResourceRuntime(new PreviewStore(temp), ignored -> {
        }, now::get, budget, workers);
    }

    private void writePreview(Hash256 id, byte[] bytes) throws Exception {
        Files.createDirectories(temp.resolve("previews"));
        Files.write(temp.resolve("previews").resolve(id + ".image"), bytes);
    }

    private static final class ControlledWorkers extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void execute(Runnable task) {
            if (shutdown)
                throw new RejectedExecutionException();
            tasks.add(task);
        }

        int size() {
            return tasks.size();
        }

        void runFirst() {
            tasks.remove(0).run();
        }

        void runLast() {
            tasks.remove(tasks.size() - 1).run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            var abandoned = List.copyOf(tasks);
            tasks.clear();
            return abandoned;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    @Test
    void previewInvalidationAffectsOnlyTheContentRegionAndKeepsTheOldLease() throws Exception {
        var id = identity(41, 42);
        var local = new com.elfmcys.ysm.model.storage.CacheRegion(temp.resolve("local"));
        var global = new com.elfmcys.ysm.model.storage.CacheRegion(temp.resolve("global"));
        for (var region : List.of(local, global)) {
            var path = Files.createDirectories(region.root().resolve("previews")).resolve(id.containerId() + ".image");
            Files.write(path, bytes(region == local ? 11 : 22));
        }
        var localContent = regional(id, local);
        var globalContent = regional(id, global);
        try (var runtime = new ServerResourceRuntime(com.elfmcys.ysm.model.storage.PreviewStorage.routed(), ignored -> {
        })) {
            local.previews().storedObserver(key -> runtime.invalidatePreview(local.previews(), key));
            global.previews().storedObserver(key -> runtime.invalidatePreview(global.previews(), key));
            try (var old = finish(runtime, runtime.acquirePreview(localContent)).orElseThrow();
                var other = finish(runtime, runtime.acquirePreview(globalContent)).orElseThrow()) {
                assertArrayEquals(bytes(11), copy(old));
                assertArrayEquals(bytes(22), copy(other));
                assertEquals(2, runtime.cachedCount());
                var png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
                try (var image = local.previews().accept(id.containerId(), png)) {
                    assertEquals(1, runtime.cachedCount());
                    try (var replacement = finish(runtime, runtime.acquirePreview(localContent)).orElseThrow();
                        var retained = finish(runtime, runtime.acquirePreview(globalContent)).orElseThrow()) {
                        assertArrayEquals(png, copy(replacement));
                        assertArrayEquals(bytes(22), copy(retained));
                        assertArrayEquals(bytes(11), copy(old));
                    }
                }
            }
        }
    }

    private static ModelContent regional(ModelFileIdentity id, com.elfmcys.ysm.model.storage.CacheRegion region) {
        var representation = representation(id);
        var binding = com.elfmcys.ysm.model.storage.ContentStorageBinding.remote(region);
        return new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }

            @Override
            public com.elfmcys.ysm.model.storage.ContentStorageBinding storageBinding() {
                return binding;
            }
        };
    }

    private static <T> T finish(ServerResourceRuntime runtime, CompletableFuture<T> result) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone() && System.nanoTime() < deadline) {
            runtime.tick();
            Thread.onSpinWait();
        }
        return result.get(1, TimeUnit.SECONDS);
    }

    private static byte[] copy(ServerResourceRuntime.BytesLease lease) {
        var target = ByteBuffer.allocate(lease.size());
        lease.copyTo(0, target);
        return target.array();
    }

    private static Source source(ModelFileIdentity identity, byte[] bytes) {
        return source(identity, bytes, () -> {
        });
    }

    private static Source source(ModelFileIdentity identity, byte[] bytes, Runnable beforeRead) {
        var reads = new AtomicInteger();
        var chunk = new AssetContainerView.ChunkInfo("test", "raw", 0, bytes.length, bytes.length, 0, bytes.length, 0,
            hash(bytes[0]).bytes());
        ChunkDataSource chunks = new ChunkDataSource() {
            @Override
            public SharedBuffer readPayload(AssetContainerView.ChunkInfo ignored, BufferType bufferType) {
                throw new AssertionError("Stored-byte cache must not request decoded payload");
            }

            @Override
            public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo ignored, BufferType bufferType) {
                reads.incrementAndGet();
                beforeRead.run();
                return SharedBuffer.adopt(ArrayBuffer.move(Arrays.copyOf(bytes, bytes.length)));
            }
        };
        var representation = representation(identity);
        ModelContent content = new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ChunkDataSource chunks() {
                return chunks;
            }
        };
        return new Source(content, chunk, reads);
    }

    private static ModelRepresentation representation(ModelFileIdentity identity) {
        try {
            var value = (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            UnsafeUtil.getUnsafe().putObject(value, UnsafeUtil.getUnsafe().objectFieldOffset(field), identity);
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ModelFileIdentity identity(int model, int container) {
        return new ModelFileIdentity(hash(model), hash(container));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static byte[] bytes(int... values) {
        var result = new byte[values.length];
        for (var index = 0; index < values.length; index++) {
            result[index] = (byte)values[index];
        }
        return result;
    }

    private record Source(ModelContent content, AssetContainerView.ChunkInfo chunk, AtomicInteger reads) {
    }
}
