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
    void completedButUnjoinedTaskRemainsBusy() throws Exception {
        try (var runtime = new EntityExpressionRuntime()) {
            var queue = new ArrayList<Runnable>();
            var lifecycle = new EntityExecutionLifecycle(runtime, () -> {}, failure -> fail(failure), queue::add);
            lifecycle.submit(() -> 42);
            var worker = new Thread(queue.get(0));
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(lifecycle.tryInstallation(() -> { throw new AssertionError("Unjoined task was read"); }).isEmpty());
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
