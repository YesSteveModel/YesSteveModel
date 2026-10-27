package com.elfmcys.ysm.model.resource.client.work;

import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ClientResourceSchedulerTest {
    @Test
    void localPlayerStartsWhileEveryBackgroundWorkerIsBusy() throws Exception {
        var policies = new ConcurrentHashMap<Thread, ThreadPolicy>();
        var scheduler = new ClientResourceScheduler(3, (policy, body) -> () -> {
            policies.put(Thread.currentThread(), policy);
            body.run();
        });
        var first = new Gate();
        var second = new Gate();
        try {
            scheduler.backgroundExecutor().execute(first);
            scheduler.backgroundExecutor().execute(second);
            var firstThread = first.thread.get(5, TimeUnit.SECONDS);
            var secondThread = second.thread.get(5, TimeUnit.SECONDS);
            var queuedBackground = new CompletableFuture<Thread>();
            scheduler.backgroundExecutor().execute(() -> queuedBackground.complete(Thread.currentThread()));

            var localThread = executedBy(scheduler.dispatch(WorkPriority.LOCAL_PLAYER)).get(5, TimeUnit.SECONDS);
            assertFalse(queuedBackground.isDone());
            assertNotSame(firstThread, localThread);
            assertNotSame(secondThread, localThread);
            assertNotSame(Thread.currentThread(), localThread);
            assertEquals(ThreadPolicy.NORMAL, policies.get(localThread));
            assertEquals(ThreadPolicy.BELOW_NORMAL, policies.get(firstThread));
            assertEquals(ThreadPolicy.BELOW_NORMAL, policies.get(secondThread));
            assertTrue(localThread.isDaemon());
            assertTrue(firstThread.isDaemon());
            assertTrue(secondThread.isDaemon());
        } finally {
            first.release();
            second.release();
            close(scheduler);
        }
    }

    @Test
    void promotionMovesEachNodeOnceToTheLocalQueueTailInSubmissionOrder() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var background = new Gate();
        var local = new Gate();
        try {
            scheduler.backgroundExecutor().execute(background);
            scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(local);
            background.thread.get(5, TimeUnit.SECONDS);
            local.thread.get(5, TimeUnit.SECONDS);
            var order = new ArrayList<String>();
            var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
            var first = dispatch.enqueue(() -> order.add("first"));
            var second = dispatch.enqueue(() -> order.add("second"));
            var localDispatch = scheduler.dispatch(WorkPriority.LOCAL_PLAYER);
            localDispatch.execute(() -> order.add("before"));

            dispatch.promoteLocalPlayer();
            dispatch.promoteLocalPlayer();
            dispatch.execute(() -> order.add("after"));
            var complete = new CompletableFuture<Void>();
            localDispatch.execute(() -> {
                order.add("last");
                complete.complete(null);
            });
            local.release();
            complete.get(5, TimeUnit.SECONDS);

            assertEquals(WorkPriority.LOCAL_PLAYER, dispatch.priority());
            assertEquals(List.of("before", "first", "second", "after", "last"), order);
            assertFalse(first.withdraw());
            assertFalse(second.withdraw());
        } finally {
            background.release();
            local.release();
            close(scheduler);
        }
    }

    @Test
    void claimedWorkStaysOnItsWorkerAndLaterSubmissionUsesThePromotedCategory() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var claimed = new Gate();
        try {
            var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
            var executions = new AtomicInteger();
            var task = dispatch.enqueue(() -> {
                executions.incrementAndGet();
                claimed.run();
            });
            var backgroundThread = claimed.thread.get(5, TimeUnit.SECONDS);
            dispatch.promoteLocalPlayer();
            dispatch.promoteLocalPlayer();

            var continuationThread = executedBy(dispatch).get(5, TimeUnit.SECONDS);
            assertFalse(task.withdraw());
            assertNotSame(backgroundThread, continuationThread);
            assertEquals(1, executions.get());
        } finally {
            claimed.release();
            close(scheduler);
        }
    }

    @Test
    void withdrawalRemovesOnlyUnclaimedNodesAndNeverRunsTheirActions() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var background = new Gate();
        var local = new Gate();
        var executions = new AtomicInteger();
        try {
            scheduler.backgroundExecutor().execute(background);
            scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(local);
            background.thread.get(5, TimeUnit.SECONDS);
            local.thread.get(5, TimeUnit.SECONDS);
            var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
            var backgroundTask = dispatch.enqueue(executions::incrementAndGet);
            assertTrue(backgroundTask.withdraw());
            assertFalse(backgroundTask.withdraw());
            var promotedTask = dispatch.enqueue(executions::incrementAndGet);
            dispatch.promoteLocalPlayer();
            assertTrue(promotedTask.withdraw());
            assertFalse(promotedTask.withdraw());
            var localTask = scheduler.dispatch(WorkPriority.LOCAL_PLAYER).enqueue(executions::incrementAndGet);
            assertTrue(localTask.withdraw());
            scheduler.shutdown();
        } finally {
            background.release();
            local.release();
            close(scheduler);
        }
        assertEquals(0, executions.get());
    }

    @Test
    void linkingBeforeOrAfterPromotionPromotesTheSameQueuedProducer() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var background = new Gate();
        try {
            scheduler.backgroundExecutor().execute(background);
            background.thread.get(5, TimeUnit.SECONDS);
            var localThread = executedBy(scheduler.dispatch(WorkPriority.LOCAL_PLAYER)).get(5, TimeUnit.SECONDS);
            var before = scheduler.dispatch(WorkPriority.BACKGROUND);
            var beforeProducer = scheduler.dispatch(WorkPriority.BACKGROUND);
            var beforeWork = executedBy(beforeProducer);
            before.promoteLocalPlayer();
            try (var ignored = before.forwardPromotionTo(beforeProducer)) {
                assertSame(localThread, beforeWork.get(5, TimeUnit.SECONDS));
            }

            var after = scheduler.dispatch(WorkPriority.BACKGROUND);
            var afterProducer = scheduler.dispatch(WorkPriority.BACKGROUND);
            var afterWork = executedBy(afterProducer);
            try (var ignored = after.forwardPromotionTo(afterProducer)) {
                assertEquals(WorkPriority.BACKGROUND, afterProducer.priority());
                after.promoteLocalPlayer();
                assertSame(localThread, afterWork.get(5, TimeUnit.SECONDS));
            }
            assertEquals(WorkPriority.LOCAL_PLAYER, beforeProducer.priority());
            assertEquals(WorkPriority.LOCAL_PLAYER, afterProducer.priority());
        } finally {
            background.release();
            close(scheduler);
        }
    }

    @Test
    void unlinkingOneConsumerDoesNotCancelOrDisconnectAnotherConsumer() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var background = new Gate();
        try {
            scheduler.backgroundExecutor().execute(background);
            background.thread.get(5, TimeUnit.SECONDS);
            var localThread = executedBy(scheduler.dispatch(WorkPriority.LOCAL_PLAYER)).get(5, TimeUnit.SECONDS);
            var first = scheduler.dispatch(WorkPriority.BACKGROUND);
            var second = scheduler.dispatch(WorkPriority.BACKGROUND);
            var producer = scheduler.dispatch(WorkPriority.BACKGROUND);
            var producerWork = executedBy(producer);
            var firstLink = first.forwardPromotionTo(producer);
            try (var ignored = second.forwardPromotionTo(producer)) {
                firstLink.close();
                firstLink.close();
                first.promoteLocalPlayer();
                assertEquals(WorkPriority.BACKGROUND, producer.priority());
                assertFalse(producerWork.isDone());
                second.promoteLocalPlayer();
                assertSame(localThread, producerWork.get(5, TimeUnit.SECONDS));
            }
            assertEquals(WorkPriority.LOCAL_PLAYER, producer.priority());
        } finally {
            background.release();
            close(scheduler);
        }
    }

    @Test
    void promotionLinksRejectSelfAndOtherSchedulers() throws Exception {
        var first = new ClientResourceScheduler(2);
        var second = new ClientResourceScheduler(2);
        try {
            var dispatch = first.dispatch(WorkPriority.BACKGROUND);
            assertThrows(IllegalArgumentException.class, () -> dispatch.forwardPromotionTo(dispatch));
            assertThrows(IllegalArgumentException.class,
                () -> dispatch.forwardPromotionTo(second.dispatch(WorkPriority.BACKGROUND)));
            assertEquals(WorkPriority.BACKGROUND, dispatch.priority());
        } finally {
            close(first);
            close(second);
        }
    }

    @Test
    void shutdownDrainsAcceptedExecutorWorkAndPreventsLateMigrationAndLinks() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var background = new Gate();
        var local = new Gate();
        try {
            scheduler.backgroundExecutor().execute(background);
            scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(local);
            var backgroundThread = background.thread.get(5, TimeUnit.SECONDS);
            local.thread.get(5, TimeUnit.SECONDS);
            var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
            var producer = scheduler.dispatch(WorkPriority.BACKGROUND);
            var accepted = executedBy(dispatch);
            var producerWork = executedBy(producer);
            var executorWork = CompletableFuture.supplyAsync(Thread::currentThread, scheduler.backgroundExecutor());
            var acceptedLocal = executedBy(scheduler.dispatch(WorkPriority.LOCAL_PLAYER));
            var promotedSource = scheduler.dispatch(WorkPriority.LOCAL_PLAYER);
            var existingLink = dispatch.forwardPromotionTo(producer);

            scheduler.shutdown();
            scheduler.shutdown();
            local.release();
            acceptedLocal.get(5, TimeUnit.SECONDS);
            dispatch.promoteLocalPlayer();
            try (var ignored = promotedSource.forwardPromotionTo(producer)) {
                assertEquals(WorkPriority.BACKGROUND, producer.priority());
                assertFalse(producerWork.isDone());
            }
            assertThrows(RejectedExecutionException.class, () -> dispatch.enqueue(() -> fail("rejected action ran")));
            assertThrows(RejectedExecutionException.class,
                () -> scheduler.backgroundExecutor().execute(() -> fail("rejected background action ran")));
            existingLink.close();
            background.release();

            assertSame(backgroundThread, accepted.get(5, TimeUnit.SECONDS));
            assertSame(backgroundThread, producerWork.get(5, TimeUnit.SECONDS));
            assertSame(backgroundThread, executorWork.get(5, TimeUnit.SECONDS));
        } finally {
            background.release();
            local.release();
            close(scheduler);
        }
    }

    @Test
    void shutdownAndPromotionRaceNeverStrandsOrDuplicatesAnAcceptedNode() throws Exception {
        for (var iteration = 0; iteration < 32; iteration++) {
            var scheduler = new ClientResourceScheduler(2);
            var background = new Gate();
            var local = new Gate();
            try {
                scheduler.backgroundExecutor().execute(background);
                scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(local);
                background.thread.get(5, TimeUnit.SECONDS);
                local.thread.get(5, TimeUnit.SECONDS);
                var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
                var executions = new AtomicInteger();
                var complete = new CompletableFuture<Void>();
                dispatch.execute(() -> {
                    executions.incrementAndGet();
                    complete.complete(null);
                });
                var race = new CountDownLatch(1);
                var promotion = CompletableFuture.runAsync(() -> {
                    await(race);
                    dispatch.promoteLocalPlayer();
                });
                var shutdown = CompletableFuture.runAsync(() -> {
                    await(race);
                    scheduler.shutdown();
                });
                race.countDown();
                promotion.get(5, TimeUnit.SECONDS);
                shutdown.get(5, TimeUnit.SECONDS);
                background.release();
                local.release();
                complete.get(5, TimeUnit.SECONDS);
                scheduler.awaitTermination();
                assertEquals(1, executions.get());
            } finally {
                background.release();
                local.release();
                close(scheduler);
            }
        }
    }

    @Test
    void claimAndPromotionRaceExecutesTheNodeExactlyOnce() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        try {
            for (var iteration = 0; iteration < 64; iteration++) {
                var background = new Gate();
                try {
                    scheduler.backgroundExecutor().execute(background);
                    background.thread.get(5, TimeUnit.SECONDS);
                    var dispatch = scheduler.dispatch(WorkPriority.BACKGROUND);
                    var executions = new AtomicInteger();
                    var complete = new CompletableFuture<Void>();
                    dispatch.execute(() -> {
                        executions.incrementAndGet();
                        complete.complete(null);
                    });
                    var race = new CountDownLatch(1);
                    var promotion = CompletableFuture.runAsync(() -> {
                        await(race);
                        dispatch.promoteLocalPlayer();
                    });
                    var claim = CompletableFuture.runAsync(() -> {
                        await(race);
                        background.release();
                    });
                    race.countDown();
                    promotion.get(5, TimeUnit.SECONDS);
                    claim.get(5, TimeUnit.SECONDS);
                    complete.get(5, TimeUnit.SECONDS);
                    executedBy(scheduler.dispatch(WorkPriority.LOCAL_PLAYER)).get(5, TimeUnit.SECONDS);
                    executedBy(scheduler.dispatch(WorkPriority.BACKGROUND)).get(5, TimeUnit.SECONDS);
                    assertEquals(1, executions.get());
                } finally {
                    background.release();
                }
            }
        } finally {
            close(scheduler);
        }
    }

    @Test
    void actionFailuresDoNotRetireEitherWorker() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        try {
            var background = scheduler.dispatch(WorkPriority.BACKGROUND);
            var local = scheduler.dispatch(WorkPriority.LOCAL_PLAYER);
            var backgroundThread = executedBy(background).get(5, TimeUnit.SECONDS);
            var localThread = executedBy(local).get(5, TimeUnit.SECONDS);
            background.execute(() -> {
                throw new AssertionError("background action failure");
            });
            local.execute(() -> {
                throw new IllegalStateException("local action failure");
            });
            assertSame(backgroundThread, executedBy(background).get(5, TimeUnit.SECONDS));
            assertSame(localThread, executedBy(local).get(5, TimeUnit.SECONDS));
        } finally {
            close(scheduler);
        }
    }

    @Test
    void terminationWaitIsInterruptibleAndDoesNotDiscardTheRunningAction() throws Exception {
        var scheduler = new ClientResourceScheduler(2);
        var background = new Gate();
        Thread waiter = null;
        try {
            scheduler.backgroundExecutor().execute(background);
            background.thread.get(5, TimeUnit.SECONDS);
            scheduler.shutdown();
            var entered = new CountDownLatch(1);
            var interrupted = new CompletableFuture<Boolean>();
            waiter = new Thread(() -> {
                entered.countDown();
                try {
                    scheduler.awaitTermination();
                    interrupted.complete(false);
                } catch (InterruptedException expected) {
                    interrupted.complete(true);
                }
            });
            waiter.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
            assertEquals(1, background.release.getCount());
        } finally {
            background.release();
            close(scheduler);
            if (waiter != null) {
                waiter.join();
            }
        }
    }

    @Test
    void invalidCapacityAndMissingEntryAreRejectedBeforeStartingWorkers() {
        assertThrows(IllegalArgumentException.class, () -> new ClientResourceScheduler(1));
        assertThrows(NullPointerException.class, () -> new ClientResourceScheduler(2, null));
    }

    private static CompletableFuture<Thread> executedBy(ClientResourceScheduler.Dispatch dispatch) {
        var result = new CompletableFuture<Thread>();
        dispatch.execute(() -> result.complete(Thread.currentThread()));
        return result;
    }

    private static void close(ClientResourceScheduler scheduler) throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test action release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted test action", interrupted);
        }
    }

    private static final class Gate implements Runnable {
        private final CompletableFuture<Thread> thread = new CompletableFuture<>();
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void run() {
            thread.complete(Thread.currentThread());
            await(release);
        }

        private void release() {
            release.countDown();
        }
    }
}
