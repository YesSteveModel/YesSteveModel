package com.elfmcys.ysm.model.resource.server;

import com.elfmcys.ysm.model.domain.ModelId;
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
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.elfmcys.ysm.model.resource.server.ServerResourceRuntime.State.*;
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
    void sharedBudgetEvictsLruEntryAcrossChunksAndPreviewsWithoutRevokingLeases() throws Exception {
        var now = new AtomicLong();
        var workers = new ControlledWorkers();
        var store = previewStore();
        var chunk = source(identity(2, 12), bytes(1, 2, 3));
        var id = hash(22);
        var previewContent = previewContent(id);
        writePreview(id, bytes(4, 5, 6));
        try (var runtime = runtime(store, workers, now, 5)) {
            var chunkResult = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            var chunkLease = chunkResult;

            now.incrementAndGet();
            var previewResult = runtime.acquirePreview(previewContent);
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            var previewLease = previewResult;

            var previewHit = runtime.acquirePreview(previewContent);
            runtime.tick();
            assertEquals(0, workers.size());
            previewHit.close();

            var reacquiredChunk = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            assertEquals(2, chunk.reads().get());
            reacquiredChunk.close();

            assertArrayEquals(bytes(1, 2, 3), copy(chunkLease));
            assertArrayEquals(bytes(4, 5, 6), copy(previewLease));
            chunkLease.close();
            previewLease.close();
        }
    }

    @Test
    void idleEvictionRespectsDeadlineAndRefreshWithoutRevokingLeases() throws Exception {
        var now = new AtomicLong();
        var workers = new ControlledWorkers();
        var store = previewStore();
        var content = previewContent(hash(23));
        writePreview(content.representation().containerId(), bytes(1, 2, 3));
        try (var runtime = runtime(store, workers, now, 16)) {
            var lease = runtime.acquirePreview(content);
            runtime.tick();
            workers.runFirst();
            runtime.tick();

            now.set(ServerResourceRuntime.IDLE_NANOS - 1);
            var refreshed = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(0, workers.size());
            refreshed.close();

            now.addAndGet(ServerResourceRuntime.IDLE_NANOS - 1);
            var stillCached = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(0, workers.size());
            stillCached.close();

            now.addAndGet(ServerResourceRuntime.IDLE_NANOS);
            runtime.tick();
            var missed = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(1, workers.size());
            missed.close();
            workers.runFirst();
            runtime.tick();

            assertArrayEquals(bytes(1, 2, 3), copy(lease));
            lease.close();
        }
    }

    @Test
    void oversizedChunkIsDeliveredWithoutEnteringTheResidentCache() throws Exception {
        var workers = new ControlledWorkers();
        var source = source(identity(4, 14), bytes(1, 2, 3));
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 2)) {
            var lease = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            assertArrayEquals(bytes(1, 2, 3), copy(lease));

            var second = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            assertEquals(2, source.reads().get());
            second.close();
            lease.close();
        }
    }

    @Test
    void pendingReadsCoalesceOnlyForTheSameExactSourceButReadyBytesAreReusable() throws Exception {
        var id = identity(5, 15);
        var first = source(id, bytes(7, 8, 9));
        var workers = new ControlledWorkers();
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var sameSourceA = runtime.acquireChunk(first.content(), first.chunk());
            var sameSourceB = runtime.acquireChunk(first.content(), first.chunk());
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            assertEquals(1, first.reads().get());

            var equivalent = source(id, bytes(7, 8, 9));
            var reused = runtime.acquireChunk(equivalent.content(), equivalent.chunk());
            runtime.tick();
            assertEquals(0, workers.size());
            assertEquals(0, equivalent.reads().get());
            assertEquals(READY, reused.state());

            sameSourceA.close();
            sameSourceB.close();
            reused.close();
        }
    }

    @Test
    void concurrentEquivalentExactSourcesDoNotDoubleCountOneReadyEntry() throws Exception {
        var id = identity(6, 16);
        var first = source(id, bytes(1, 1, 1));
        var second = source(id, bytes(1, 1, 1));
        var third = source(identity(16, 26), bytes(9));
        var workers = new ControlledWorkers();
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 4)) {
            var a = runtime.acquireChunk(first.content(), first.chunk());
            var b = runtime.acquireChunk(second.content(), second.chunk());
            runtime.tick();
            workers.runFirst();
            workers.runFirst();
            runtime.tick();
            assertEquals(1, first.reads().get());
            assertEquals(1, second.reads().get());

            var c = runtime.acquireChunk(third.content(), third.chunk());
            runtime.tick();
            workers.runFirst();
            runtime.tick();

            var firstHit = runtime.acquireChunk(first.content(), first.chunk());
            var thirdHit = runtime.acquireChunk(third.content(), third.chunk());
            runtime.tick();
            assertEquals(0, workers.size());
            assertEquals(READY, firstHit.state());
            assertEquals(READY, thirdHit.state());
            a.close();
            b.close();
            c.close();
            firstHit.close();
            thirdHit.close();
        }
    }

    @Test
    void acquireAfterRuntimeCloseFailsWithoutQueuingWork() {
        var source = source(identity(7, 17), bytes(1));
        var runtime = new ServerResourceRuntime(previewStore(), ignored -> {
        });
        runtime.close();

        var failure = runtime.acquireChunk(source.content(), source.chunk());
        assertEquals(FAILED, failure.state());
        assertTrue(failure.failure() instanceof IllegalStateException);
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
        var runtime = new ServerResourceRuntime(new PreviewStore(temp, com.elfmcys.ysm.testutil.DerivedStorageTestScope
            .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temp))), ignored -> {
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
            assertEquals(FAILED, acquired.state());
            assertEquals(FAILED, runtime.acquireChunk(source.content(), source.chunk()).state());
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
        var content = previewContent(id);
        writePreview(id, bytes(1, 2, 3));
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var cancelled = runtime.acquirePreview(content);
            var first = runtime.acquirePreview(content);
            var second = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(1, workers.size());
            cancelled.close();
            workers.runFirst();
            runtime.tick();
            try (var a = first; var b = second) {
                assertNotSame(a, b);
                assertArrayEquals(bytes(1, 2, 3), copy(a));
                a.close();
                assertArrayEquals(bytes(1, 2, 3), copy(b));
            }
            assertEquals(ENDED, cancelled.state());
            var hit = runtime.acquirePreview(content);
            runtime.tick();
            try (var lease = hit) {
                assertArrayEquals(bytes(1, 2, 3), copy(lease));
            }
            assertEquals(0, workers.size());
        }
    }

    @Test
    void missingPreviewIsNeverNegativeCachedAndBoundedInvalidMediaRemainsEncodedData() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(24);
        var content = previewContent(id);
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var missing = runtime.acquirePreview(content);
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            assertEquals(MISSING, missing.state());
            missing.close();
            writePreview(id, bytes(7, 8));
            var acquired = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            try (var lease = acquired) {
                assertArrayEquals(bytes(7, 8), copy(lease));
            }
        }
    }

    @Test
    void detachedInvalidatedFlightDoesNotReplaceNewFlightOrReadyCache() throws Exception {
        var workers = new ControlledWorkers();
        var store = previewStore();
        try (var runtime = runtime(store, workers, new AtomicLong(), 16)) {
            var beforeTickId = hash(25);
            var beforeTickContent = previewContent(beforeTickId);
            writePreview(beforeTickId, bytes(1));
            var old = runtime.acquirePreview(beforeTickContent);
            runtime.tick();
            workers.runFirst();
            runtime.invalidatePreview(store, beforeTickId);
            writePreview(beforeTickId, bytes(2));
            var replacement = runtime.acquirePreview(beforeTickContent);
            runtime.tick();
            assertEquals(1, workers.size());
            try (var lease = old) {
                assertArrayEquals(bytes(1), copy(lease));
            }
            var joined = runtime.acquirePreview(beforeTickContent);
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            try (var lease = replacement; var other = joined) {
                assertArrayEquals(bytes(2), copy(lease));
                assertArrayEquals(bytes(2), copy(other));
            }
            try (var hit = runtime.acquirePreview(beforeTickContent)) {
                runtime.tick();
                assertEquals(0, workers.size());
                assertArrayEquals(bytes(2), copy(hit));
            }

            var lateId = hash(26);
            var lateContent = previewContent(lateId);
            writePreview(lateId, bytes(1));
            var lateOld = runtime.acquirePreview(lateContent);
            runtime.tick();
            runtime.invalidatePreview(store, lateId);
            writePreview(lateId, bytes(2));
            var lateReplacement = runtime.acquirePreview(lateContent);
            runtime.tick();
            workers.runLast();
            runtime.tick();
            lateReplacement.close();
            writePreview(lateId, bytes(3, 3));
            workers.runFirst();
            runtime.tick();
            try (var lease = lateOld) {
                assertArrayEquals(bytes(3, 3), copy(lease));
            }
            try (var hit = runtime.acquirePreview(lateContent)) {
                runtime.tick();
                assertEquals(0, workers.size());
                assertArrayEquals(bytes(2), copy(hit));
            }
        }
    }

    @Test
    void repeatedInvalidationKeepsDetachedWorkersWithinTheSameFlightCapacity() throws Exception {
        var workers = new ControlledWorkers();
        var store = previewStore();
        var id = hash(27);
        var content = previewContent(id);
        writePreview(id, bytes(1));
        try (var runtime = runtime(store, workers, new AtomicLong(), 16)) {
            for (var count = 0; count < ServerResourceRuntime.MAX_IN_FLIGHT; count++) {
                runtime.acquirePreview(content);
                runtime.tick();
                runtime.invalidatePreview(store, id);
            }
            var rejected = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(FAILED, rejected.state());
            assertTrue(rejected.failure() instanceof ServerResourceRuntime.BusyException);
            assertEquals(ServerResourceRuntime.MAX_IN_FLIGHT, workers.size());
            workers.runFirst();
            runtime.tick();
            var accepted = runtime.acquirePreview(content);
            var overflow = runtime.acquirePreview(previewContent(hash(127)));
            runtime.tick();
            assertEquals(PENDING, accepted.state());
            assertEquals(FAILED, overflow.state());
            assertTrue(overflow.failure() instanceof ServerResourceRuntime.BusyException);
        }
    }

    @Test
    void invalidatingAllPreviewsPreservesChunksAndOtherPreviewLeases() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(28);
        var content = previewContent(id);
        var chunk = source(identity(28, 38), bytes(5));
        writePreview(id, bytes(1));
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var preview = runtime.acquirePreview(content);
            var stored = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            workers.runFirst();
            workers.runFirst();
            runtime.tick();
            var lease = preview;
            stored.close();
            runtime.invalidatePreviews();
            var hit = runtime.acquireChunk(chunk.content(), chunk.chunk());
            runtime.tick();
            hit.close();
            assertEquals(1, chunk.reads().get());
            assertArrayEquals(bytes(1), copy(lease));
            lease.close();
            var missedPreview = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(1, workers.size());
            missedPreview.close();
        }
    }

    @Test
    void closeTerminatesDetachedQueuedAndUndeliveredPreviewRequests() throws Exception {
        var workers = new ControlledWorkers();
        var store = previewStore();
        var runtime = runtime(store, workers, new AtomicLong(), 16);
        var id = hash(29);
        var content = previewContent(id);
        writePreview(id, bytes(1));
        var detached = runtime.acquirePreview(content);
        runtime.tick();
        runtime.invalidatePreview(store, id);
        var completion = runtime.acquirePreview(content);
        runtime.tick();
        workers.runLast();
        var queued = runtime.acquirePreview(previewContent(hash(30)));
        runtime.close();
        assertEquals(FAILED, detached.state());
        assertEquals(FAILED, completion.state());
        assertEquals(FAILED, queued.state());
        assertEquals(FAILED, runtime.acquirePreview(content).state());
        runtime.invalidatePreview(store, id);
        runtime.invalidatePreviews();
    }

    @Test
    void successfulStoreAcceptAndCommitInvalidateOnlyTheirPreview() throws Exception {
        var png = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var workers = new ControlledWorkers();
        var owner = new AtomicReference<ServerResourceRuntime>();
        var storeRef = new AtomicReference<PreviewStore>();
        var store = new PreviewStore(temp,
            com.elfmcys.ysm.testutil.DerivedStorageTestScope
                .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temp)),
            id -> owner.get().invalidatePreview(storeRef.get(), id));
        storeRef.set(store);
        var id = hash(31);
        var otherId = hash(32);
        var content = previewContent(id);
        var otherContent = previewContent(otherId);
        writePreview(id, bytes(1));
        writePreview(otherId, bytes(2));
        try (var runtime = new ServerResourceRuntime(store, ignored -> {
        }, () -> 0L, 1_024, workers)) {
            owner.set(runtime);
            var old = runtime.acquirePreview(content);
            var other = runtime.acquirePreview(otherContent);
            runtime.tick();
            workers.runFirst();
            workers.runFirst();
            runtime.tick();
            var oldLease = old;
            other.close();
            try (var ignored = store.accept(id, png)) {
            }
            assertArrayEquals(bytes(1), copy(oldLease));
            oldLease.close();
            var updated = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(1, workers.size());
            workers.runFirst();
            runtime.tick();
            try (var lease = updated) {
                assertArrayEquals(png, copy(lease));
            }
            try (var encoded = ArrayBuffer.move(png)) {
                assertThrows(IOException.class, () -> store.commit(id, encoded, replacement -> {
                    throw new IOException("Effect retired");
                }));
                try (var stillCached = runtime.acquirePreview(content)) {
                    runtime.tick();
                    assertEquals(0, workers.size());
                    assertArrayEquals(png, copy(stillCached));
                }
                store.commit(id, encoded, replacement -> replacement.run());
            }
            var retained = runtime.acquirePreview(otherContent);
            runtime.tick();
            try (var lease = retained) {
                assertArrayEquals(bytes(2), copy(lease));
            }
            assertEquals(0, workers.size());
            var refreshed = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(1, workers.size());
            refreshed.close();
        }
    }

    @Test
    void requestBacklogHasTheSameTypedCapacityFailureForPreviewAndChunk() {
        var workers = new ControlledWorkers();
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var previewItem = previewContent(hash(33));
            for (var count = 0; count < ServerResourceRuntime.MAX_REQUEST_BACKLOG; count++)
                runtime.acquirePreview(previewItem);
            var preview = runtime.acquirePreview(previewItem);
            var source = source(identity(33, 43), bytes(1));
            var chunk = runtime.acquireChunk(source.content(), source.chunk());
            assertEquals(FAILED, preview.state());
            assertTrue(preview.failure() instanceof ServerResourceRuntime.BusyException);
            assertEquals(FAILED, chunk.state());
            assertTrue(chunk.failure() instanceof ServerResourceRuntime.BusyException);
            assertEquals(0, workers.size());
        }
    }

    @Test
    void readyHitIsImmediateAndIndependentUsesSurviveRuntimeClose() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(34);
        var content = previewContent(id);
        writePreview(id, bytes(1));
        var runtime = runtime(previewStore(), workers, new AtomicLong(), 16);
        var initial = runtime.acquirePreview(content);
        runtime.tick();
        workers.runFirst();
        runtime.tick();
        initial.close();
        try (var hot = runtime.acquirePreview(content); var retained = hot.retain()) {
            assertEquals(READY, hot.state());
            runtime.close();
            hot.close();
            assertEquals(ENDED, hot.state());
            assertArrayEquals(bytes(1), copy(retained));
        }
    }

    @Test
    void lastPendingUseRetiresJoinAndPublicationWithoutConsumerPoll() throws Exception {
        var workers = new ControlledWorkers();
        var id = hash(34);
        var content = previewContent(id);
        writePreview(id, bytes(1));
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var abandoned = runtime.acquirePreview(content);
            runtime.tick();
            abandoned.close();
            abandoned.close();
            var replacement = runtime.acquirePreview(content);
            runtime.tick();
            assertEquals(2, workers.size());
            workers.runFirst();
            runtime.tick();
            assertEquals(PENDING, replacement.state());
            workers.runFirst();
            runtime.tick();
            try (replacement) {
                assertArrayEquals(bytes(1), copy(replacement));
            }
        }
    }

    @Test
    void independentReadRetentionEndsAtRejectionQueuedExitOrActualReadTerminal() throws Exception {
        var workers = new ControlledWorkers();
        var released = new AtomicInteger();
        var source = source(identity(37, 47), bytes(1));
        var runtime = runtime(previewStore(), workers, new AtomicLong(), 16);
        var queued = runtime.acquireChunk(source.content(), source.chunk(), released::incrementAndGet);
        queued.close();
        queued.close();
        assertEquals(1, released.get());
        runtime.tick();
        assertEquals(0, workers.size());
        var pending = runtime.acquireChunk(source.content(), source.chunk(), released::incrementAndGet);
        runtime.tick();
        pending.close();
        assertEquals(1, released.get());
        workers.runFirst();
        assertEquals(2, released.get());
        runtime.tick();
        var uncached = runtime.acquireChunk(source.content(), source.chunk(), released::incrementAndGet);
        runtime.tick();
        assertEquals(1, workers.size());
        uncached.close();
        runtime.close();
        assertEquals(3, released.get());
        try (var rejected = runtime.acquireChunk(source.content(), source.chunk(), released::incrementAndGet)) {
            assertEquals(FAILED, rejected.state());
            assertEquals(4, released.get());
        }
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
        try (var runtime = new ServerResourceRuntime(previewStore(), ignored -> {
        })) {
            var acquired = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            try {
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                runtime.tick();
                assertEquals(PENDING, acquired.state());
            } finally {
                release.countDown();
            }
            try (var lease = finish(runtime, acquired)) {
                assertArrayEquals(bytes(1), copy(lease));
            }
        }
    }

    @Test
    void sourceReadFailureProducesOneFailureWithoutPublishingCandidateBytes() throws Exception {
        var workers = new ControlledWorkers();
        var expected = new IllegalStateException("Source read failed");
        var source = source(identity(36, 46), bytes(1), () -> {
            throw expected;
        });
        try (var runtime = runtime(previewStore(), workers, new AtomicLong(), 16)) {
            var acquired = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            workers.runFirst();
            runtime.tick();
            assertEquals(FAILED, acquired.state());
            org.junit.jupiter.api.Assertions.assertSame(expected, acquired.failure());
            runtime.tick();
            var retry = runtime.acquireChunk(source.content(), source.chunk());
            runtime.tick();
            assertEquals(1, workers.size());
            retry.close();
        }
    }

    private PreviewStore previewStore() {
        return new PreviewStore(temp, com.elfmcys.ysm.testutil.DerivedStorageTestScope
            .track(new com.elfmcys.ysm.model.storage.AtomicSharedCache(temp)));
    }

    private ServerResourceRuntime runtime(PreviewStore store, ControlledWorkers workers, AtomicLong now, long budget) {
        return new ServerResourceRuntime(store, ignored -> {
        }, now::get, budget, workers);
    }

    private void writePreview(Hash256 id, byte[] bytes) throws Exception {
        Files.createDirectories(temp.resolve("previews"));
        var file = Files.write(temp.resolve("previews").resolve(id + ".image"), bytes);
        com.elfmcys.ysm.model.storage.TestPreviews.index(temp, id, file);
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
        var local = com.elfmcys.ysm.testutil.DerivedStorageTestScope
            .track(new com.elfmcys.ysm.model.storage.CacheRegion(temp.resolve("local")));
        var global = com.elfmcys.ysm.testutil.DerivedStorageTestScope
            .track(new com.elfmcys.ysm.model.storage.CacheRegion(temp.resolve("global")));
        for (var region : List.of(local, global)) {
            var path = com.elfmcys.ysm.model.storage.TestCacheFiles.path(region.root(),
                com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.PREVIEW, id.containerId().toString(),
                true);
            Files.write(path, bytes(region == local ? 11 : 22));
        }
        var localContent = regional(id, local);
        var globalContent = regional(id, global);
        try (var runtime = new ServerResourceRuntime(com.elfmcys.ysm.model.storage.PreviewStorage.routed(), ignored -> {
        })) {
            var localPreviews = localContent.storageBinding().previews();
            var globalPreviews = globalContent.storageBinding().previews();
            localPreviews.storedObserver(key -> runtime.invalidatePreview(localPreviews, key));
            globalPreviews.storedObserver(key -> runtime.invalidatePreview(globalPreviews, key));
            try (var old = finish(runtime, runtime.acquirePreview(localContent));
                var other = finish(runtime, runtime.acquirePreview(globalContent))) {
                assertArrayEquals(bytes(11), copy(old));
                assertArrayEquals(bytes(22), copy(other));
                var png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
                try (var image = localPreviews.accept(id.containerId(), png)) {
                    try (var replacement = finish(runtime, runtime.acquirePreview(localContent));
                        var retained = finish(runtime, runtime.acquirePreview(globalContent))) {
                        assertArrayEquals(png, copy(replacement));
                        assertArrayEquals(bytes(22), copy(retained));
                        assertArrayEquals(bytes(11), copy(old));
                    }
                }
            }
        }
    }

    private static ModelContent previewContent(Hash256 containerId) {
        var representation = representation(new ModelFileIdentity(ModelId.fromHash(containerId), containerId));
        return new ModelContent() {
            @Override
            public ModelRepresentation representation() {
                return representation;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
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

    private static ServerResourceRuntime.BytesLease finish(ServerResourceRuntime runtime,
        ServerResourceRuntime.BytesLease result) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (result.state() == PENDING && System.nanoTime() < deadline) {
            runtime.tick();
            Thread.onSpinWait();
        }
        assertEquals(READY, result.state());
        return result;
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
        return new ModelFileIdentity(ModelId.fromHash(hash(model)), hash(container));
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
