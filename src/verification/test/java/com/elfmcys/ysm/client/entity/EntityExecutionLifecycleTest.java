package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityExecutionLifecycleTest {
    @Test
    void guiReservationSealsReadsUntilTheExplicitJoinAndCannotAuthorizeAnotherOwner() throws Exception {
        try (var runtime = new EntityExpressionRuntime(); var other = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure));
            var another = new EntityExecutionLifecycle(other, () -> {}, failure -> fail(failure));
            var input = new EntityExpressionRuntime.InvocationInputs(
                new InvocationInputs<net.minecraft.world.entity.Entity>() {}, (commands, provenance) -> () -> {},
                () -> false);
            var task = lifecycle.reserveGuiTask(true, () -> {
                lifecycle.assertStateAccess();
                lifecycle.assertSubjectAccess();
                assertEquals(0, runtime.generation());
                assertThrows(IllegalStateException.class, lifecycle::endUse);
                assertThrows(IllegalStateException.class, lifecycle::retire);
                assertThrows(IllegalStateException.class, runtime::invalidate);
                assertThrows(IllegalStateException.class, runtime::clear);
                assertThrows(IllegalStateException.class, runtime::prepareExecution);
                assertThrows(IllegalStateException.class, () -> another.inCall(() -> null));
                assertThrows(IllegalStateException.class, another::assertSubjectAccess);
                assertThrows(IllegalStateException.class, other::generation);
                assertThrows(IllegalStateException.class, () -> other.observe(input, ignored -> null));
                return 17;
            });
            assertThrows(IllegalStateException.class, lifecycle::assertStateAccess);
            assertThrows(IllegalStateException.class, lifecycle::assertSubjectAccess);
            assertThrows(IllegalStateException.class, runtime::generation);
            assertThrows(IllegalStateException.class, lifecycle::endUse);
            assertThrows(IllegalStateException.class, () -> lifecycle.inDraw(() -> null));
            assertThrows(IllegalStateException.class, () -> runtime.observe(input, ignored -> null));
            var worker = new Thread(task);
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(lifecycle.hasTask());
            assertThrows(IllegalStateException.class, lifecycle::assertSubjectAccess);
            assertThrows(IllegalStateException.class, runtime::generation);
            assertThrows(IllegalStateException.class, task::run);
            assertEquals(17, task.join());
            assertFalse(lifecycle.hasTask());
            lifecycle.assertSubjectAccess();
            assertEquals(0, runtime.generation());
            assertThrows(IllegalStateException.class, task::join);
        }
    }

    @Test
    void guiInternalEntryPreservesCandidateCallAndDrawReentryRulesEvenOnTheMainThread() {
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure));
            @SuppressWarnings("unchecked")
            var tasks = new EntityExecutionLifecycle.GuiTask<?>[1];
            var task = lifecycle.reserveGuiTask(true, () -> lifecycle.inGuiExecution(tasks[0], () -> {
                runtime.prepareExecution();
                lifecycle.inCandidate(() -> {
                    assertThrows(IllegalStateException.class, () -> lifecycle.inCall(() -> null));
                    assertThrows(IllegalStateException.class, lifecycle::endUse);
                    assertTrue(lifecycle.tryInstallation(() -> "reentered").isEmpty());
                    return null;
                });
                lifecycle.inCall(() -> {
                    assertThrows(IllegalStateException.class, () -> lifecycle.inCandidate(() -> null));
                    assertThrows(IllegalStateException.class, () -> lifecycle.inDraw(() -> null));
                    assertThrows(IllegalStateException.class, runtime::clear);
                    return null;
                });
                assertThrows(IllegalStateException.class, lifecycle::awaitTask);
                assertThrows(IllegalStateException.class, () -> lifecycle.reserveGuiTask(true, () -> null));
                return 23;
            }));
            tasks[0] = task;
            task.run();
            assertThrows(IllegalStateException.class, runtime::generation);
            assertEquals(23, task.join());
        }
    }

    @Test
    void rejectedAndNonGuiReservationsDoNotGrantExecution() throws Exception {
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure));
            assertThrows(IllegalStateException.class, () -> lifecycle.reserveGuiTask(false, () -> null));
            assertFalse(lifecycle.hasTask());
            var task = lifecycle.reserveGuiTask(true, () -> fail("Rejected task executed"));
            task.reject();
            assertFalse(lifecycle.hasTask());
            assertEquals(0, runtime.generation());
            assertThrows(IllegalStateException.class, task::run);
            assertThrows(IllegalStateException.class, task::join);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try { runtime.prepareExecution(); }
                catch (Throwable expected) { failure.set(expected); }
            });
            worker.start();
            worker.join(5000);
            assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }

    @Test
    void guiFailedSharedActivationDefersCleanupAndPreservesTheTaskFailure() {
        var events = new ArrayList<String>();
        var primary = new IllegalArgumentException("task");
        var cleanup = new IllegalStateException("cleanup");
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                events.add("close");
                throw cleanup;
            }, failure -> {}, Runnable::run);
            var tasks = new EntityExecutionLifecycle.GuiTask<?>[1];
            var task = lifecycle.reserveGuiTask(true, () -> lifecycle.inGuiExecution(tasks[0], () -> {
                try {
                    lifecycle.endInstallationUse();
                    assertTrue(lifecycle.isEndingUse());
                    assertTrue(events.isEmpty());
                    throw primary;
                } finally { events.add("execution-exit"); }
            }));
            tasks[0] = task;
            task.run();
            assertEquals(List.of("execution-exit"), events);
            assertSame(primary, assertThrows(IllegalArgumentException.class, task::join));
            assertEquals(List.of("execution-exit", "close"), events);
            assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
            assertFalse(lifecycle.hasTask());
            assertEquals(0, runtime.generation());
        }
    }

    @Test
    void guiJoinWaitsForTheEntireFinallyAndRestoresInterrupt() throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure));
            var task = lifecycle.reserveGuiTask(true, () -> {
                try {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return 31;
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
                finally { events.add("execution-exit"); }
            });
            var worker = new Thread(task);
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var main = Thread.currentThread();
            var gate = new Thread(() -> {
                while (main.getState() != Thread.State.WAITING) Thread.onSpinWait();
                release.countDown();
            });
            gate.start();
            main.interrupt();
            assertEquals(31, task.join());
            assertTrue(Thread.interrupted());
            assertEquals(List.of("execution-exit"), events);
            gate.join(5000);
            worker.join(5000);
        } finally { release.countDown(); Thread.interrupted(); }
    }

    @Test
    void guiJoinPromotesFatalFailureHandlingAndCleanupWithoutLosingTheOriginalTask() {
        for (boolean failCleanup : List.of(false, true)) {
            for (var fatal : List.of(new InternalError("fatal"), new OutOfMemoryError("oom"), new ThreadDeath())) {
                var primary = new IllegalArgumentException("task");
                var closed = new java.util.concurrent.atomic.AtomicInteger();
                try (var runtime = new EntityExpressionRuntime()) {
                    var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                        closed.incrementAndGet();
                        if (failCleanup) throw fatal;
                    }, failure -> {
                        assertSame(primary, failure);
                        if (!failCleanup) throw fatal;
                    });
                    var tasks = new EntityExecutionLifecycle.GuiTask<?>[1];
                    var task = lifecycle.reserveGuiTask(true, () -> lifecycle.inGuiExecution(tasks[0], () -> {
                        if (failCleanup) lifecycle.endInstallationUse();
                        throw primary;
                    }));
                    tasks[0] = task;
                    task.run();
                    assertSame(fatal, assertThrows(Error.class, task::join));
                    assertArrayEquals(new Throwable[]{primary}, fatal.getSuppressed());
                    assertEquals(failCleanup ? 1 : 0, closed.get());
                    assertFalse(lifecycle.hasTask());
                    assertFalse(lifecycle.isEndingUse());
                    assertEquals(0, runtime.generation());
                }
            }
        }
    }

    @Test
    void guiDeferredEndingUsePromotesFatalCleanupAfterProjectionFailureButKeepsTheExistingOomBoundary()
        throws Exception {
        for (var cleanup : List.of(new InternalError("fatal"), new ThreadDeath(), new OutOfMemoryError("oom"))) {
            try (var module = TestExpressionModules.create(Set.of("value"));
                var runtime = new EntityExpressionRuntime()) {
                TestExpressionModules.replace(runtime, module, Map.of(), RoamingRole.PRODUCER);
                runtime.initializeRoaming(new RoamingPayload(new java.util.TreeMap<>()));
                var installation = runtime.snapshot().orElseThrow().installation();
                var projection = new IllegalStateException("projection");
                var closed = new java.util.concurrent.atomic.AtomicInteger();
                var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                    closed.incrementAndGet();
                    throw cleanup;
                }, failure -> fail(failure));
                var inputs = new EntityExpressionRuntime.InvocationInputs(
                    new InvocationInputs<net.minecraft.world.entity.Entity>() {},
                    (commands, provenance) -> () -> {}, () -> false);
                var tasks = new EntityExecutionLifecycle.GuiTask<?>[1];
                var task = lifecycle.reserveGuiTask(true, () -> lifecycle.inGuiExecution(tasks[0], () -> {
                    runtime.normalUpdate(inputs, evaluation -> {
                        runtime.roamingState().orElseThrow().write("value", 7);
                        return null;
                    }, delta -> { throw projection; });
                    lifecycle.endInstallationUse();
                    return null;
                }));
                tasks[0] = task;
                var worker = new Thread(task);
                worker.start();
                worker.join(5000);
                assertFalse(worker.isAlive());
                assertTrue(lifecycle.hasTask());
                assertTrue(lifecycle.isEndingUse());
                assertEquals(0, closed.get());
                assertThrows(IllegalStateException.class, runtime::generation);
                var failure = assertThrows(Throwable.class, task::join);
                if (cleanup instanceof OutOfMemoryError) {
                    assertSame(projection, failure);
                    assertArrayEquals(new Throwable[]{cleanup}, projection.getSuppressed());
                } else {
                    assertSame(cleanup, failure);
                    assertArrayEquals(new Throwable[]{projection}, cleanup.getSuppressed());
                }
                assertEquals(1, closed.get());
                assertFalse(lifecycle.hasTask());
                assertFalse(lifecycle.isEndingUse());
                assertFalse(installation.commit(() -> fail("Failed cleanup left the installation open")));
            }
        }
    }

    @Test
    void completedButUnjoinedTaskRemainsBusy() throws Exception {
        try (var runtime = new EntityExpressionRuntime()) {
            var queue = new ArrayList<Runnable>();
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure), queue::add);
            lifecycle.submit(() -> {
                lifecycle.assertSubjectAccess();
                lifecycle.assertStateAccess();
                return 42;
            });
            lifecycle.assertSubjectAccess();
            assertThrows(IllegalStateException.class, lifecycle::assertStateAccess);
            var worker = new Thread(queue.get(0));
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            lifecycle.assertSubjectAccess();
            assertThrows(IllegalStateException.class, lifecycle::assertStateAccess);
            assertTrue(lifecycle.tryInstallation(() -> { throw new AssertionError("Unjoined task was read"); }).isEmpty());
            assertThrows(IllegalStateException.class, runtime::clear);
            assertThrows(IllegalStateException.class, runtime::prepareExecution);
            lifecycle.awaitTask();
            assertEquals("installed", lifecycle.tryInstallation(() -> "installed").orElseThrow());
        }
    }

    @Test
    void correspondingGetPublishesOrdinaryFieldsIncludingFinallyOnSuccessAndFailure() {
        for (boolean fail : List.of(false, true)) {
            class Values { int result; final Nested nested = new Nested(); }
            class Holder { final Values values = new Values(); }
            var holder = new Holder();
            var failures = new ArrayList<Throwable>();
            try (var runtime = new EntityExpressionRuntime()) {
                var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failures::add,
                    task -> new Thread(task).start());
                lifecycle.submit(() -> {
                    try {
                        holder.values.result = 17;
                        if (fail) throw new IllegalArgumentException("fixture failure");
                        return null;
                    } finally { holder.values.nested.value = 29; }
                });
                // No worker-to-main signal is consumed before this get.
                lifecycle.awaitTask();
                assertEquals(17, holder.values.result);
                assertEquals(29, holder.values.nested.value);
                assertEquals(fail ? 1 : 0, failures.size());
            }
        }
    }

    private static final class Nested { int value; }

    @Test
    void endingUseAdoptsPendingLocalDeltaBeforeSealingTheInstallation() {
        for (boolean permanently : List.of(false, true)) {
            for (boolean asynchronous : List.of(false, true)) {
                try (var module = TestExpressionModules.create(Set.of("value"));
                    var runtime = new EntityExpressionRuntime()) {
                    TestExpressionModules.replace(runtime, module, Map.of(), RoamingRole.PRODUCER);
                    runtime.initializeRoaming(new RoamingPayload(new java.util.TreeMap<>()));
                    var installation = runtime.snapshot().orElseThrow().installation();
                    var retained = new java.util.HashMap<String, Float>();
                    var events = new ArrayList<String>();
                    var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                        events.add("close");
                        runtime.clear();
                    }, failure -> fail(failure), task -> new Thread(task).start());
                    var inputs = new EntityExpressionRuntime.InvocationInputs(
                        new InvocationInputs<net.minecraft.world.entity.Entity>() {},
                        (commands, provenance) -> () -> {}, () -> false);
                    java.util.function.Supplier<Object> update = () -> runtime.normalUpdate(inputs, evaluation -> {
                        runtime.roamingState().orElseThrow().write("value", 7);
                        return null;
                    }, delta -> {
                        assertTrue(installation.commit(() -> retained.putAll(delta.values())));
                        events.add("adopt");
                    });
                    if (asynchronous)
                        lifecycle.submit(update);
                    else
                        update.get();
                    assertTrue(retained.isEmpty());

                    if (permanently)
                        lifecycle.retire();
                    else
                        lifecycle.endUse();

                    assertEquals(Map.of("value", 7F), retained);
                    assertEquals(List.of("adopt", "close"), events);
                    assertFalse(installation.commit(() -> fail("Retired installation committed")));
                    assertEquals(permanently, lifecycle.isRetired());
                }
            }
        }
    }

    @Test
    void adoptionFailurePreservesTaskFailureAndStillSealsAndCleansUp() {
        try (var module = TestExpressionModules.create(Set.of("value"));
            var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of(), RoamingRole.PRODUCER);
            runtime.initializeRoaming(new RoamingPayload(new java.util.TreeMap<>()));
            var installation = runtime.snapshot().orElseThrow().installation();
            var original = new IllegalArgumentException("task");
            var adoptionFailure = new IllegalStateException("adoption");
            var closeFailure = new IllegalStateException("close");
            var events = new ArrayList<String>();
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
                events.add("close");
                throw closeFailure;
            }, failure -> { throw (RuntimeException)failure; }, task -> new Thread(task).start());
            var inputs = new EntityExpressionRuntime.InvocationInputs(
                new InvocationInputs<net.minecraft.world.entity.Entity>() {},
                (commands, provenance) -> () -> {}, () -> false);
            lifecycle.submit(() -> {
                runtime.normalUpdate(inputs, evaluation -> {
                    runtime.roamingState().orElseThrow().write("value", 7);
                    return null;
                }, delta -> {
                    events.add("adopt");
                    throw adoptionFailure;
                });
                throw original;
            });

            assertSame(original, assertThrows(IllegalArgumentException.class, lifecycle::retire));
            assertEquals(List.of("adopt", "close"), events);
            assertArrayEquals(new Throwable[] {adoptionFailure, closeFailure}, original.getSuppressed());
            assertFalse(installation.commit(() -> fail("Failed retirement left the installation open")));
            assertTrue(lifecycle.isRetired());
        }
    }

    @Test
    void retirementWaitsThroughInterruptAndCleanupFollowsExecutionFinally() throws Exception {
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> events.add("close"), failure -> fail(failure),
                task -> new Thread(task).start());
            lifecycle.submit(() -> {
                try {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                } catch (InterruptedException e) { throw new AssertionError(e); }
                finally { events.add("execution-exit"); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var main = Thread.currentThread();
            var gate = new Thread(() -> {
                while (main.getState() != Thread.State.WAITING) Thread.onSpinWait();
                release.countDown();
            });
            gate.start();
            main.interrupt();
            lifecycle.retire();
            events.add("retire-return");
            assertTrue(Thread.interrupted());
            gate.join(5000);
            assertEquals(List.of("execution-exit", "close", "retire-return"), events);
            assertTrue(lifecycle.tryInstallation(() -> "revived").isEmpty());
        } finally { release.countDown(); Thread.interrupted(); }
    }

    @Test
    void workerLifecycleAndSynchronousEvaluationReentryChangeNothing() {
        var closes = new ArrayList<String>();
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> closes.add("close"), failure -> fail(failure),
                task -> new Thread(task).start());
            lifecycle.submit(() -> {
                assertThrows(IllegalStateException.class, lifecycle::endUse);
                assertThrows(IllegalStateException.class, lifecycle::retire);
                return null;
            });
            lifecycle.awaitTask();
            lifecycle.inCall(() -> {
                assertThrows(IllegalStateException.class, lifecycle::endUse);
                return null;
            });
            assertTrue(closes.isEmpty());
            assertFalse(lifecycle.isRetired());
        }
    }

    @Test
    void drawRevokesImmediatelyAndClosesAfterBorrowEvenWhenCallbackFails() {
        var events = new ArrayList<String>();
        var original = new IllegalArgumentException("draw");
        var closeFailure = new IllegalStateException("close");
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> { events.add("close"); throw closeFailure; },
                failure -> fail(failure));
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> lifecycle.inDraw(() -> {
                lifecycle.retire();
                assertTrue(lifecycle.isRetired());
                assertTrue(events.isEmpty());
                throw original;
            })));
            assertEquals(List.of("close"), events);
            assertArrayEquals(new Throwable[]{closeFailure}, original.getSuppressed());
        }
    }

    @Test
    void candidateCannotReenterInstallation() {
        try (var runtime = new EntityExpressionRuntime()) {
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure));
            assertTrue(lifecycle.inCandidate(() -> lifecycle.tryInstallation(() -> "invalid").isEmpty()));
        }
    }
}
