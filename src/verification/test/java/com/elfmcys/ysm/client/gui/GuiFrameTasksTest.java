package com.elfmcys.ysm.client.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class GuiFrameTasksTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 8})
    void admissionBoundsNewWorkAndRotatesAcrossTenContinuouslyPendingCards(int maximumPoolSize) {
        var pending = new boolean[10];
        Arrays.fill(pending, true);
        var seen = new boolean[10];
        int cursor = 0;
        for (int frame = 0; frame < 10; frame++) {
            var admission = GuiFrameTasks.admit(pending, cursor, maximumPoolSize);
            assertEquals(maximumPoolSize + 1, count(admission.admitted()));
            for (int index = 0; index < seen.length; index++)
                seen[index] |= admission.admitted()[index];
            assertNotEquals(cursor, admission.nextCursor());
            cursor = admission.nextCursor();
        }
        assertEquals(10, count(seen));
        assertEquals(10, count(pending), "admission does not consume page demand");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 8})
    void rejectedPreparationDoesNotPinTheNextFrameToTheSamePrefix(int maximumPoolSize) {
        var pending = new boolean[10];
        Arrays.fill(pending, true);
        var seen = new boolean[10];
        int cursor = 0;
        for (int frame = 0; frame < 10; frame++) {
            var admission = GuiFrameTasks.admit(pending, cursor, maximumPoolSize);
            var jobs = new ArrayList<GuiFrameTasks.Job>();
            for (int index = 0; index < pending.length; index++) {
                if (!admission.admitted()[index])
                    continue;
                seen[index] = true;
                jobs.add(new EmptyJob());
            }
            assertInstanceOf(RejectedExecutionException.class, GuiFrameTasks.execute(jobs, command -> {
                throw new RejectedExecutionException("saturated");
            }));
            assertNotEquals(cursor, admission.nextCursor());
            cursor = admission.nextCursor();
        }
        assertEquals(10, count(seen));
    }

    @Test
    void admissionSkipsIneligibleCardsAndHandlesAnEmptyPage() {
        assertEquals(0, GuiFrameTasks.admit(new boolean[0], 7, 2).nextCursor());
        var admission = GuiFrameTasks.admit(new boolean[] {false, true, false, true}, -1, 2);
        assertArrayEquals(new boolean[] {false, true, false, true}, admission.admitted());
        assertEquals(3, admission.nextCursor());
    }

    @Test
    void otherJobsAreSubmittedBeforeFirstAndFirstPublishesBeforeWorkerDrain() throws Exception {
        try (var f = new Fixture()) {
            f.blockWorkers();
            var controllerFailure = new AtomicReference<Throwable>();
            var controller = new Thread(() -> {
                try {
                    await(f.firstPublished);
                    await(f.workersStarted);
                    assertEquals(1, f.jobs.get(1).terminal.getCount());
                    assertEquals(1, f.jobs.get(2).terminal.getCount());
                    f.events.add("controller-release");
                } catch (Throwable failure) {
                    controllerFailure.set(failure);
                } finally {
                    f.release.countDown();
                }
            }, "frame-test-controller");
            controller.start();
            assertNull(GuiFrameTasks.execute(f.jobs, f.executor(Set.of())));
            controller.join(5_000);
            assertFalse(controller.isAlive());
            assertNull(controllerFailure.get());
            before(f.events, "submit:1", "run:0");
            before(f.events, "submit:2", "run:0");
            before(f.events, "join:0", "publish:0");
            before(f.events, "publish:0", "controller-release");
            before(f.events, "publish:0", "join-enter:1");
            before(f.events, "join:1", "publish:1");
            before(f.events, "join:2", "publish:1");
            before(f.events, "join:1", "publish:2");
            before(f.events, "join:2", "publish:2");
            f.assertFinished();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"first-run", "first-register", "worker-run", "first-reject", "partial-reject", "both-run"})
    void everyAcceptedJobDrainsAndEveryJobFinishesAcrossFailurePaths(String scenario) throws Exception {
        try (var f = new Fixture()) {
            var primary = new IllegalStateException(scenario);
            var sibling = new IllegalArgumentException("worker sibling");
            Set<Integer> rejected = Set.of();
            switch (scenario) {
                case "first-run" -> f.jobs.get(0).runFailure = primary;
                case "first-register" -> f.jobs.get(0).publishFailure = primary;
                case "worker-run" -> f.jobs.get(1).runFailure = primary;
                case "first-reject" -> rejected = Set.of(1);
                case "partial-reject" -> rejected = Set.of(2);
                case "both-run" -> {
                    f.jobs.get(0).runFailure = primary;
                    f.jobs.get(1).runFailure = sibling;
                }
                default -> throw new AssertionError(scenario);
            }
            var failure = GuiFrameTasks.execute(f.jobs, f.executor(rejected));
            assertNotNull(failure);
            if (scenario.endsWith("reject"))
                assertInstanceOf(RejectedExecutionException.class, failure);
            else
                assertSame(primary, failure);
            if (scenario.equals("both-run"))
                assertTrue(Arrays.asList(failure.getSuppressed()).contains(sibling));
            for (var job : f.jobs) {
                assertEquals(rejected.contains(job.id) ? 1 : 0, job.rejected.get());
                assertEquals(rejected.contains(job.id) ? 0 : 1, job.joined.get());
                assertEquals(rejected.contains(job.id) ? 0 : 1, job.published.get());
                if (!rejected.contains(job.id) && job.id != 0) {
                    before(f.events, "join:" + job.id, "publish:" + job.id);
                    before(f.events, "join:" + job.id, "finish:0");
                }
            }
            f.assertFinished();
        }
    }

    @Test
    void rejectAndFinishFailuresRemainSuppressedAndCannotSkipSiblings() throws Exception {
        try (var f = new Fixture()) {
            var rejectFailure = new IllegalStateException("rejected claim cleanup");
            var firstFinishFailure = new IllegalArgumentException("first finish");
            var lastFinishFailure = new IllegalStateException("last finish");
            f.jobs.get(1).rejectFailure = rejectFailure;
            f.jobs.get(0).finishFailure = firstFinishFailure;
            f.jobs.get(2).finishFailure = lastFinishFailure;
            var failure = GuiFrameTasks.execute(f.jobs, f.executor(Set.of(1)));
            assertInstanceOf(RejectedExecutionException.class, failure);
            assertArrayEquals(new Throwable[] {rejectFailure, firstFinishFailure, lastFinishFailure}, failure.getSuppressed());
            f.assertFinished();
        }
    }

    @Test
    void interruptedOwnerStillDrainsQueuedWorkersAndRestoresItsInterrupt() throws Exception {
        try (var f = new Fixture()) {
            f.blockWorkers();
            var result = new AtomicReference<Throwable>();
            var restored = new AtomicBoolean();
            var owner = new Thread(() -> {
                result.set(GuiFrameTasks.execute(f.jobs, f.executor(Set.of())));
                restored.set(Thread.currentThread().isInterrupted());
            }, "frame-test-owner");
            owner.start();
            try {
                await(f.firstPublished);
                await(f.workersStarted);
                owner.interrupt();
                await(f.interruptedJoin);
                assertTrue(owner.isAlive());
                assertEquals(0, f.jobs.get(0).finished.get());
            } finally {
                f.release.countDown();
                owner.join(5_000);
            }
            assertFalse(owner.isAlive());
            assertNull(result.get());
            assertTrue(restored.get());
            f.assertFinished();
        }
    }

    @Test
    void saturatedSingleWorkerQueueDoesNotRequireCallbacksFromTheFrameOwner() throws Exception {
        try (var f = new Fixture(1)) {
            var occupied = new CountDownLatch(1);
            f.pool.execute(() -> {
                occupied.countDown();
                await(f.release);
            });
            await(occupied);
            var controllerFailure = new AtomicReference<Throwable>();
            var controller = new Thread(() -> {
                try {
                    await(f.firstPublished);
                    assertEquals(1, f.jobs.get(1).terminal.getCount());
                    assertEquals(1, f.jobs.get(2).terminal.getCount());
                } catch (Throwable failure) {
                    controllerFailure.set(failure);
                } finally {
                    f.release.countDown();
                }
            }, "saturated-frame-controller");
            controller.start();
            assertNull(GuiFrameTasks.execute(f.jobs, f.executor(Set.of())));
            controller.join(5_000);
            assertFalse(controller.isAlive());
            assertNull(controllerFailure.get());
            f.assertFinished();
        }
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values)
            if (value)
                count++;
        return count;
    }

    private static void before(List<String> events, String first, String second) {
        assertTrue(events.contains(first), () -> "missing " + first + ": " + events);
        assertTrue(events.contains(second), () -> "missing " + second + ": " + events);
        assertTrue(events.indexOf(first) < events.indexOf(second), () -> first + " before " + second + ": " + events);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "test control timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }

    private static class EmptyJob implements GuiFrameTasks.Job {
        public void run() {}
        public void join() {}
        public void reject() {}
        public void publish() {}
        public void finish() {}
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> events = new CopyOnWriteArrayList<>();
        final java.util.concurrent.ExecutorService pool;
        final CountDownLatch firstPublished = new CountDownLatch(1);
        final CountDownLatch workersStarted = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interruptedJoin = new CountDownLatch(1);
        final List<RecordingJob> jobs = List.of(new RecordingJob(0), new RecordingJob(1), new RecordingJob(2));

        Fixture() {
            this(2);
        }

        Fixture(int maximumPoolSize) {
            pool = Executors.newFixedThreadPool(maximumPoolSize);
        }

        void blockWorkers() {
            jobs.get(1).blocked = true;
            jobs.get(2).blocked = true;
        }

        Executor executor(Set<Integer> rejected) {
            return command -> {
                var job = (RecordingJob)command;
                events.add("submit:" + job.id);
                if (rejected.contains(job.id))
                    throw new RejectedExecutionException("rejected " + job.id);
                pool.execute(job);
            };
        }

        void assertFinished() {
            for (var job : jobs) {
                assertEquals(1, job.finished.get(), "finish " + job.id);
                if (job.rejected.get() == 0)
                    assertEquals(0, job.terminal.getCount(), "worker exit " + job.id);
            }
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        final class RecordingJob implements GuiFrameTasks.Job {
            final int id;
            final CountDownLatch terminal = new CountDownLatch(1);
            final AtomicInteger joined = new AtomicInteger();
            final AtomicInteger rejected = new AtomicInteger();
            final AtomicInteger published = new AtomicInteger();
            final AtomicInteger finished = new AtomicInteger();
            boolean blocked;
            Throwable runFailure;
            Throwable publishFailure;
            Throwable rejectFailure;
            Throwable finishFailure;
            Throwable returnedFailure;

            RecordingJob(int id) {
                this.id = id;
            }

            public void run() {
                events.add("run:" + id);
                try {
                    if (id != 0)
                        workersStarted.countDown();
                    if (blocked)
                        await(release);
                    rethrow(runFailure);
                } catch (RuntimeException | Error failure) {
                    returnedFailure = failure;
                    if (id == 0)
                        throw failure;
                } finally {
                    events.add("exit:" + id);
                    terminal.countDown();
                }
            }

            public void join() {
                events.add("join-enter:" + id);
                boolean interrupted = false;
                try {
                    while (true) {
                        try {
                            terminal.await();
                            break;
                        } catch (InterruptedException failure) {
                            interrupted = true;
                            interruptedJoin.countDown();
                        }
                    }
                } finally {
                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
                joined.incrementAndGet();
                events.add("join:" + id);
                rethrow(returnedFailure);
            }

            public void reject() {
                rejected.incrementAndGet();
                events.add("reject:" + id);
                rethrow(rejectFailure);
            }

            public void publish() {
                assertEquals(0, terminal.getCount(), "publication requires ownership return");
                published.incrementAndGet();
                events.add("publish:" + id);
                if (id == 0)
                    firstPublished.countDown();
                rethrow(publishFailure);
            }

            public void finish() {
                assertTrue(rejected.get() != 0 || terminal.getCount() == 0, "finish cannot retire an active worker");
                finished.incrementAndGet();
                events.add("finish:" + id);
                rethrow(finishFailure);
            }
        }
    }
}
