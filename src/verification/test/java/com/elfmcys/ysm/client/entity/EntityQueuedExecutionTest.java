package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.util.ResponsiveExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EntityQueuedExecutionTest {
    @Test
    void takenTaskDoesNotInheritTheWaitersInterruptAndPreservesBothSignalSources() {
        for (boolean waitingInterrupted : List.of(false, true)) {
            for (boolean taskInterrupts : List.of(false, true)) {
                for (boolean consumesTaskInterrupt : List.of(false, true)) {
                    for (boolean fails : List.of(false, true)) {
                        var accepted = new ArrayList<Throwable>();
                        var original = new IllegalArgumentException("task");
                        var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
                        try (var runtime = new EntityExpressionRuntime()) {
                            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                            }, accepted::add, queue);
                            lifecycle.submit(() -> {
                                try {
                                    new CountDownLatch(0).await();
                                } catch (InterruptedException failure) {
                                    throw new AssertionError("The waiter's interrupt reached the task", failure);
                                }
                                if (taskInterrupts) {
                                    Thread.currentThread().interrupt();
                                    if (consumesTaskInterrupt)
                                        assertTrue(Thread.interrupted());
                                }
                                if (fails)
                                    throw original;
                                return 17;
                            });
                            if (waitingInterrupted)
                                Thread.currentThread().interrupt();
                            lifecycle.awaitTaskForRender();
                            boolean interruptedAfter = Thread.interrupted();
                            assertEquals(fails ? List.of(original) : List.of(), accepted);
                            assertEquals(waitingInterrupted || taskInterrupts && !consumesTaskInterrupt,
                                interruptedAfter);
                            assertFalse(lifecycle.hasTask());
                        } finally {
                            Thread.interrupted();
                        }
                    }
                }
            }
        }
    }

    @Test
    void renderCompletesTheOriginalTaskBeforeAnyOccupiedWorkerIsReleased() {
        int workers = ResponsiveExecutor.maximumPoolSize();
        var entered = new CountDownLatch(workers);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(workers);
        try (var runtime = new EntityExpressionRuntime()) {
            for (int index = 0; index < workers; index++) {
                ResponsiveExecutor.execute(() -> {
                    entered.countDown();
                    try {
                        await(release);
                    } finally {
                        exited.countDown();
                    }
                });
            }
            await(entered);
            var main = Thread.currentThread();
            var events = new ArrayList<String>();
            var lifecycle =
                new EntityExecutionLifecycle(runtime, () -> fail("Unexpected cleanup"), failure -> fail(failure));
            lifecycle.submit(() -> {
                assertSame(main, Thread.currentThread());
                assertEquals(1, release.getCount());
                try {
                    events.add("body");
                    lifecycle.assertStateAccess();
                    return 42;
                } finally {
                    events.add("finally");
                }
            });
            lifecycle.awaitTaskForRender();
            assertEquals(List.of("body", "finally"), events);
            assertFalse(lifecycle.hasTask());
            assertEquals(1, release.getCount());
        } finally {
            release.countDown();
            await(exited);
        }
    }

    @Test
    void dequeuedRunningAndCompletedTasksKeepTheirWorkerAndOriginalHandoff() throws Exception {
        for (int state = 0; state < 3; state++) {
            var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
            var dequeued = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var events = new ArrayList<String>();
            var main = Thread.currentThread();
            try (var runtime = new EntityExpressionRuntime()) {
                var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                }, failure -> fail(failure), queue);
                int heldState = state;
                lifecycle.submit(() -> {
                    assertNotSame(main, Thread.currentThread());
                    entered.countDown();
                    if (heldState == 1)
                        await(release);
                    try {
                        events.add("body");
                        return 23;
                    } finally {
                        events.add("finally");
                    }
                });
                var original = assertInstanceOf(FutureTask.class, queue.tasks.peek());
                var worker = new Thread(() -> {
                    var claimed = queue.tasks.remove();
                    assertSame(original, claimed);
                    dequeued.countDown();
                    if (heldState == 0)
                        await(release);
                    claimed.run();
                });
                worker.start();
                await(dequeued);
                if (state == 1)
                    await(entered);
                if (state == 2)
                    worker.join(5000);
                assertTrue(lifecycle.hasTask());
                assertThrows(IllegalStateException.class, lifecycle::assertStateAccess);
                assertTrue(lifecycle.tryInstallation(() -> "too early").isEmpty());
                var gate = new Thread(() -> {
                    if (heldState != 2)
                        awaitWaiting(main);
                    release.countDown();
                });
                gate.start();
                lifecycle.awaitTaskForRender();
                gate.join(5000);
                worker.join(5000);
                assertFalse(gate.isAlive());
                assertFalse(worker.isAlive());
                assertEquals(23, original.get());
                assertEquals(List.of("body", "finally"), events);
                assertEquals(1, queue.claims);
                assertFalse(lifecycle.hasTask());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void queueRaceExecutesTheBodyAndFinallyExactlyOnce() throws Exception {
        for (int round = 0; round < 32; round++) {
            var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
            var start = new CountDownLatch(1);
            var bodies = new AtomicInteger();
            var cleanups = new AtomicInteger();
            try (var runtime = new EntityExpressionRuntime()) {
                var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                }, failure -> fail(failure), queue);
                lifecycle.submit(() -> {
                    try {
                        lifecycle.assertStateAccess();
                        bodies.incrementAndGet();
                        return null;
                    } finally {
                        cleanups.incrementAndGet();
                    }
                });
                var worker = new Thread(() -> {
                    await(start);
                    var claimed = queue.tasks.poll();
                    if (claimed != null)
                        claimed.run();
                });
                worker.start();
                start.countDown();
                lifecycle.awaitTaskForRender();
                worker.join(5000);
                assertFalse(worker.isAlive());
                assertEquals(1, bodies.get());
                assertEquals(1, cleanups.get());
                assertTrue(queue.tasks.isEmpty());
                assertFalse(lifecycle.hasTask());
            }
        }
    }

    @Test
    void inlineBodyAndFinallyRejectPublicReentryBeforeAnyStateChangeAndPreserveFailureIdentity() {
        for (Throwable failure : List.of(new IllegalArgumentException("ordinary"), new OutOfMemoryError("oom"),
            new InternalError("fatal"), new ThreadDeath())) {
            var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
            var accepted = new ArrayList<Throwable>();
            var checked = new AtomicInteger();
            try (var runtime = new EntityExpressionRuntime()) {
                var lifecycle =
                    new EntityExecutionLifecycle(runtime, () -> fail("Owner changed"), accepted::add, queue);
                Runnable reenter = () -> {
                    lifecycle.assertStateAccess();
                    assertThrows(IllegalStateException.class, lifecycle::awaitTask);
                    assertThrows(IllegalStateException.class, lifecycle::awaitTaskForRender);
                    assertThrows(IllegalStateException.class, lifecycle::endUse);
                    assertThrows(IllegalStateException.class, lifecycle::retire);
                    assertThrows(IllegalStateException.class, () -> lifecycle.submit(() -> null));
                    assertThrows(IllegalStateException.class, () -> lifecycle.inCall(() -> null));
                    assertThrows(IllegalStateException.class, runtime::clear);
                    assertThrows(IllegalStateException.class, runtime::invalidate);
                    assertThrows(IllegalStateException.class, runtime::prepareExecution);
                    assertTrue(lifecycle.tryInstallation(() -> "reentered").isEmpty());
                    assertFalse(lifecycle.isEndingUse());
                    assertFalse(lifecycle.isRetired());
                    checked.incrementAndGet();
                };
                lifecycle.submit(() -> {
                    try {
                        reenter.run();
                        if (failure instanceof Error error)
                            throw error;
                        throw (RuntimeException)failure;
                    } finally {
                        reenter.run();
                    }
                });
                lifecycle.awaitTaskForRender();
                assertEquals(2, checked.get());
                assertEquals(1, accepted.size());
                assertSame(failure, accepted.get(0));
                assertFalse(lifecycle.hasTask());
                assertEquals("installed", lifecycle.tryInstallation(() -> "installed").orElseThrow());
                assertEquals(0, runtime.generation());
            }
        }
    }

    @Test
    void ordinaryWaitResetRetireAndNextSubmissionNeverClaimQueuedWork() throws Exception {
        for (int action = 0; action < 4; action++) {
            var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
            try (var runtime = new EntityExpressionRuntime()) {
                var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                }, failure -> fail(failure), queue);
                lifecycle.submit(() -> 17);
                var main = Thread.currentThread();
                var worker = new Thread(() -> {
                    awaitWaiting(main);
                    queue.tasks.remove().run();
                });
                worker.start();
                switch (action) {
                    case 0 -> lifecycle.awaitTask();
                    case 1 -> lifecycle.endUse();
                    case 2 -> lifecycle.retire();
                    case 3 -> lifecycle.submit(() -> 29);
                }
                worker.join(5000);
                assertFalse(worker.isAlive());
                assertEquals(0, queue.claims);
                if (action == 3) {
                    queue.tasks.remove().run();
                    lifecycle.awaitTask();
                }
                assertFalse(lifecycle.hasTask());
            }
        }
    }

    @Test
    void rejectionLeavesNoHandoffAndRenderWaitNeverTakesGuiExecution() {
        var rejected = new RejectedExecutionException("fixture");
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
            }, failure -> fail(failure), EntityExecutionLifecycleTest.scheduler(task -> {
                throw rejected;
            }));
            assertSame(rejected, assertThrows(RejectedExecutionException.class, () -> lifecycle.submit(() -> 17)));
            assertFalse(lifecycle.hasTask());
            lifecycle.awaitTaskForRender();
        }
        try (var runtime = new EntityExpressionRuntime()) {
            var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
            var guiOwner = new EntityExecutionLifecycle(runtime, () -> {
            }, failure -> fail(failure), queue);
            var gui = guiOwner.reserveGuiTask(true, () -> 31);
            assertThrows(IllegalStateException.class, guiOwner::awaitTaskForRender);
            assertEquals(0, queue.claims);
            gui.run();
            assertThrows(IllegalStateException.class, guiOwner::awaitTaskForRender);
            assertEquals(31, gui.join());
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            assertTrue(gate.await(10, TimeUnit.SECONDS), "Gate timed out");
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
            Thread.onSpinWait();
        assertEquals(Thread.State.WAITING, thread.getState());
    }
}
