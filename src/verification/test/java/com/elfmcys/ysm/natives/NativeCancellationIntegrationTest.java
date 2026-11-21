package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
@Timeout(15)
class NativeCancellationIntegrationTest {
    @Test
    void requestsAreOneWayIndependentAndConcurrentWithConsumers() throws Exception {
        try (var token = NativeCancellation.create(); var independent = NativeCancellation.create()) {
            assertFalse(token.isRequested());
            var entered = new CountDownLatch(4);
            var inspect = new CountDownLatch(1);
            var consumers = new ArrayList<FutureTask<Integer>>();
            for (int i = 0; i < 4; i++) {
                consumers.add(start(() -> token.borrow(handle -> {
                    entered.countDown();
                    await(inspect);
                    return query(handle);
                })));
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var requesters = new ArrayList<FutureTask<Void>>();
                for (int i = 0; i < 4; i++) {
                    requesters.add(start(() -> {
                        for (int request = 0; request < 100; request++) {
                            token.request();
                            assertTrue(token.isRequested());
                        }
                        return null;
                    }));
                }
                for (var requester : requesters) {
                    requester.get(5, TimeUnit.SECONDS);
                }
                assertFalse(independent.isRequested());
            } finally {
                inspect.countDown();
            }
            for (var consumer : consumers) {
                assertEquals(1, consumer.get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void closeSealsAdmissionAndWaitsForTheLastBorrowBeforeDestruction() throws Exception {
        var token = NativeCancellation.create();
        var open = cleanupState(token);
        var entered = new CountDownLatch(2);
        var firstExit = new CountDownLatch(1);
        var lastExit = new CountDownLatch(1);
        var first = consumer(token, entered, firstExit);
        var last = consumer(token, entered, lastExit);
        FutureTask<Void> closing = null;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            token.request();
            closing = start(() -> {
                token.close();
                return null;
            });
            awaitCondition(() -> isClosing(token));
            assertThrows(IllegalStateException.class, token::request);
            assertThrows(IllegalStateException.class, token::isRequested);
            assertThrows(IllegalStateException.class, () -> token.borrow(handle -> fail("New borrow admitted")));
            assertTrue(open.get(), "Close destroyed a handle with active consumers");
            assertFalse(closing.isDone());
            firstExit.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertTrue(open.get(), "The last native consumer still owns a Java borrow");
            assertFalse(closing.isDone());
        } finally {
            firstExit.countDown();
            lastExit.countDown();
            first.get(5, TimeUnit.SECONDS);
            last.get(5, TimeUnit.SECONDS);
            if (closing != null) {
                closing.get(5, TimeUnit.SECONDS);
            }
            token.close();
        }
        assertFalse(open.get());
        token.close();
        assertThrows(IllegalStateException.class, token::get);
    }

    @Test
    void exceptionalBorrowAndInterruptedCloseStillReleaseTheHandle() throws Exception {
        var token = NativeCancellation.create();
        var failure = new IllegalArgumentException("Injected consumer failure");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> token.borrow(handle -> {
            throw failure;
        })));
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var consumer = consumer(token, entered, exit);
        var interrupted = new AtomicBoolean();
        var closing = new FutureTask<Void>(() -> {
            token.close();
            interrupted.set(Thread.currentThread().isInterrupted());
            return null;
        });
        var closer = new Thread(closing);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            closer.start();
            awaitCondition(() -> isClosing(token));
            closer.interrupt();
            assertTrue(cleanupState(token).get());
        } finally {
            exit.countDown();
            consumer.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            token.close();
        }
        assertTrue(interrupted.get());
        assertFalse(cleanupState(token).get());
    }

    @Test
    void cleanerRetainsTheActiveBorrowThenReleasesTheUnreferencedWrapper() throws Exception {
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var held = startUnownedBorrow(entered, exit);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) {
                System.gc();
                assertNotNull(held.wrapper().get());
                assertTrue(held.open().get());
            }
        } finally {
            exit.countDown();
            assertEquals(0, held.consumer().get(5, TimeUnit.SECONDS));
        }
        awaitCondition(() -> {
            System.gc();
            return held.wrapper().get() == null && !held.open().get();
        });
    }

    @Test
    void adoptionRollsBackAllocationConstructionAndCleanerRegistrationFailures() throws Exception {
        var create = method("nCreate");
        Constructor<NativeCancellation> constructor =
            NativeCancellation.class.getDeclaredConstructor(NativeObject.State.class);
        constructor.setAccessible(true);
        for (int point = 0; point < 3; point++) {
            int failurePoint = point;
            var state = new AtomicReference<NativeObject.State>();
            var wrapper = new AtomicReference<NativeCancellation>();
            var allocationFailure = new OutOfMemoryError("Injected wrapper failure");
            Function<NativeObject.State, NativeCancellation> factory = adopted -> {
                state.set(adopted);
                if (failurePoint == 1) {
                    try {
                        wrapper.set(constructor.newInstance(adopted));
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError(exception);
                    }
                }
                if (failurePoint == 2) {
                    Cleaner.create().register(null, () -> {
                    });
                }
                throw allocationFailure;
            };
            long handle = (long)create.invoke(null);
            assertNotEquals(0, handle);
            if (point == 2) {
                assertThrows(NullPointerException.class, () -> NativeObject.adopt(handle, factory));
            } else {
                assertSame(allocationFailure,
                    assertThrows(OutOfMemoryError.class, () -> NativeObject.adopt(handle, factory)));
            }
            assertFalse(cleanupState(state.get()).get());
            if (wrapper.get() != null) {
                assertThrows(IllegalStateException.class, wrapper.get()::get);
                wrapper.get().close();
            }
        }
    }

    @Test
    void jniFailureSentinelsCannotBecomeAnUnrequestedStateOrSuccessfulRequest() throws Exception {
        assertFalse((boolean)method("nRequest", long.class).invoke(null, 0L));
        assertEquals(-1, method("nQuery", long.class).invoke(null, 0L));
        try (var token = NativeCancellation.create()) {
            assertEquals(0, token.borrow(NativeCancellationIntegrationTest::query));
            token.borrow(handle -> {
                token.request();
                return null;
            });
            assertTrue(token.isRequested());
        }
    }

    private static Held startUnownedBorrow(CountDownLatch entered, CountDownLatch exit) throws Exception {
        var token = NativeCancellation.create();
        return new Held(new WeakReference<>(token), cleanupState(token), consumer(token, entered, exit));
    }

    private static FutureTask<Integer> consumer(NativeCancellation token, CountDownLatch entered, CountDownLatch exit) {
        return start(() -> token.borrow(handle -> {
            entered.countDown();
            await(exit);
            return query(handle);
        }));
    }

    private static <T> FutureTask<T> start(java.util.concurrent.Callable<T> operation) {
        var task = new FutureTask<>(operation);
        new Thread(task).start();
        return task;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static boolean isClosing(NativeCancellation token) {
        synchronized (token) {
            try {
                var field = NativeCancellation.class.getDeclaredField("closing");
                field.setAccessible(true);
                return field.getBoolean(token);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static AtomicBoolean cleanupState(NativeObject owner) throws Exception {
        var field = NativeObject.class.getDeclaredField("state");
        field.setAccessible(true);
        return cleanupState((NativeObject.State)field.get(owner));
    }

    private static AtomicBoolean cleanupState(NativeObject.State state) throws Exception {
        var field = NativeObject.State.class.getDeclaredField("open");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(state);
    }

    private static int query(long handle) {
        try {
            return (int)method("nQuery", long.class).invoke(null, handle);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Method method(String name, Class<?>... arguments) throws NoSuchMethodException {
        var method = NativeCancellation.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private record Held(WeakReference<NativeCancellation> wrapper, AtomicBoolean open, FutureTask<Integer> consumer) {
    }
}
