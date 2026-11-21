package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.NativePlayerData;
import com.elfmcys.ysm.natives.storage.StorageDeliveryException;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class DatabaseWorkTest {
    @TempDir
    Path temp;

    @Test
    void boundedAdmissionCancellationAndCloseKeepResourcesUntilActualReturn() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var owner = new AtomicReference<NativePlayerData>();
        var borrowedToken = new AtomicReference<NativeCancellation>();
        try (var work = new DatabaseWork()) {
            var resource = work.createResource(token -> NativePlayerData.open(temp, token));
            var active = work.submit(resource, (db, cancelled) -> {
                owner.set(db);
                borrowedToken.set(cancelled);
                var committed = db.putPreference(
                    NativePlayerData.PreferenceKey.roaming(UUID.randomUUID(), PublishedModelId.INTRINSIC_DEFAULT),
                    ArrayBuffer.borrow(new byte[] {7}), 1, cancelled);
                assertTrue(committed.committed());
                entered.countDown();
                awaitUninterruptibly(release);
                assertTrue(cancelled.isRequested());
                return committed;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var neverOpen = work.createResource(token -> {
                throw new AssertionError("Cancelled queued work must not open storage");
            });
            var queued = new ArrayList<DatabaseWork.Job<Void>>();
            for (int index = 0; index < 63; index++)
                queued.add(work.submit(neverOpen, (unused, cancelled) -> {
                    throw new AssertionError("Queued work must be cancelled");
                }));
            var overCapacity =
                work.submit(resource, (db, cancelled) -> StorageResult.<Void>refused(StorageResult.Failure.INTERNAL))
                    .result().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(StorageResult.Failure.CAPACITY, overCapacity.failure());
            assertEquals(StorageResult.Outcome.REJECTED, overCapacity.outcome());
            var tokenField = queued.get(0).getClass().getDeclaredField("token");
            tokenField.setAccessible(true);
            var queuedToken = (NativeCancellation)tokenField.get(queued.get(0));
            queued.get(0).cancel();
            assertEquals(StorageResult.Outcome.CANCELLED,
                queued.get(0).result().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome());
            assertThrows(IllegalStateException.class, queuedToken::isRequested);
            queued.get(0).cancel();
            var closed = new CountDownLatch(1);
            var closer = new Thread(() -> {
                work.close();
                closed.countDown();
            });
            closer.start();
            assertEquals(StorageResult.Failure.CLOSED,
                queued.get(1).result().toCompletableFuture().get(2, TimeUnit.SECONDS).failure());
            assertFalse(active.result().toCompletableFuture().isDone());
            assertEquals(1, closed.getCount());
            assertNotEquals(0, owner.get().get());
            long cancelDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!borrowedToken.get().isRequested() && System.nanoTime() < cancelDeadline)
                Thread.sleep(1);
            assertTrue(borrowedToken.get().isRequested());
            release.countDown();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(active.result().toCompletableFuture().get().committed());
            assertThrows(IllegalStateException.class, owner.get()::get);
            assertThrows(IllegalStateException.class, borrowedToken.get()::isRequested);
            var afterClose =
                work.submit(resource, (db, cancelled) -> StorageResult.<Void>refused(StorageResult.Failure.INTERNAL))
                    .result().toCompletableFuture().get();
            assertEquals(StorageResult.Failure.CLOSED, afterClose.failure());
            assertEquals(StorageResult.Outcome.REJECTED, afterClose.outcome());
        } finally {
            release.countDown();
        }
    }

    @Test
    void idleWorkerReusesTheSameBusinessObjectAndUnusedResourceNeverOpens() throws Exception {
        var opens = new AtomicInteger();
        var firstOwner = new AtomicReference<NativePlayerData>();
        try (var work = new DatabaseWork()) {
            var resource = work.createResource(token -> {
                opens.incrementAndGet();
                return NativePlayerData.open(temp, token);
            });
            work.createResource(token -> {
                throw new AssertionError("An unused business resource must not open storage");
            });
            var key = NativePlayerData.PreferenceKey.roaming(UUID.randomUUID(), PublishedModelId.INTRINSIC_DEFAULT);
            var first = work.submit(resource, (db, cancelled) -> {
                firstOwner.set(db);
                return db.putPreference(key, ArrayBuffer.borrow(new byte[0]), 0, cancelled);
            });
            assertTrue(first.result().toCompletableFuture().get(2, TimeUnit.SECONDS).committed());
            work.awaitIdle();
            var later = work.submit(resource, (db, cancelled) -> {
                assertSame(firstOwner.get(), db);
                return db.readPreference(key, cancelled);
            });
            assertEquals(StorageResult.Outcome.FOUND,
                later.result().toCompletableFuture().get(2, TimeUnit.SECONDS).outcome());
            assertEquals(1, opens.get());
            var inaccessible =
                work.createResource(token -> NativePlayerData.open(temp.resolve("missing/parent"), token));
            var failed = work.submit(inaccessible, (db, cancelled) -> db.readPreference(key, cancelled));
            assertEquals(StorageResult.Failure.ACCESS_IO,
                failed.result().toCompletableFuture().get(2, TimeUnit.SECONDS).failure());
        }
        assertThrows(IllegalStateException.class, firstOwner.get()::get);
    }

    @Test
    void deliveryFailureRemainsExceptionalWithItsKnownCommitAndDoesNotPoisonTheNextToken() throws Exception {
        var injected = new OutOfMemoryError("Injected result construction failure");
        var token = new AtomicReference<NativeCancellation>();
        try (var work = new DatabaseWork()) {
            var resource = work.createResource(cancelled -> NativePlayerData.open(temp, cancelled));
            var key = NativePlayerData.PreferenceKey.roaming(UUID.randomUUID(), PublishedModelId.INTRINSIC_DEFAULT);
            var job = work.submit(resource, (db, cancelled) -> {
                token.set(cancelled);
                var committed = db.putPreference(key, ArrayBuffer.borrow(new byte[] {9}), 1, cancelled);
                throw StorageDeliveryException.after(committed, injected);
            });
            var failed = assertThrows(ExecutionException.class,
                () -> job.result().toCompletableFuture().get(2, TimeUnit.SECONDS));
            var delivery = assertInstanceOf(StorageDeliveryException.class, failed.getCause());
            assertEquals(StorageDeliveryException.CommitState.COMMITTED, delivery.commitState());
            assertSame(injected, delivery.getCause());
            assertThrows(IllegalStateException.class, token.get()::isRequested);
            var read = work.submit(resource, (db, cancelled) -> {
                assertFalse(cancelled.isRequested());
                return db.readPreference(key, cancelled);
            }).result().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(9, read.rows().get(0).value().nio().get());
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                if (!latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                    throw new AssertionError("Controlled operation did not receive its release signal");
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
