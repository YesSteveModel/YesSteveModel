package com.elfmcys.ysm.model.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DerivedStorageWorkTest {
    @Test
    void defaultAdmissionKeepsSixteenJobsAndOneWorker() throws Exception {
        var release = new CountDownLatch(1);
        try (var work = new DerivedStorageWork()) {
            var entered = new CountDownLatch(1);
            var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
            assertNotNull(work.submit(gate -> {
                worker.set(Thread.currentThread());
                entered.countDown();
                awaitUninterruptibly(release);
                gate.commit(() -> {});
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 1; i < 16; i++) {
                assertNotNull(work.submit(gate -> {
                    assertSame(worker.get(), Thread.currentThread());
                    gate.commit(() -> {});
                }));
            }
            assertEquals(16, work.snapshot().jobs());
            assertNull(work.submit(gate -> fail("The seventeenth job must not run")));
            release.countDown();
            work.awaitIdle();
            assertEquals(16, work.snapshot().succeeded());
        } finally {
            release.countDown();
        }
    }

    @Test
    void admissionIsBoundedAndCancellationRetainsTheActiveBorrow() throws Exception {
        try (var work = new DerivedStorageWork(2)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var active = work.submit(gate -> {
                entered.countDown();
                awaitUninterruptibly(release);
                gate.commit(effects::incrementAndGet);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = work.submit(gate -> gate.commit(effects::incrementAndGet));
            assertNull(work.submit(gate -> fail("Task count must be bounded")));
            assertTrue(active.cancel());
            assertEquals(2, work.snapshot().jobs());
            assertTrue(queued.cancel());
            assertEquals(1, work.snapshot().jobs());
            release.countDown();
            work.awaitIdle();
            assertEquals(0, effects.get());
            assertEquals(0, work.snapshot().jobs());
            assertEquals(2, work.snapshot().cancelled());
            assertFalse(active.cancel());
        }
    }

    @Test
    void realAtomicWritesKeepTheOldFileOnValidationAndMoveFailure(@TempDir Path root) throws Exception {
        for (var failure : new String[] {"write", "validate", "flush", "move", "gate"}) {
            try (var work = new DerivedStorageWork(2)) {
                var target = root.resolve(failure).resolve("exact");
                Files.createDirectories(target.getParent());
                Files.writeString(target, "old");
                var cache = new AtomicSharedCache(root.resolve(failure), replacement -> {
                    if (failure.equals("move"))
                        throw new IOException("Injected move failure");
                    replacement.run();
                }, file -> {
                    file.force();
                    if (failure.equals("flush"))
                        throw new IOException("Injected staging flush failure");
                });
                var job = work.submit(gate -> cache.replaceValidated("test", "exact", target,
                    file -> !failure.equals("validate") && new String(file.readAllBytes()).equals("new"), file -> {
                        file.writeBytes("new".getBytes());
                        if (failure.equals("write"))
                            throw new IOException("Injected staging write failure");
                    }, replacement -> {
                        if (failure.equals("gate"))
                            throw new IOException("Injected commit gate failure");
                        gate.commit(replacement);
                    }));
                work.awaitIdle();
                assertEquals(DerivedStorageWork.Outcome.FAILED, job.outcome());
                assertEquals("old", Files.readString(target));
                try (var files = Files.list(target.getParent())) {
                    assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
                }
                assertEquals(0, work.snapshot().jobs());
            }
        }
    }

    @Test
    void shutdownSealsCommitBeforeWaitingForTheBorrow(@TempDir Path root) throws Exception {
        var work = new DerivedStorageWork(2);
        var cache = new AtomicSharedCache(root);
        var target = root.resolve("exact");
        Files.writeString(target, "old");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var job = work.submit(gate -> cache.replaceValidated("test", "exact", target, file -> true, file -> {
            file.writeBytes("new".getBytes());
            entered.countDown();
            awaitUninterruptibly(release);
        }, gate));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var queued = work.submit(gate -> fail("Queued work must not run after shutdown"));
        var closer = new Thread(work::close);
        closer.start();
        while (!work.snapshot().closed())
            Thread.onSpinWait();
        assertNull(work.submit(gate -> fail("Sealed admission")));
        assertEquals(DerivedStorageWork.Outcome.SHUTDOWN, job.outcome());
        assertEquals(DerivedStorageWork.Outcome.SHUTDOWN, queued.outcome());
        assertEquals(1, work.snapshot().jobs());
        release.countDown();
        closer.join(5000);
        assertFalse(closer.isAlive());
        assertEquals("old", Files.readString(target));
        assertEquals(0, work.snapshot().jobs());
    }

    @Test
    void committedSuccessSurvivesLaterFailureAndCancellation() throws Exception {
        try (var work = new DerivedStorageWork()) {
            var job = work.submit(gate -> {
                gate.commit(() -> {
                });
                throw new IOException("Notification/cleanup failed after commit");
            });
            work.awaitIdle();
            assertEquals(DerivedStorageWork.Outcome.SUCCEEDED, job.outcome());
            assertFalse(job.cancel());
            assertEquals(1, work.snapshot().succeeded());
            assertEquals(0, work.snapshot().failed());
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
