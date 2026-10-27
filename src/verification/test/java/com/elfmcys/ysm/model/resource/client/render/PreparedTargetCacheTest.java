package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class PreparedTargetCacheTest {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);

    @AfterEach
    void stopWorkers() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    @Test
    void cancellationDoesNotCancelAnotherConsumerAndLastPinRetiresOnce() throws Exception {
        var constructions = new AtomicInteger();
        var inputsClosed = new AtomicInteger();
        var value = new Value();
        try (var blocked = block(WorkPriority.BACKGROUND); var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            PreparedTargetCache.ProducerFactory<Value> producer = () -> {
                constructions.incrementAndGet();
                return new PreparedTargetCache.Producer<>() {
                    @Override
                    public Value produce(java.util.function.BooleanSupplier cancelled) {
                        assertFalse(cancelled.getAsBoolean());
                        return value;
                    }

                    @Override
                    public void close() {
                        inputsClosed.incrementAndGet();
                    }
                };
            };
            var first = owner.acquire("same", background(), producer);
            var second = owner.acquire("same", background(), producer);
            assertFalse(owner.containsReady("same"));
            assertEquals(1, constructions.get());
            first.cancel(false);
            blocked.close();
            var retained = second.get(10, TimeUnit.SECONDS);
            assertTrue(first.isCancelled());
            assertSame(value, retained.value());
            assertEquals(1, inputsClosed.get());
            var later = owner.acquire("same", background(), () -> {
                fail("ready target rebuilt");
                return null;
            }).join();
            retained.close();
            assertEquals(0, value.closes.get());
            later.close();
            later.close();
            assertFalse(owner.containsReady("same"));
            assertEquals(1, value.closes.get());
            assertEquals(0, owner.size());
        }
    }

    @Test
    void readyPinsKeepSharedPreparationAliveAfterAdmissionOwnerCloses() throws Exception {
        var value = new Value();
        var owner = new PreparedTargetCache<String, Value>(scheduler);
        var first = owner.acquire("same", background(), () -> cancelled -> value).get(10, TimeUnit.SECONDS);
        var second = owner.acquire("same", background(), () -> {
            fail("a live target must retain its shared preparation");
            return null;
        }).get(10, TimeUnit.SECONDS);
        owner.close();
        assertEquals(0, owner.size());
        assertSame(value, first.value());
        assertSame(value, second.value());
        assertEquals(0, value.closes.get());
        first.close();
        assertSame(value, second.value());
        assertEquals(0, value.closes.get());
        second.close();
        second.close();
        assertEquals(1, value.closes.get());
    }

    @Test
    void localConsumerPromotesTheSharedProducerWithoutTakingCancellationOwnership() throws Exception {
        var productions = new AtomicInteger();
        var thread = new AtomicReference<Thread>();
        var local = background();
        try (var blocked = block(WorkPriority.BACKGROUND); var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            var first = owner.acquire("same", background(), () -> cancelled -> {
                productions.incrementAndGet();
                thread.set(Thread.currentThread());
                return new Value();
            });
            var second = owner.acquire("same", local, () -> {
                fail("joined preparation rebuilt");
                return null;
            });
            assertTrue(first.cancel(false));
            local.promoteLocalPlayer();
            var pin = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, productions.get());
            assertEquals(Thread.NORM_PRIORITY, thread.get().getPriority());
            assertEquals(1, blocked.release.getCount());
            pin.close();
            assertEquals(0, owner.size());
        }
    }

    @Test
    void promotionBeforePinRegistrationIsAppliedToTheNewProducer() throws Exception {
        var dispatch = background();
        dispatch.promoteLocalPlayer();
        try (var blocked = block(WorkPriority.BACKGROUND); var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            var thread = new AtomicReference<Thread>();
            var pin = owner.acquire("same", dispatch, () -> cancelled -> {
                thread.set(Thread.currentThread());
                return new Value();
            }).get(10, TimeUnit.SECONDS);
            assertEquals(Thread.NORM_PRIORITY, thread.get().getPriority());
            assertEquals(1, blocked.release.getCount());
            pin.close();
        }
    }

    @Test
    void lastCancellationSettlesAndLateResultCannotReplaceNewEntry() throws Exception {
        var oldValue = new Value();
        var newValue = new Value();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            var old = owner.acquire("same", background(), () -> cancelled -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return oldValue;
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                old.cancel(false);
                var current = owner.acquire("same", background(), () -> cancelled -> newValue);
                assertFalse(current.isDone());
                release.countDown();
                var pin = current.get(10, TimeUnit.SECONDS);
                assertEquals(1, oldValue.closes.get());
                assertSame(newValue, pin.value());
                pin.close();
                assertEquals(1, newValue.closes.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void shutdownSettlesEveryAcceptedInterestAndDisposesLateProducer() throws Exception {
        var value = new Value();
        var owner = new PreparedTargetCache<String, Value>(scheduler);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = owner.acquire("same", background(), () -> cancelled -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return value;
            });
            var second = owner.acquire("same", background(), () -> cancelled -> value);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            owner.close();
            assertThrows(CancellationException.class, first::join);
            assertThrows(CancellationException.class, second::join);
            release.countDown();
            drainBackground();
            assertEquals(1, value.closes.get());
            assertThrows(CancellationException.class,
                () -> owner.acquire("new", background(), () -> cancelled -> new Value()).join());
            assertEquals(0, owner.size());
        } finally {
            release.countDown();
            owner.close();
        }
    }

    @Test
    void shutdownWithdrawsUnstartedProducerAndClosesItsInputOnce() throws Exception {
        var inputsClosed = new AtomicInteger();
        var productions = new AtomicInteger();
        try (var blocked = block(WorkPriority.BACKGROUND); var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            var first = owner.acquire("same", background(), () -> new PreparedTargetCache.Producer<>() {
                @Override
                public Value produce(java.util.function.BooleanSupplier cancelled) {
                    productions.incrementAndGet();
                    return new Value();
                }

                @Override
                public void close() {
                    inputsClosed.incrementAndGet();
                }
            });
            var second = owner.acquire("same", background(), () -> {
                fail("second producer");
                return null;
            });
            first.cancel(false);
            assertEquals(0, inputsClosed.get());
            owner.close();
            assertThrows(CancellationException.class, second::join);
            assertEquals(1, inputsClosed.get());
            assertEquals(0, owner.size());
            blocked.close();
            drainBackground();
            assertEquals(0, productions.get());
            assertEquals(1, inputsClosed.get());
        }
    }

    @Test
    void cacheOnlyMissRetriesWithTheStillPromotedConsumer() throws Exception {
        try (var blocked = block(WorkPriority.BACKGROUND); var localBlocked = block(WorkPriority.LOCAL_PLAYER);
            var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            var normal = owner.acquire("normal", background(), () -> cancelled -> new Value());
            var miss = owner.acquire("normal", true, background(), () -> {
                fail("cache-only joined producer");
                return null;
            });
            assertThrows(CompletionException.class, miss::join);
            var cached = owner.acquire("same", true, background(), () -> cancelled -> {
                throw new java.io.IOException("cache miss");
            });
            var consumer = background();
            var thread = new AtomicReference<Thread>();
            var retry = owner.acquire("same", consumer, () -> cancelled -> {
                thread.set(Thread.currentThread());
                return new Value();
            });
            consumer.promoteLocalPlayer();
            localBlocked.close();
            retry.get(10, TimeUnit.SECONDS).close();
            assertThrows(CompletionException.class, cached::join);
            assertEquals(Thread.NORM_PRIORITY, thread.get().getPriority());
            assertEquals(1, blocked.release.getCount());
            assertFalse(normal.isDone());
            blocked.close();
            normal.get(10, TimeUnit.SECONDS).close();
            assertEquals(0, owner.size());
        }
    }

    @Test
    void distinctKeysProgressWithoutWaitingForAChild() throws Exception {
        try (var owner = new PreparedTargetCache<String, Value>(scheduler)) {
            var first = owner.acquire("first", background(), () -> cancelled -> new Value());
            var second = owner.acquire("second", background(), () -> cancelled -> new Value());
            first.get(10, TimeUnit.SECONDS).close();
            second.get(10, TimeUnit.SECONDS).close();
            assertEquals(0, owner.size());
        }
    }

    private ClientResourceScheduler.Dispatch background() {
        return scheduler.dispatch(WorkPriority.BACKGROUND);
    }

    private void drainBackground() throws Exception {
        CompletableFuture.runAsync(() -> {}, scheduler.backgroundExecutor()).get(10, TimeUnit.SECONDS);
    }

    private BlockedWorker block(WorkPriority priority) throws InterruptedException {
        var blocked = new BlockedWorker();
        scheduler.dispatch(priority).execute(() -> {
            blocked.entered.countDown();
            try {
                assertTrue(blocked.release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        assertTrue(blocked.entered.await(10, TimeUnit.SECONDS));
        return blocked;
    }

    private static final class BlockedWorker implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static final class Value implements AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
