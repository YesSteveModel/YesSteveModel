package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityExecutionLifecycleTest {
    @Test
    void installationReturnsBusyWhileTheExistingBarrierIsBorrowed() throws Exception {
        var runtime = new EntityExpressionRuntime();
        var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
        }, failure -> {
            throw new AssertionError(failure);
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new Thread(() -> lifecycle.inCall(() -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
            return true;
        }));
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            assertTrue(lifecycle.tryInstallation(() -> {
                throw new AssertionError("Busy must not build a candidate");
            }).isEmpty());
            assertTrue(worker.isAlive());
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertEquals("installed", lifecycle.tryInstallation(() -> "installed").orElseThrow());
        runtime.close();
    }

    @Test
    void candidateAssemblyCannotReenterInstallation() {
        var runtime = new EntityExpressionRuntime();
        var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
        }, failure -> {
            throw new AssertionError(failure);
        });
        assertTrue(lifecycle.inCandidate(() -> lifecycle.tryInstallation(() -> "invalid").isEmpty()));
        runtime.close();
    }

    @Test
    void installationReapsASealedCompletedTaskBeforeRecovery() throws Exception {
        com.elfmcys.ysm.util.NativeLibUtil.load();
        var runtime = new EntityExpressionRuntime();
        var order = new ArrayList<String>();
        var failure = new IllegalStateException("component failure");
        var cleanupFailure = new IllegalStateException("cleanup failure");
        var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
            order.add("cleanup");
            throw cleanupFailure;
        }, actual -> {
            assertSame(failure, actual);
            assertArrayEquals(new Throwable[]{cleanupFailure}, actual.getSuppressed());
            order.add("failure");
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        lifecycle.submit(() -> {
            lifecycle.endUse();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            throw failure;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            assertTrue(lifecycle.tryInstallation(() -> "invalid").isEmpty());
            assertTrue(order.isEmpty());
        } finally {
            release.countDown();
        }
        var taskField = EntityExecutionLifecycle.class.getDeclaredField("asyncTask");
        taskField.setAccessible(true);
        var task = (FutureTask<?>) taskField.get(lifecycle);
        assertSame(failure, assertThrows(ExecutionException.class,
            () -> task.get(5, TimeUnit.SECONDS)).getCause());
        assertEquals("recovered", lifecycle.tryInstallation(() -> {
            assertEquals(java.util.List.of("cleanup", "failure"), order);
            return "recovered";
        }).orElseThrow());
        assertFalse(lifecycle.hasTask());
        assertFalse(lifecycle.isEndingUse());
        runtime.close();
    }

    @Test
    void synchronousCleanupPreservesTheOriginalFailure() {
        var runtime = new EntityExpressionRuntime();
        var original = new IllegalArgumentException("hook failed");
        var cleanup = new IllegalStateException("cleanup failed");
        var lifecycle = new EntityExecutionLifecycle(runtime, () -> {
            throw cleanup;
        }, failure -> fail(failure));
        assertSame(original, assertThrows(IllegalArgumentException.class, () -> lifecycle.inCall(() -> {
            lifecycle.endUse();
            throw original;
        })));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        assertFalse(lifecycle.isEndingUse());
        runtime.close();
    }
}
