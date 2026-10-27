package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.natives.ThreadSchedulingResult.Attribute;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.Operation;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.Outcome;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.Platform;
import com.elfmcys.ysm.natives.ThreadSchedulingResult.StepResult;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
@Timeout(30)
class ThreadSchedulingIntegrationTest {
    @Test
    void jniChangesOnlyItsCallingPlatformThreadAndReadsBackTheOsValue() throws Exception {
        var parent = cpuPriority();
        onThread(() -> {
            var result = NativeRuntime.applyCurrentThreadPolicy(ThreadPolicy.BELOW_NORMAL);
            assertApplied(result, ThreadPolicy.BELOW_NORMAL);
            var independent = cpuPriority();
            if (independent.isPresent()) {
                assertEquals(independent.getAsLong(),
                    result.observations().value(Attribute.CPU_PRIORITY).orElseThrow());
            }
            return null;
        });
        assertEquals(parent, cpuPriority());
    }

    @Test
    void repeatingLowPreservesAnExistingBackgroundModeWithoutRollback() throws Exception {
        onThread(() -> {
            assertApplied(NativeRuntime.applyCurrentThreadPolicy(ThreadPolicy.LOW), ThreadPolicy.LOW);
            if (System.getProperty("os.name").startsWith("Windows")) {
                windowsMemoryPriority().ifPresent(priority -> assertEquals(5, priority));
            }
            var repeated = NativeRuntime.applyCurrentThreadPolicy(ThreadPolicy.LOW);
            assertApplied(repeated, ThreadPolicy.LOW);
            assertTrue(repeated.steps().stream().noneMatch(step -> step.operation() == Operation.END_BACKGROUND));
            if (repeated.platform() == Platform.WINDOWS && repeated.steps().stream().anyMatch(
                step -> step.operation() == Operation.BEGIN_BACKGROUND && step.result() == StepResult.FAILED)) {
                assertEquals(Outcome.DEGRADED, repeated.outcome());
            }
            return null;
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void bothPoliciesPreserveAnIdleHostThreadWithoutChangingItsParent() throws Exception {
        var parentCpu = cpuPriority();
        var parentMemory = windowsMemoryPriority();
        for (var policy : new ThreadPolicy[] {ThreadPolicy.BELOW_NORMAL, ThreadPolicy.LOW}) {
            onThread(() -> {
                var kernel = NativeLibrary.getInstance("kernel32");
                var thread = kernel.getFunction("GetCurrentThread").invokePointer(new Object[0]);
                assertNotEquals(0, kernel.getFunction("SetThreadPriority").invokeInt(new Object[] {thread, -15}));
                var beforeCpu = cpuPriority().orElseThrow();
                var beforeMemory = windowsMemoryPriority();
                assertEquals(-15, beforeCpu);

                var report = NativeRuntime.applyCurrentThreadPolicy(policy);
                var afterCpu = cpuPriority().orElseThrow();
                assertTrue(afterCpu <= beforeCpu, report::toString);
                assertEquals(afterCpu, report.observations().value(Attribute.CPU_PRIORITY).orElseThrow());
                assertEquals(beforeMemory, windowsMemoryPriority());
                if (policy == ThreadPolicy.LOW) {
                    assertEquals(Outcome.DEGRADED, report.outcome());
                    assertTrue(report.steps().stream().noneMatch(step -> step.operation() == Operation.END_BACKGROUND
                        || step.operation() == Operation.BEGIN_BACKGROUND && step.result() != StepResult.SKIPPED));
                } else {
                    assertEquals(Outcome.APPLIED, report.outcome());
                }
                return null;
            });
            assertEquals(parentCpu, cpuPriority());
            assertEquals(parentMemory, windowsMemoryPriority());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void inheritedLinuxNiceIsNeverRaisedByABelowNormalEntry() throws Exception {
        onThread(() -> {
            var low = NativeRuntime.applyCurrentThreadPolicy(ThreadPolicy.LOW);
            assertApplied(low, ThreadPolicy.LOW);
            var inherited = onThread(() -> {
                var before = cpuPriority().orElseThrow();
                var report = NativeRuntime.applyCurrentThreadPolicy(ThreadPolicy.BELOW_NORMAL);
                assertApplied(report, ThreadPolicy.BELOW_NORMAL);
                assertTrue(report.observations().cpuPriority() >= before);
                return report;
            });
            assertTrue(inherited.observations().cpuPriority() >= low.observations().cpuPriority());
            return null;
        });
    }

    @Test
    void replacementWorkerRunsTheSameFixedEntryPolicy() throws Exception {
        var created = new AtomicInteger();
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), body -> {
            created.incrementAndGet();
            var thread = new Thread(ThreadScheduling.entry(ThreadRole.SERVER_PREVIEW, ThreadPolicy.BELOW_NORMAL, body),
                "thread-policy-replacement-test");
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
            });
            return thread;
        });
        try {
            var first = new CompletableFuture<Thread>();
            executor.execute(() -> {
                first.complete(Thread.currentThread());
                throw new PlannedWorkerExit();
            });
            var exited = first.get(5, TimeUnit.SECONDS);
            var replacement = new CompletableFuture<Thread>();
            executor.execute(() -> {
                try {
                    var independent = cpuPriority();
                    if (independent.isPresent()) {
                        var windows = System.getProperty("os.name").startsWith("Windows");
                        assertTrue(windows ? independent.getAsLong() <= -1 : independent.getAsLong() >= 5);
                    }
                    replacement.complete(Thread.currentThread());
                } catch (Throwable failure) {
                    replacement.completeExceptionally(failure);
                }
            });
            assertNotSame(exited, replacement.get(5, TimeUnit.SECONDS));
            assertEquals(2, created.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertApplied(ThreadSchedulingResult report, ThreadPolicy policy) {
        assertTrue(report.outcome() == Outcome.APPLIED || report.outcome() == Outcome.DEGRADED, report::toString);
        switch (report.platform()) {
            case WINDOWS -> {
                if (policy == ThreadPolicy.LOW && report.outcome() == Outcome.APPLIED) {
                    assertEquals(5, report.observations().value(Attribute.MEMORY_PRIORITY).orElseThrow());
                } else {
                    assertTrue(report.observations().value(Attribute.CPU_PRIORITY)
                        .orElseThrow() <= (policy == ThreadPolicy.LOW ? -2 : -1), report::toString);
                }
            }
            case LINUX, ANDROID -> {
                assertTrue(report.observations().value(Attribute.CPU_PRIORITY)
                    .orElseThrow() >= (policy == ThreadPolicy.LOW ? 10 : 5), report::toString);
                assertTrue(report.observations().value(Attribute.SCHEDULER_POLICY).isPresent());
            }
            case MACOS -> {
                assertTrue(report.observations().value(Attribute.QOS_CLASS).isPresent());
                assertTrue(report.observations().value(Attribute.QOS_RELATIVE_PRIORITY).isPresent());
            }
            case UNKNOWN -> fail(report.toString());
        }
    }

    private static OptionalLong cpuPriority() {
        var os = System.getProperty("os.name");
        if (os.startsWith("Windows")) {
            var kernel = NativeLibrary.getInstance("kernel32");
            var thread = kernel.getFunction("GetCurrentThread").invokePointer(new Object[0]);
            return OptionalLong.of(kernel.getFunction("GetThreadPriority").invokeInt(new Object[] {thread}));
        }
        if (os.equals("Linux")) {
            return OptionalLong
                .of(NativeLibrary.getInstance("c").getFunction("getpriority").invokeInt(new Object[] {0, 0}));
        }
        return OptionalLong.empty();
    }

    private static OptionalLong windowsMemoryPriority() {
        var kernel = NativeLibrary.getInstance("kernel32");
        final Function query;
        try {
            query = kernel.getFunction("GetThreadInformation");
        } catch (UnsatisfiedLinkError unavailable) {
            return OptionalLong.empty();
        }
        var thread = kernel.getFunction("GetCurrentThread").invokePointer(new Object[0]);
        var memory = new Memory(Integer.BYTES);
        assertNotEquals(0, query.invokeInt(new Object[] {thread, 0, memory, Integer.BYTES}));
        return OptionalLong.of(Integer.toUnsignedLong(memory.getInt(0)));
    }

    private static <T> T onThread(Callable<T> body) throws Exception {
        var result = new CompletableFuture<T>();
        var thread = new Thread(() -> {
            try {
                result.complete(body.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, "thread-policy-jni-test");
        thread.start();
        try {
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            thread.join(5_000);
            assertFalse(thread.isAlive());
        }
    }

    private static final class PlannedWorkerExit extends Error {}
}
