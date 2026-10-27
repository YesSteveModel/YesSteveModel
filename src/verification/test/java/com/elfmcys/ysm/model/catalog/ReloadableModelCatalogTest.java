package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogPresentation;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedCacheCoordinator;
import com.elfmcys.ysm.model.storage.ConvertedGateProcess;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.util.CleanerUtil;
import java.lang.ref.Cleaner;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadableModelCatalogTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static Path defaultFixture;
    private static Path firstFixture;
    private static Path secondFixture;

    @BeforeAll
    static void createFixtures() throws Exception {
        defaultFixture = parseFixture("/assets/ysm/builtin/default/ysm.json", "default", true);
        firstFixture = parseFixture("/assets/ysm/builtin/misc/1_alex/ysm.json", "first", false);
        secondFixture = parseFixture("/assets/ysm/builtin/misc/2_steve/ysm.json", "second", false);
    }

    @Test
    void memoryFailureEndsTheScanAndAllowsTheNextIndependentScan() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var failures = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = new AtomicReference<Thread>();
        var memory = new OutOfMemoryError("injected");
        try (var fixture = open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, body -> {
            var thread = new Thread(body);
            thread.setUncaughtExceptionHandler((ignored, failure) -> failures.set(failure));
            worker.set(thread);
            return thread;
        }, (sequence, policy) -> java.util.concurrent.Executors.newSingleThreadExecutor(), () -> {
        }, progress -> {
            if (armed.get() && progress.phase() == CatalogScanProgress.Phase.PROCESSING)
                throw new java.util.concurrent.CompletionException(memory);
        }))) {
            fixture.activateAndFinish();
            var previous = fixture.catalog().currentCandidate();
            Files.delete(root.resolve("first.mxc"));
            Files.copy(secondFixture, root.resolve("second.mxc"));
            armed.set(true);
            var failed = fixture.catalog().reload();
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            assertFalse(failed.isDone());
            assertEquals(CatalogScanProgress.Phase.FINALIZING, fixture.catalog().scanProgress().phase());
            assertEquals(ReloadStatus.FAILED, fixture.finish(failed).status());
            assertEquals(previous.snapshot().byModelId(), fixture.catalog().currentCandidate().snapshot().byModelId());
            assertEquals(null, failures.get());
            assertEquals(null, fixture.catalog().scanProgress());
            armed.set(false);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
        }
    }

    @Test
    void progressCountsInputOutcomesAndWaitsForOwnerAcceptance() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("01-first.mxc"));
        Files.writeString(root.resolve("02-bad.mxc"), "broken input");
        Files.copy(firstFixture, root.resolve("03-duplicate.mxc"));
        var pack = Files.createDirectories(root.resolve("pack"));
        Files.copy(secondFixture, pack.resolve("04-second.mxc"));
        Files.writeString(pack.resolve("ysm-pack.json"), "{\"name\":\"Input pack\"}");
        var gate = new ProgressGate();
        var coordinator = new AtomicReference<Thread>();
        try (var fixture =
            open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, runnable -> {
                var thread = new Thread(runnable);
                coordinator.set(thread);
                return thread;
            }, (sequence, policy) -> java.util.concurrent.Executors.newSingleThreadExecutor(), () -> {
            }, gate))) {
            assertEquals(null, fixture.catalog().scanProgress());
            fixture.catalog().activate();
            var discovered = gate.next();
            assertEquals(CatalogScanProgress.Phase.DISCOVERING, discovered.phase());
            assertEquals(-1, discovered.total());
            assertEquals(0, discovered.processed());
            assertSame(discovered, fixture.catalog().scanProgress());
            try (var adapter = new com.elfmcys.ysm.model.catalog.client.ClientCatalogManager(fixture.catalog())) {
                adapter.beginRemote();
                assertSame(discovered, adapter.scanProgress());
                adapter.endRemote();
                assertSame(discovered, adapter.scanProgress());
            }
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());
            assertSame(discovered, fixture.catalog().scanProgress());
            gate.release();
            // Four model inputs (including broken and duplicate) plus one pack, independent of current/output size.
            for (var processed = 0; processed <= 5; processed++) {
                var value = gate.next();
                assertEquals(discovered.scanId(), value.scanId());
                assertEquals(5, value.total());
                assertEquals(processed, value.processed());
                assertEquals(
                    processed == 5 ? CatalogScanProgress.Phase.FINALIZING : CatalogScanProgress.Phase.PROCESSING,
                    value.phase());
                assertSame(value, fixture.catalog().scanProgress());
                gate.release();
            }
            coordinator.get().join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinator.get().isAlive());
            assertEquals(CatalogScanProgress.Phase.FINALIZING, fixture.catalog().scanProgress().phase());
            assertTrue(fixture.catalog().busy());
            fixture.catalog().tick();
            assertEquals(null, fixture.catalog().scanProgress());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().current().packs().size());
            assertEquals(2, fixture.catalog().current().report().errorCount());
            var next = fixture.catalog().reload();
            var nextDiscovery = gate.next();
            assertTrue(nextDiscovery.scanId() > discovered.scanId());
            assertEquals(CatalogScanProgress.Phase.DISCOVERING, nextDiscovery.phase());
            assertEquals(-1, nextDiscovery.total());
            assertEquals(0, nextDiscovery.processed());
            assertEquals(-1, discovered.total());
            gate.open();
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(next).status());
            assertEquals(null, fixture.catalog().scanProgress());
        } finally {
            gate.open();
        }
    }

    @Test
    void emptyScanNeverPublishesZeroDenominatorAndFailureWaitsForOwner() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        for (var fail : List.of(false, true)) {
            var gate = new ProgressGate();
            var coordinator = new AtomicReference<Thread>();
            try (var fixture =
                open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, runnable -> {
                    var thread = new Thread(runnable);
                    coordinator.set(thread);
                    return thread;
                }, (sequence, policy) -> java.util.concurrent.Executors.newSingleThreadExecutor(), () -> {
                }, gate))) {
                fixture.catalog().activate();
                assertEquals(CatalogScanProgress.Phase.DISCOVERING, gate.next().phase());
                gate.release();
                var finalizing = gate.next();
                assertEquals(CatalogScanProgress.Phase.FINALIZING, finalizing.phase());
                assertEquals(0, finalizing.processed());
                assertEquals(0, finalizing.total());
                if (fail) {
                    Files.writeString(root.resolve("late-input.mxc"), "changed final inventory");
                }
                gate.release();
                coordinator.get().join(TimeUnit.SECONDS.toMillis(10));
                assertFalse(coordinator.get().isAlive());
                assertEquals(CatalogScanProgress.Phase.FINALIZING, fixture.catalog().scanProgress().phase());
                fixture.catalog().tick();
                assertEquals(null, fixture.catalog().scanProgress());
                assertEquals(fail, fixture.catalog().current().report().errorCount() > 0);
            } finally {
                gate.open();
                Files.deleteIfExists(root.resolve("late-input.mxc"));
            }
        }
    }

    @Test
    void closeKeepsFinalizingUntilProducerStopsWithoutTick() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        try (var fixture = open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial,
            Thread::new, (sequence, policy) -> java.util.concurrent.Executors.newSingleThreadExecutor(), () -> {
            }, progress -> {
                if (progress.phase() != CatalogScanProgress.Phase.PROCESSING)
                    return;
                entered.countDown();
                var interrupted = false;
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                        cancelled.countDown();
                    }
                }
                if (interrupted)
                    Thread.currentThread().interrupt();
            }))) {
            fixture.catalog().activate();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var close = new Thread(fixture.catalog()::close);
            close.start();
            try {
                assertTrue(cancelled.await(10, TimeUnit.SECONDS));
                var progress = fixture.catalog().scanProgress();
                assertEquals(CatalogScanProgress.Phase.FINALIZING, progress.phase());
                assertEquals(0, progress.processed());
                assertEquals(1, progress.total());
                assertTrue(close.isAlive());
            } finally {
                release.countDown();
            }
            close.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(close.isAlive());
            assertEquals(null, fixture.catalog().scanProgress());
            assertEquals(ReloadStatus.FAILED, fixture.catalog().reload().join().status());
        } finally {
            release.countDown();
        }
    }

    @Test
    void interruptedScanWaitsForOwnerWithoutCountingUnfinishedInputs() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var gate = new ProgressGate();
        var coordinator = new AtomicReference<Thread>();
        try (var fixture =
            open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, runnable -> {
                var thread = new Thread(runnable);
                coordinator.set(thread);
                return thread;
            }, (sequence, policy) -> java.util.concurrent.Executors.newSingleThreadExecutor(), () -> {
            }, gate))) {
            fixture.catalog().activate();
            assertEquals(CatalogScanProgress.Phase.DISCOVERING, gate.next().phase());
            gate.release();
            assertEquals(new CatalogScanProgress(1, CatalogScanProgress.Phase.PROCESSING, 0, 2), gate.next());
            coordinator.get().interrupt();
            coordinator.get().join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinator.get().isAlive());
            assertEquals(new CatalogScanProgress(1, CatalogScanProgress.Phase.FINALIZING, 0, 2),
                fixture.catalog().scanProgress());
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());
            fixture.catalog().tick();
            assertEquals(null, fixture.catalog().scanProgress());
            gate.open();
            var next = fixture.catalog().reload();
            assertEquals(2, fixture.catalog().scanProgress().scanId());
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(next).status());
            assertEquals(null, fixture.catalog().scanProgress());
        } finally {
            gate.open();
        }
    }

    @Test
    void concurrentActivationAdmitsOnlyOnce() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var coordinators = new CopyOnWriteArrayList<Thread>();
        try (var fixture = open(root, runnable -> {
            var thread = new Thread(runnable);
            coordinators.add(thread);
            return thread;
        })) {
            var callers = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var start = new CountDownLatch(1);
                var requests = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (var index = 0; index < 8; index++) {
                    requests.add(callers.submit(() -> {
                        start.await();
                        fixture.catalog().activate();
                        return null;
                    }));
                }
                start.countDown();
                for (var request : requests) {
                    request.get(10, TimeUnit.SECONDS);
                }
            } finally {
                callers.shutdownNow();
            }
            assertEquals(1, coordinators.size());
            coordinators.get(0).join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinators.get(0).isAlive());
            assertTrue(fixture.catalog().busy());
            fixture.catalog().tick();
            assertFalse(fixture.catalog().busy());
            fixture.catalog().activate();
            assertEquals(1, coordinators.size());
        }
    }

    @Test
    void preparationFailureRetainsFirstPolicyButWorkerCreationFailureConsumesIt() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var registrations = new AtomicInteger();
        var policies = new CopyOnWriteArrayList<ThreadPolicy>();
        try (var fixture = open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial,
            Thread::new, (sequence, policy) -> {
                policies.add(policy);
                if (policies.size() == 1) {
                    throw new IllegalStateException("worker creation failed");
                }
                return java.util.concurrent.Executors.newFixedThreadPool(2);
            }, () -> {
                if (registrations.getAndIncrement() == 0) {
                    throw new IOException("registration failed");
                }
            }))) {
            fixture.activateAndFinish();
            assertTrue(policies.isEmpty());
            assertTrue(fixture.catalog().current().report().errors().stream()
                .anyMatch(error -> error.code().equals("CONVERTED_REGISTRATION")));
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(List.of(ThreadPolicy.BELOW_NORMAL, ThreadPolicy.LOW), policies);
        }
    }

    @Test
    void registrationFailureCanRetryWithTheInitialWorkerPolicy() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var registrations = new AtomicInteger();
        var policies = new CopyOnWriteArrayList<ThreadPolicy>();
        try (var fixture = open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial,
            Thread::new, (sequence, policy) -> {
                policies.add(policy);
                return java.util.concurrent.Executors.newFixedThreadPool(2);
            }, () -> {
                if (registrations.getAndIncrement() == 0) {
                    throw new IOException("registration failed");
                }
            }))) {
            fixture.activateAndFinish();
            assertTrue(fixture.catalog().current().report().errors().stream()
                .anyMatch(error -> error.code().equals("CONVERTED_REGISTRATION")));
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(List.of(ThreadPolicy.BELOW_NORMAL), policies);
        }
    }

    @Test
    void blockedExternalPruneDoesNotHoldTheCatalogMonitorOrCreateWorkersAndCloseInterruptsIt() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var cache = temp.resolve("gate-cache");
        var workerCount = new AtomicInteger();
        var coordinator = new AtomicReference<Thread>();
        try (var gate = new ConvertedGateProcess(cache); var registration = new ConvertedCacheCoordinator(cache);
            var fixture =
                open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, runnable -> {
                    var thread = new Thread(runnable);
                    coordinator.set(thread);
                    return thread;
                }, (sequence, policy) -> {
                    workerCount.incrementAndGet();
                    return java.util.concurrent.Executors.newFixedThreadPool(2);
                }, registration::register))) {
            fixture.catalog().activate();
            await(() -> java.util.Arrays.stream(coordinator.get().getStackTrace())
                .anyMatch(frame -> frame.getClassName().equals("com.elfmcys.ysm.model.storage.SharedCacheLocks")
                    && frame.getMethodName().equals("lock")));
            var tick = new Thread(fixture.catalog()::tick);
            tick.start();
            tick.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(tick.isAlive());
            assertEquals(0, workerCount.get());
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());

            fixture.catalog().close();

            assertFalse(coordinator.get().isAlive());
            assertFalse(registration.registered());
            assertEquals(0, workerCount.get());
        }
    }

    @Test
    void busyFileChangesAndRejectedRequestsDoNotQueueAnotherScan() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var coordinators = new CopyOnWriteArrayList<Thread>();
        try (var fixture = open(root, runnable -> {
            var thread = new Thread(runnable);
            coordinators.add(thread);
            return thread;
        })) {
            fixture.catalog().activate();
            coordinators.get(0).join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinators.get(0).isAlive());
            Files.copy(firstFixture, root.resolve("added-after-scan.mxc"));
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());
            fixture.catalog().tick();
            assertFalse(fixture.catalog().busy());
            for (var index = 0; index < 1_000; index++) {
                fixture.catalog().tick();
            }
            assertEquals(1, coordinators.size());
            assertEquals(1, fixture.catalog().current().byModelId().size());

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(2, coordinators.size());
            assertEquals(2, fixture.catalog().current().byModelId().size());
        }
    }

    @Test
    void inputChangeDuringPausedWorkersDoesNotScheduleRecovery() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            fixture.catalog().activate();
            workers.awaitFirstBatch();
            Files.copy(firstFixture, root.resolve("added-during-scan.mxc"));
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());
            workers.release.countDown();
            workers.awaitCoordinator();
            fixture.catalog().tick();
            assertFalse(fixture.catalog().busy());
            var terminal = fixture.catalog().currentCandidate();
            var threadCount = workers.threads.size();
            for (var index = 0; index < 1_000; index++) {
                fixture.catalog().tick();
            }
            assertFalse(fixture.catalog().busy());
            assertSame(terminal, fixture.catalog().currentCandidate());
            assertEquals(threadCount, workers.threads.size());
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void coordinatorStartAndCloseUseTheSameAdmissionLock() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var enteringStart = new CountDownLatch(1);
        var allowStart = new CountDownLatch(1);
        var coordinator = new AtomicReference<Thread>();
        ThreadFactory threads = body -> {
            var thread = new Thread(body) {
                @Override
                public synchronized void start() {
                    enteringStart.countDown();
                    try {
                        allowStart.await();
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                    super.start();
                }
            };
            coordinator.set(thread);
            return thread;
        };
        try (var fixture = open(root, threads)) {
            var activate = new Thread(fixture.catalog()::activate);
            var close = new Thread(fixture.catalog()::close);
            activate.start();
            assertTrue(enteringStart.await(10, TimeUnit.SECONDS));
            close.start();
            await(() -> close.getState() == Thread.State.BLOCKED);
            allowStart.countDown();
            activate.join(TimeUnit.SECONDS.toMillis(10));
            close.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(activate.isAlive());
            assertFalse(close.isAlive());
            assertFalse(coordinator.get().isAlive());
            assertTrue(fixture.catalog().current().byModelId().isEmpty());
            fixture.catalog().activate();
            assertEquals(ReloadStatus.FAILED, fixture.catalog().reload().join().status());
        } finally {
            allowStart.countDown();
        }
    }

    @Test
    void registrationGrantedDuringCloseStaysOwnedByTheProcessUntilItsFinalClose() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var coordinator = new AtomicReference<Thread>();
        try (var registration = new ConvertedCacheCoordinator(temp.resolve("registered-cache"));
            var fixture = open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, body -> {
                var thread = new Thread(body);
                coordinator.set(thread);
                return thread;
            }, (sequence, policy) -> {
                throw new AssertionError("Closed scan must not create workers");
            }, () -> {
                registration.register();
                entered.countDown();
                var interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }))) {
            fixture.catalog().activate();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var close = new Thread(fixture.catalog()::close);
            close.start();
            await(() -> !fixture.catalog().busy());
            assertTrue(registration.registered());
            release.countDown();
            close.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(close.isAlive());
            assertFalse(coordinator.get().isAlive());
            assertTrue(registration.registered());
            registration.close();
            assertFalse(registration.registered());
        } finally {
            release.countDown();
        }
    }

    @Test
    void scanningRequiresActivationAndBusyRequestsAreIgnored() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            assertEquals(ReloadStatus.FAILED, fixture.catalog().reload().join().status());

            fixture.catalog().activate();
            assertEquals(ReloadStatus.BUSY, fixture.catalog().reload().join().status());
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fixture.catalog().current().byModelId().size() != 2 && System.nanoTime() < deadline) {
                fixture.catalog().tick();
                Thread.onSpinWait();
            }
            assertEquals(2, fixture.catalog().current().byModelId().size());

            fixture.finish();
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().index().entries().size());
            Files.copy(secondFixture, root.resolve("second.mxc"));
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals(2, fixture.catalog().index().entries().size());
        }
    }

    @Test
    void successfulItemsPublishBeforeTheScanTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            fixture.catalog().activate();
            workers.awaitFirstBatch();
            fixture.awaitModelCount(2);

            assertTrue(fixture.catalog().busy());
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().index().entries().size());
            assertEquals(new CatalogScanProgress(1, CatalogScanProgress.Phase.PROCESSING, 1, 2),
                fixture.catalog().scanProgress());
            workers.release.countDown();
            fixture.finish();
            assertEquals(3, fixture.catalog().current().byModelId().size());
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void conflictingCandidatesPublishOnlyTheAcceptedCandidateInEveryIndex() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("a.mxc"));
        Files.copy(firstFixture, root.resolve("b.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(1, fixture.catalog().index().entries().size());
            var indexed = fixture.catalog().index().entries().get(0);
            var published = fixture.catalog().current().byModelId().get(indexed.modelId());
            assertEquals(indexed.location(), published.location());
            assertEquals(indexed.identity(), published.binding().content().representation().identity());
            assertEquals(1, fixture.catalog().current().report().errorCount());
        }
    }

    @Test
    void customCandidateCannotEnterTheIndexUnderTheIntrinsicDefaultIdentity() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(defaultFixture, root.resolve("replacement.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertTrue(fixture.catalog().index().entries().isEmpty());
            assertEquals(1, fixture.catalog().current().report().errorCount());
        }
    }

    @Test
    void idleFileCreationModificationAndDeletionNeedExplicitReload() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var coordinators = new AtomicInteger();
        try (var fixture = open(root, runnable -> {
            coordinators.incrementAndGet();
            return new Thread(runnable);
        })) {
            fixture.activateAndFinish();
            var initial = fixture.catalog().currentCandidate();
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            var file = Files.copy(firstFixture, root.resolve("first.mxc"));
            assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            Files.copy(secondFixture, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            Files.delete(file);
            assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            Files.copy(secondFixture, file);

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(2, coordinators.get());
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(identity(secondFixture).modelId(), fixture.catalog().index().entries().get(0).modelId());
        }
    }

    @Test
    void idleLinkRetargetingDoesNotAdmitOrPublishWork() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var firstTarget = Files.createDirectories(temp.resolve("first-target"));
        var secondTarget = Files.createDirectories(temp.resolve("second-target"));
        var link = root.resolve("group");
        var coordinators = new AtomicInteger();
        try (var fixture = open(root, runnable -> {
            coordinators.incrementAndGet();
            return new Thread(runnable);
        })) {
            fixture.activateAndFinish();
            var initial = fixture.catalog().currentCandidate();
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            try {
                createDirectoryLink(link, firstTarget);
                assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
                Files.delete(link);
                createDirectoryLink(link, secondTarget);
                assertIdleUnchanged(fixture.catalog(), initial, coordinators, observations);
            } finally {
                Files.deleteIfExists(link);
            }
            Files.copy(firstFixture, root.resolve("first.mxc"));
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(2, coordinators.get());
            assertEquals(2, fixture.catalog().current().byModelId().size());
        }
    }

    private static void assertIdleUnchanged(ReloadableModelCatalog catalog, CatalogCandidate expected,
        AtomicInteger coordinators, AtomicInteger observations) {
        for (var index = 0; index < 1_000; index++) {
            catalog.tick();
        }
        assertFalse(catalog.busy());
        assertEquals(1, coordinators.get());
        assertEquals(1, observations.get());
        assertSame(expected, catalog.currentCandidate());
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX,
        org.junit.jupiter.api.condition.OS.MAC})
    void unixInvalidEntrySegmentsCannotAliasHealthyModelsOrPacks() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var healthy = Files.createDirectories(root.resolve("a"));
        Files.copy(firstFixture, root.resolve("a\\b.mxc"));
        Files.copy(secondFixture, healthy.resolve("b.mxc"));
        var invalidPack = Files.createDirectories(root.resolve("a\\b"));
        var healthyPack = Files.createDirectories(healthy.resolve("b"));
        Files.writeString(invalidPack.resolve("ysm-pack.json"), "{\"name\":\"Invalid pack\"}");
        Files.writeString(healthyPack.resolve("ysm-pack.json"), "{\"name\":\"Healthy pack\"}");
        var originalInvalid = Files.readAllBytes(root.resolve("a\\b.mxc"));
        var originalHealthy = Files.readAllBytes(healthy.resolve("b.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var snapshot = fixture.catalog().current();
            assertEquals(2, snapshot.byModelId().size());
            assertFalse(snapshot.byModelId().containsKey(identity(firstFixture).modelId()));
            var published = snapshot.byModelId().get(identity(secondFixture).modelId());
            assertEquals("a/b.mxc", published.location().path().value());
            assertEquals(healthy.resolve("b.mxc"), ((ManagedContainer)published.binding().content()).file());
            assertEquals(1, fixture.catalog().index().entries().size());
            assertEquals(identity(secondFixture).modelId(), fixture.catalog().index().entries().get(0).modelId());
            assertEquals(List.of("Healthy pack"), snapshot.packs().stream().map(pack -> pack.name()).toList());
            assertTrue(snapshot.packs().get(0).hierarchy().endsWith("/a/b"));
            var invalid = snapshot.report().errors().stream()
                .filter(error -> error.code().equals("INVALID_SOURCE_LOCATION")).toList();
            assertTrue(invalid.size() >= 2, snapshot.report().errors().toString());
            for (var error : invalid) {
                assertFalse(error.source().contains("a/b"), error.source());
                assertFalse(error.source().contains(root.toString()), error.source());
                assertFalse(error.message().contains(root.toString()), error.message());
            }
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalInvalid,
            Files.readAllBytes(root.resolve("a\\b.mxc")));
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalHealthy,
            Files.readAllBytes(healthy.resolve("b.mxc")));
    }

    @Test
    void completeInventoryRetainsObservedPacksAtScanTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.writeString(root.resolve("ysm-pack.json"), "{\"name\":\"Observed pack\",\"description\":\"kept\"}");
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            assertEquals(1, fixture.catalog().current().packs().size());
            assertEquals("Observed pack", fixture.catalog().current().packs().get(0).name());
        }
    }

    @Test
    void failedRootInventoryKeepsPublishedAuthority() throws Exception {
        var root = Files.writeString(temp.resolve("models"), "not a directory");
        try (var fixture = open(root)) {
            fixture.activateAndFinish();

            assertEquals(1, fixture.catalog().current().byModelId().size());
            assertTrue(fixture.catalog().current().report().errorCount() > 0);
            assertFalse(fixture.catalog().busy());
        }
    }

    @Test
    void observerFailureCannotRollbackCommitOrBlockAnotherObserver() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            fixture.catalog().subscribe(ignored -> {
                throw new IllegalStateException("observer failed");
            });
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            Files.copy(firstFixture, root.resolve("first.mxc"));

            var reload = fixture.catalog().reload();
            fixture.finish(reload);

            assertEquals(ReloadStatus.COMMITTED, reload.join().status());
            assertEquals(2, fixture.catalog().current().byModelId().size());
            assertEquals(2, observations.get());
        }
    }

    @Test
    void unchangedReloadKeepsTheCompleteCandidateAndContentGraph() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var previous = fixture.catalog().currentCandidate();
            var old = previous.snapshot().byModelId().values().stream()
                .filter(record -> record.location().rootKind() == CatalogRootKind.CUSTOM).findFirst().orElseThrow();
            var notifications = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> notifications.incrementAndGet());

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());

            assertSame(previous, fixture.catalog().currentCandidate());
            var current = fixture.catalog().current().byModelId().get(old.binding().modelId());
            assertSame(old, current);
            assertSame(old.binding().content().representation(), current.binding().content().representation());
            assertSame(old.binding().content().modelFile().getMetadata(),
                current.binding().content().modelFile().getMetadata());
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void movingTheSourceReusesMetadataButUpdatesItsBinding() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var file = Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var previous = fixture.catalog().current().byModelId().values().stream()
                .filter(record -> record.location().rootKind() == CatalogRootKind.CUSTOM).findFirst().orElseThrow();
            var moved = Files.move(file, root.resolve("moved.mxc"));

            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());

            var current = fixture.catalog().current().byModelId().get(previous.binding().modelId());
            assertNotSame(previous, current);
            assertEquals("moved.mxc", current.location().path().value());
            assertEquals(moved, ((ManagedContainer)current.binding().content()).file());
            assertSame(previous.binding().content().modelFile(), current.binding().content().modelFile());
            assertSame(previous.binding().content().modelFile().getFileView().getAssetView(),
                current.binding().content().modelFile().getFileView().getAssetView());
        }
    }

    @Test
    void pausedMainThreadReceivesOnlyTheLatestCompleteTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            var initial = fixture.catalog().current();
            var transitions = new CopyOnWriteArrayList<com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition>();
            fixture.catalog().subscribe(transitions::add);
            fixture.catalog().activate();
            workers.awaitFirstBatch();
            assertSame(initial, fixture.catalog().current());
            workers.release.countDown();
            workers.awaitCoordinator();
            assertTrue(fixture.catalog().busy());

            fixture.catalog().tick();

            assertFalse(fixture.catalog().busy());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals(2, transitions.size());
            assertSame(initial, transitions.get(1).previous());
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void laterBatchWaitsOneSecondAndReportsTheActuallyInstalledPrevious() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("first.mxc"));
        Files.copy(secondFixture, root.resolve("second.mxc"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            fixture.catalog().activate();
            workers.awaitFirstBatch();
            fixture.awaitModelCount(2);
            var first = fixture.catalog().current();
            assertEquals(2, first.byModelId().size());
            assertTrue(fixture.catalog().busy());
            workers.release.countDown();

            fixture.finish();

            var last = fixture.catalog().current();
            assertEquals(3, last.byModelId().size());
            assertTrue(java.time.Duration.between(first.report().completedAt(), last.report().completedAt())
                .compareTo(java.time.Duration.ofSeconds(1)) >= 0);
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void indexPersistenceFailureRetainsOldMissingAndNewSuccessfulItems() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var first = Files.copy(firstFixture, root.resolve("first.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            Files.delete(first);
            Files.copy(secondFixture, root.resolve("second.mxc"));
            var cacheRoot = temp.resolve("cache");
            // The production index commit must fail after valid sources have completed.
            Files.move(cacheRoot, temp.resolve("saved-cache"));
            Files.writeString(cacheRoot, "index root is no longer a directory");

            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());

            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertFalse(fixture.catalog().busy());
            assertTrue(fixture.catalog().current().report().errors().stream()
                .anyMatch(error -> error.code().equals("CATALOG_FINALIZATION_FAILED")));
        }
    }

    @Test
    void indexFailureRemainsFinalizingUntilOwnerConsumesTheFailedTerminal() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var first = Files.copy(firstFixture, root.resolve("first.mxc"));
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var coordinator = new AtomicReference<Thread>();
        try (var fixture =
            open(root, (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, runnable -> {
                var thread = new Thread(runnable);
                coordinator.set(thread);
                return thread;
            }, (sequence, policy) -> java.util.concurrent.Executors.newSingleThreadExecutor(), () -> {
            }, progress -> {
                if (!armed.get() || progress.phase() != CatalogScanProgress.Phase.FINALIZING)
                    return;
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }))) {
            fixture.activateAndFinish();
            Files.delete(first);
            Files.copy(secondFixture, root.resolve("second.mxc"));
            armed.set(true);
            var completion = fixture.catalog().reload();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(new CatalogScanProgress(2, CatalogScanProgress.Phase.FINALIZING, 1, 1),
                fixture.catalog().scanProgress());
            var cacheRoot = temp.resolve("cache");
            Files.move(cacheRoot, temp.resolve("saved-cache"));
            Files.writeString(cacheRoot, "index root is no longer a directory");
            release.countDown();
            coordinator.get().join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinator.get().isAlive());
            assertFalse(completion.isDone());
            assertEquals(CatalogScanProgress.Phase.FINALIZING, fixture.catalog().scanProgress().phase());
            fixture.catalog().tick();
            assertEquals(ReloadStatus.FAILED, completion.join().status());
            assertEquals(null, fixture.catalog().scanProgress());
            assertEquals(3, fixture.catalog().current().byModelId().size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void threadStartFailureEndsTheRequestAndAllowsAnotherReload() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var attempts = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("thread creation failed");
            }
            return new Thread(runnable);
        };
        try (var fixture = open(root, threads)) {
            fixture.catalog().activate();
            assertFalse(fixture.catalog().busy());
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
        }
    }

    @Test
    void completionCallbackCanImmediatelyStartTheNextScan() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var first = fixture.catalog().reload();
            var next = new AtomicReference<CompletableFuture<ReloadResult>>();
            var callback = first.thenRun(() -> {
                next.set(fixture.catalog().reload());
                assertEquals(3, fixture.catalog().scanProgress().scanId());
            });
            fixture.finish(first);
            callback.join();
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(next.get()).status());
        }
    }

    @Test
    void closeRejectsLatePublicationAndJoinsBothExecutionCapacities() throws Exception {
        var root = Files.createDirectories(temp.resolve("models"));
        var workers = new ControlledThreads();
        try (var fixture = open(root, workers)) {
            fixture.activateAndFinish();
            Files.copy(firstFixture, root.resolve("first.mxc"));
            Files.copy(secondFixture, root.resolve("second.mxc"));
            var observations = new AtomicInteger();
            fixture.catalog().subscribe(ignored -> observations.incrementAndGet());
            var completion = fixture.catalog().reload();
            workers.awaitFirstBatch();
            fixture.catalog().close();
            fixture.catalog().close();
            assertEquals(ReloadStatus.FAILED, completion.join().status());
            assertTrue(workers.threads.stream().noneMatch(Thread::isAlive));
            fixture.catalog().tick();
            assertTrue(fixture.catalog().current().byModelId().isEmpty());
            assertEquals(1, observations.get());
            assertEquals(ReloadStatus.FAILED, fixture.catalog().reload().join().status());
        } finally {
            workers.release.countDown();
        }
    }

    @Test
    void bindingViewKeepsExactOldContentUntilDeterministicCleanupEvent() {
        assertFalse(AutoCloseable.class.isAssignableFrom(ContentBinding.class));
        assertFalse(AutoCloseable.class.isAssignableFrom(ModelContent.class));

        var cleaned = new AtomicInteger();
        TestContent first = new TestContent(hash(3), cleaned);
        var second = new TestContent(hash(3), new AtomicInteger());
        CatalogSnapshot firstSnapshot = snapshot(first, "same");
        var current = new AtomicReference<>(candidate(firstSnapshot));
        ModelContent resourceReference = current.get().snapshot().binding(first.modelId()).orElseThrow().content();

        assertSame(first, resourceReference);
        current.set(candidate(snapshot(second, "same")));
        var currentContent = current.get().snapshot().binding(second.modelId()).orElseThrow().content();
        assertSame(second, currentContent);
        assertNotSame(resourceReference, currentContent);
        assertEquals(first.modelId(), resourceReference.modelId());
        assertEquals(0, cleaned.get());

        var cleanupEvent = first.cleanupEvent();
        resourceReference = null;
        firstSnapshot = null;
        first = null;
        cleanupEvent.clean();

        assertEquals(1, cleaned.get());
    }

    private CatalogFixture open(Path root) throws Exception {
        return open(root, (ThreadFactory)Thread::new);
    }

    private CatalogFixture open(Path root, ThreadFactory threads) throws Exception {
        return open(root,
            (reconciler, initial) -> threads instanceof ControlledThreads controlled
                ? new ReloadableModelCatalog(reconciler, initial, threads, controlled::newExecutor)
                : new ReloadableModelCatalog(reconciler, initial, threads));
    }

    private CatalogFixture open(Path root,
        BiFunction<CatalogReconciler, CatalogCandidate, ReloadableModelCatalog> factory) throws Exception {
        return open(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false)), factory);
    }

    private CatalogFixture open(List<ModelCatalogSource> roots,
        BiFunction<CatalogReconciler, CatalogCandidate, ReloadableModelCatalog> factory) throws Exception {
        var cacheRoot = temp.resolve("cache");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(cacheRoot, "catalog-owner-test"), new ConvertedObjectStore(cacheRoot));
        var intrinsicDefault =
            openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        var reconciler = new CatalogReconciler(roots, resolver, Set.of(intrinsicDefault.modelId()), intrinsicDefault,
            CatalogIndexSnapshot.empty());
        var initial = reconciler.materializeBuiltins();
        var catalog = factory.apply(reconciler, initial);
        return new CatalogFixture(catalog);
    }

    @Test
    void linkedRootsPublishCompleteContainersForBothKindsAndCreationPolicies() throws Exception {
        var expected = identity(firstFixture).modelId();
        var original = Files.readAllBytes(firstFixture);
        for (var kind : List.of(CatalogRootKind.CUSTOM, CatalogRootKind.AUTH)) {
            for (var create : new boolean[] {false, true}) {
                var suffix = kind + "-" + create;
                var target = Files.createDirectories(temp.resolve("outside-" + suffix));
                Files.createSymbolicLink(target.resolve("alias.mxc"), firstFixture);
                var root = temp.resolve("root-" + suffix);
                createDirectoryLink(root, target);
                try (var fixture = open(List.of(new ModelCatalogSource(kind, root, create)),
                    (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new))) {
                    fixture.activateAndFinish();
                    assertEquals(0, fixture.catalog().current().report().errorCount());
                    assertEquals(new CatalogLocation(kind, new ModelPath("alias.mxc")),
                        fixture.catalog().current().byModelId().get(expected).location());
                    assertEquals("alias.mxc", fixture.catalog().index().entries().get(0).location().path().value());
                    assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
                    assertEquals(firstFixture, fixture.catalog().index().entries().get(0).backingFile());
                }
                assertEquals(target.toRealPath(), root.toRealPath());
                assertTrue(Files.isSymbolicLink(target.resolve("alias.mxc")));
                Files.delete(root);
            }
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(firstFixture));
    }

    @Test
    void linkedGroupRawEntryPackAndArchivePublishTheSameLogicalHierarchy() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var physical = Files.createDirectories(temp.resolve("auth-storage/deep"));
        var raw = copyRawFixture(temp.resolve("work/physical-name"));
        Files.createSymbolicLink(physical.resolve("hero"), raw);
        var manifest = Files.writeString(temp.resolve("pack-manifest.json"), "{\"name\":\"Linked pack\"}");
        var cover = Files.write(temp.resolve("pack-cover.data"), Files.readAllBytes(raw.resolve("textures/gsl.png")));
        Files.createSymbolicLink(physical.resolve("ysm-pack.json"), manifest);
        Files.createSymbolicLink(physical.resolve("ysm-pack.png"), cover);
        Files.createSymbolicLink(root.resolve("intermediate"), Path.of("../auth-storage"));
        Files.createSymbolicLink(root.resolve("team"), Path.of("intermediate"));
        var archive = temp.resolve("physical-archive.data");
        zipRaw(copyRawFixture(temp.resolve("zip-source")), archive);
        // The duplicate raw identity still reaches the candidate boundary in the separate archive scan.
        var archiveRoot = Files.createDirectory(temp.resolve("archive-root"));
        Files.createSymbolicLink(archiveRoot.resolve("different-name.zip"), archive);
        var expected = identity(firstFixture).modelId();
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var record = fixture.catalog().current().byModelId().get(expected);
            assertTrue(record.location().path().value().equals("team/deep/hero")
                || record.location().path().value().equals("intermediate/deep/hero"));
            assertEquals(CatalogRootKind.CUSTOM, record.location().rootKind());
            assertEquals(1, fixture.catalog().index().entries().size());
            assertEquals(Set.of("local:custom/team/deep", "local:custom/intermediate/deep"),
                fixture.catalog().current().packs().stream()
                    .map(com.elfmcys.ysm.model.domain.ModelPackDescriptor::hierarchy)
                    .collect(java.util.stream.Collectors.toSet()));
            assertTrue(fixture.catalog().current().packs().stream().allMatch(
                pack -> pack.coverHash().equals(com.elfmcys.ysm.model.storage.ModelHashing.blake3(readBytes(cover)))));
            var index = new ConvertedSourceIndexStore(temp.resolve("cache"), "catalog-owner-test").read();
            assertEquals(Set.of("custom/" + record.location().path().value()), index.keySet());
            assertEquals(expected, index.values().iterator().next().modelId());
        }
        try (var fixture = open(archiveRoot)) {
            fixture.activateAndFinish();
            assertEquals(0, fixture.catalog().current().report().errorCount(),
                fixture.catalog().current().report().errors().toString());
            assertEquals(new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("different-name.zip")),
                fixture.catalog().current().byModelId().get(expected).location());
        }
    }

    @Test
    void incompleteBranchPublishesHealthyItemsProtectsOldItemsAndLaterConfirmsDeletion() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var old = Files.copy(firstFixture, root.resolve("old.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            Files.delete(old);
            Files.createSymbolicLink(root.resolve("broken"), temp.resolve("absent"));
            Files.copy(secondFixture, root.resolve("healthy.mxc"));
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals("healthy.mxc", fixture.catalog().current().byModelId().get(identity(secondFixture).modelId())
                .location().path().value());
            assertEquals("old.mxc", fixture.catalog().current().byModelId().get(identity(firstFixture).modelId())
                .location().path().value());
            assertTrue(fixture.catalog().current().report().errors().stream()
                .anyMatch(error -> error.source().contains("broken")));
            Files.delete(root.resolve("broken"));
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertFalse(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
            assertEquals(1, fixture.catalog().index().entries().size());
        }
    }

    @Test
    void rawInternalLinkRetainsOldConvertedEntryWhileHealthySiblingUpdates() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var raw = copyRawFixture(root.resolve("raw"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var index = new ConvertedSourceIndexStore(temp.resolve("cache"), "catalog-owner-test");
            var oldIndex = index.read();
            var outside = Files.writeString(temp.resolve("outside.txt"), "outside");
            Files.createSymbolicLink(raw.resolve("unused.txt"), outside);
            Files.copy(secondFixture, root.resolve("healthy.mxc"));
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals(oldIndex, index.read());
            Files.delete(raw.resolve("unused.txt"));
            Files.writeString(raw.resolve("ysm.json"), "invalid input");
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals(oldIndex, index.read());
        }
    }

    @Test
    void sameRelativePathsPublishIndependentlyAndIncompleteRootRetainsMissingEntries() throws Exception {
        var auth = Files.createDirectory(temp.resolve("auth"));
        var custom = Files.createDirectory(temp.resolve("custom"));
        Files.copy(firstFixture, auth.resolve("same.mxc"));
        Files.copy(secondFixture, custom.resolve("same.mxc"));
        var release = new CountDownLatch(1);
        var customFinished = new CountDownLatch(1);
        var roots = List.of(new ModelCatalogSource(CatalogRootKind.AUTH, auth, false),
            new ModelCatalogSource(CatalogRootKind.CUSTOM, custom, false));
        try (var fixture = open(roots,
            (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new,
                (sequence, policy) -> new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>()) {
                    @Override
                    public void execute(Runnable runnable) {
                        var first = getTaskCount() == 0;
                        super.execute(() -> {
                            if (sequence == 1 && first) {
                                try {
                                    release.await();
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            runnable.run();
                        });
                    }

                    @Override
                    protected void afterExecute(Runnable runnable, Throwable failure) {
                        customFinished.countDown();
                    }
                }))) {
            fixture.catalog().activate();
            assertTrue(customFinished.await(10, TimeUnit.SECONDS));
            await(() -> {
                fixture.catalog().tick();
                return fixture.catalog().current().byModelId().size() == 2;
            });
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            release.countDown();
            fixture.finish();
            assertEquals(CatalogRootKind.AUTH,
                fixture.catalog().current().byModelId().get(identity(firstFixture).modelId()).location().rootKind());
            Files.delete(auth.resolve("same.mxc"));
            Files.createSymbolicLink(auth.resolve("broken"), temp.resolve("missing"));
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void retargetedEntryFailsItsOwnObservationAndNextExplicitReloadUsesTheNewTarget() throws Exception {
        for (var mutation : List.of("retarget", "delete", "replace")) {
            verifyAcceptedObservationMutation(mutation);
        }
    }

    private void verifyAcceptedObservationMutation(String mutation) throws Exception {
        var scenario = Files.createDirectory(temp.resolve(mutation));
        var root = Files.createDirectory(scenario.resolve("models"));
        var physical = Files.copy(firstFixture, scenario.resolve("physical.mxc"));
        var link = Files.createSymbolicLink(root.resolve("alias.mxc"), physical);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = open(root,
            (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new,
                (sequence, policy) -> new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>()) {
                    @Override
                    protected void beforeExecute(Thread thread, Runnable runnable) {
                        if (sequence == 2) {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }))) {
            fixture.activateAndFinish();
            var reload = fixture.catalog().reload();
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            switch (mutation) {
                case "retarget" -> {
                    Files.delete(link);
                    Files.createSymbolicLink(link, secondFixture);
                }
                case "delete" -> Files.delete(physical);
                case "replace" ->
                    Files.copy(secondFixture, physical, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                default -> throw new IllegalArgumentException(mutation);
            }
            release.countDown();
            assertEquals(ReloadStatus.FAILED, fixture.finish(reload).status(), mutation);
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
            assertFalse(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            if (mutation.equals("delete"))
                Files.copy(secondFixture, physical);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status(), mutation);
            assertFalse(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            System.out.println("ACCEPTED_OBSERVATION_MUTATION " + mutation + " rejected-then-recovered");
        } finally {
            release.countDown();
        }
    }

    @Test
    void firstCompleteAdmissionReplacesOldCurrentAndLaterAuthOrCustomCannotStealIt() throws Exception {
        for (var authWins : new boolean[] {false, true}) {
            var scenario = Files.createDirectory(temp.resolve(authWins ? "auth-first" : "custom-first"));
            var auth = Files.createDirectory(scenario.resolve("auth"));
            var custom = Files.createDirectory(scenario.resolve("custom"));
            var original = Files.copy(firstFixture, custom.resolve("same.mxc"));
            var entered = new CountDownLatch(2);
            var gates = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1)};
            var roots = List.of(
                new ModelCatalogSource(com.elfmcys.ysm.model.catalog.source.CatalogRoot.GLOBAL_AUTH, auth, false),
                new ModelCatalogSource(CatalogRootKind.CUSTOM, custom, false));
            try (var fixture = open(roots,
                (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new,
                    (sequence, policy) -> new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>()) {
                        private final AtomicInteger inputs = new AtomicInteger();

                        @Override
                        public void execute(Runnable runnable) {
                            var index = inputs.getAndIncrement();
                            super.execute(() -> {
                                if (sequence == 2) {
                                    entered.countDown();
                                    try {
                                        gates[index].await();
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                runnable.run();
                            });
                        }
                    }))) {
                fixture.activateAndFinish();
                var id = identity(firstFixture).modelId();
                var old = fixture.catalog().current().byModelId().get(id);
                var source = (ManagedContainer)old.binding().content();
                try (var preview = TestPreviews.blank()) {
                    ModelExporter.export(source, preview, auth.resolve("same.mxc"), "auth-second-round");
                    ModelExporter.export(source, preview, original, "custom-second-round");
                }
                var expectedPath = authWins ? "global:auth/same.mxc" : "local:custom/same.mxc";
                var expectedIdentity = identity(authWins ? auth.resolve("same.mxc") : original);
                var reload = fixture.catalog().reload();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                fixture.catalog().tick();
                assertSame(old, fixture.catalog().current().byModelId().get(id));
                gates[authWins ? 0 : 1].countDown();
                await(() -> {
                    fixture.catalog().tick();
                    return fixture.catalog().current().byModelId().get(id).binding().content().representation()
                        .identity().equals(expectedIdentity);
                });
                assertEquals(expectedPath, fixture.catalog().current().byModelId().get(id).entry().path().value());
                gates[authWins ? 1 : 0].countDown();
                assertEquals(ReloadStatus.COMMITTED, fixture.finish(reload).status());
                assertEquals(expectedPath, fixture.catalog().current().byModelId().get(id).entry().path().value());
                assertEquals(authWins ? CatalogAccess.AUTHORIZED : CatalogAccess.PUBLIC,
                    fixture.catalog().current().byModelId().get(id).entry().access());
                assertEquals(1,
                    fixture.catalog().current().report().errors().stream()
                        .filter(
                            error -> error.category() == com.elfmcys.ysm.model.domain.ModelScanError.Category.CONFLICT)
                        .count());
            } finally {
                gates[0].countDown();
                gates[1].countDown();
            }
        }
    }

    @Test
    void globalAndInstanceRootsKeepFileExtensionsPacksAndActualStorageBindings() throws Exception {
        var shared = temp.resolve("PHYSICAL_SECRET_shared");
        var paths = com.elfmcys.ysm.AssetPaths.discover(temp.resolve("game-one"), temp.resolve("config"),
            temp.resolve("builtin"), Map.of("YSM_HOME", shared.toString()),
            com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        var roots = com.elfmcys.ysm.model.catalog.source.ModelCatalogSources.reloadableSources(paths);
        assertEquals(4, roots.size());
        for (int i = 0; i < roots.size(); i++) {
            var root = roots.get(i);
            Files.createDirectories(root.path().resolve("team"));
            Files.writeString(root.path().resolve("team/ysm-pack.json"), "{\"name\":\"Pack " + i + "\"}");
            var content = com.elfmcys.ysm.model.service.ExportTestFixture.create(firstFixture,
                root.path().resolve("team/same.mxc"), "team/same.mxc", 41 + i,
                com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.RAW, root.rootKind());
            content.representation().close();
        }
        var globalCustom = paths.global().orElseThrow().customRoot();
        var raw = copyRawFixture(globalCustom.resolve("same"));
        var manifest = raw.resolve("ysm.json");
        Files.writeString(manifest, Files.readString(manifest).replace("height_scale\": 1", "height_scale\": 1.25"));
        Files.copy(firstFixture, globalCustom.resolve("same.mxc"));
        Files.copy(secondFixture, globalCustom.resolve("same.ysm"));
        var archiveRaw = copyRawFixture(temp.resolve("archive-raw"));
        var archiveManifest = archiveRaw.resolve("ysm.json");
        Files.writeString(archiveManifest,
            Files.readString(archiveManifest).replace("height_scale\": 1", "height_scale\": 1.5"));
        zipRaw(archiveRaw, globalCustom.resolve("same.zip"));
        try (var storage = com.elfmcys.ysm.model.storage.ModelStorageInfra.open(paths, "global-integration")) {
            var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), storage);
            var intrinsic =
                openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            var reconciler = new CatalogReconciler(roots, resolver, Set.of(intrinsic.modelId()), intrinsic,
                CatalogIndexSnapshot.empty());
            try (var fixture =
                new CatalogFixture(new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins(), Thread::new,
                    (sequence, policy) -> java.util.concurrent.Executors.newFixedThreadPool(4),
                    storage::registerConverted))) {
                fixture.activateAndFinish();
                assertEquals(0, fixture.catalog().current().report().errorCount());
                var expected = Set.of("default", "global:custom/team/same.mxc", "global:auth/team/same.mxc",
                    "local:custom/team/same.mxc", "local:auth/team/same.mxc", "global:custom/same",
                    "global:custom/same.mxc", "global:custom/same.ysm", "global:custom/same.zip");
                assertEquals(expected, fixture.catalog().current().byPath().keySet().stream().map(HierarchyPath::value)
                    .collect(java.util.stream.Collectors.toSet()));
                assertEquals(Set.of("global:custom/team", "global:auth/team", "local:custom/team", "local:auth/team"),
                    fixture.catalog().current().packs().stream()
                        .map(com.elfmcys.ysm.model.domain.ModelPackDescriptor::hierarchy)
                        .collect(java.util.stream.Collectors.toSet()));
                Map.of("global:custom/team/same.mxc", "29", "global:auth/team/same.mxc", "2a",
                    "local:custom/team/same.mxc", "2b", "local:auth/team/same.mxc", "2c")
                    .forEach((address, marker) -> assertEquals(Hash256.parse(marker + "00".repeat(31)),
                        fixture.catalog().current().byPath().get(new HierarchyPath(address))));
                assertEquals(
                    Map.of("global:custom/team", "Pack 0", "global:auth/team", "Pack 1", "local:custom/team", "Pack 2",
                        "local:auth/team", "Pack 3"),
                    fixture.catalog().current().packs().stream()
                        .collect(java.util.stream.Collectors.toMap(
                            com.elfmcys.ysm.model.domain.ModelPackDescriptor::hierarchy,
                            com.elfmcys.ysm.model.domain.ModelPackDescriptor::name)));
                for (var record : fixture.catalog().current().byModelId().values()) {
                    if (record.location().rootKind() == CatalogRootKind.BUILTIN)
                        continue;
                    var binding = record.binding().content().storageBinding();
                    assertEquals(record.location(), binding.localSource().orElseThrow().key().root()
                        .location(binding.localSource().orElseThrow().key().relativePath()));
                    assertSame(record.location().value().startsWith("global:") ? storage.remote() : storage.local(),
                        binding.region());
                }
                var server = new com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog(fixture.catalog().current());
                assertEquals(com.elfmcys.ysm.model.catalog.source.CatalogAddressResolver.Status.AMBIGUOUS,
                    server.resolvePath("team/same.mxc").status());
                assertTrue(server.findPath("same").isPresent());
                assertTrue(server.findPath("same.mxc").isPresent());
                assertTrue(server.findPath("same.ysm").isPresent());
                assertTrue(server.findPath("same.zip").isPresent());
                var rawId = server.findPath("same").orElseThrow().modelId();
                var object =
                    ((ManagedContainer)fixture.catalog().current().binding(rawId).orElseThrow().content()).file();
                var before = sha256(object);
                var modified = Files.getLastModifiedTime(object);
                var secondPaths = com.elfmcys.ysm.AssetPaths.discover(temp.resolve("game-two"),
                    temp.resolve("config-two"), temp.resolve("builtin"), Map.of("YSM_HOME", shared.toString()),
                    com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
                try (var secondStorage =
                    com.elfmcys.ysm.model.storage.ModelStorageInfra.open(secondPaths, "global-integration")) {
                    var secondResolver =
                        new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()), secondStorage);
                    var secondRoots =
                        com.elfmcys.ysm.model.catalog.source.ModelCatalogSources.reloadableSources(secondPaths);
                    var secondIntrinsic = openIndexed(defaultFixture,
                        new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
                    var secondReconciler = new CatalogReconciler(secondRoots, secondResolver,
                        Set.of(secondIntrinsic.modelId()), secondIntrinsic, CatalogIndexSnapshot.empty());
                    try (var second = new CatalogFixture(
                        new ReloadableModelCatalog(secondReconciler, secondReconciler.materializeBuiltins(),
                            Thread::new, (sequence, policy) -> java.util.concurrent.Executors.newFixedThreadPool(4),
                            secondStorage::registerConverted))) {
                        second.activateAndFinish();
                        var adopted = second.catalog().current().byModelId().get(rawId);
                        assertEquals("global:custom/same", adopted.entry().path().value());
                        assertEquals(object, ((ManagedContainer)adopted.binding().content()).file());
                        assertEquals(before, sha256(object));
                        assertEquals(modified, Files.getLastModifiedTime(object));
                        assertTrue(second.catalog().current().byPath().keySet().stream()
                            .noneMatch(path -> path.value().startsWith("local:")));
                    }
                }
            }
        }
    }

    private static Path copyRawFixture(Path target) throws Exception {
        var source = Path
            .of(Objects.requireNonNull(
                ReloadableModelCatalogTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI())
            .getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    @Test
    void userSourcesCannotOccupyReservedBuiltinIdentityButMayReuseItsRelativeName() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("forbidden-identity.mxc"));
        Files.copy(secondFixture, root.resolve("reserved.mxc"));
        var builtinLocation = new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("reserved.mxc"));
        var builtinIndex = new CatalogIndexSnapshot(
            List.of(new CatalogIndexEntry(identity(firstFixture), builtinLocation, firstFixture)), List.of(),
            ModelScanReport.empty());
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(temp.resolve("cache"), "test"),
            new ConvertedObjectStore(temp.resolve("cache")));
        var intrinsic =
            openIndexed(defaultFixture, new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
        var reconciler = new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, root, false)),
            resolver, Set.of(intrinsic.modelId(), identity(firstFixture).modelId()), intrinsic, builtinIndex);
        try (var fixture =
            new CatalogFixture(new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins(), Thread::new))) {
            fixture.activateAndFinish();
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertEquals(builtinLocation,
                fixture.catalog().current().byModelId().get(identity(firstFixture).modelId()).location());
            assertTrue(fixture.catalog().current().byModelId().containsKey(identity(secondFixture).modelId()));
            assertEquals(1, fixture.catalog().current().report().errorCount());
            assertEquals(
                Set.of(builtinLocation, new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("reserved.mxc"))),
                Set.copyOf(fixture.catalog().index().entries().stream().map(CatalogIndexEntry::location).toList()));
        }
    }

    @Test
    void deniedBranchKeepsOldContentWhileHealthySiblingPublishes() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var branch = Files.createDirectory(root.resolve("denied"));
        Files.copy(firstFixture, branch.resolve("old.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            try (var denied = denyDirectoryRead(branch)) {
                org.junit.jupiter.api.Assertions.assertThrows(java.nio.file.AccessDeniedException.class, () -> {
                    try (var ignored = Files.newDirectoryStream(branch)) {
                    }
                });
                Files.copy(secondFixture, root.resolve("healthy.mxc"));
                assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
                assertEquals(3, fixture.catalog().current().byModelId().size());
                assertTrue(fixture.catalog().current().report().errors().stream()
                    .anyMatch(error -> error.source().contains("denied")));
            }
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
        }
    }

    private static AutoCloseable denyDirectoryRead(Path directory) throws Exception {
        var acl = Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var original = acl.getAcl();
            var denied = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.DENY).setPrincipal(Files.getOwner(directory))
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build();
            var entries = new java.util.ArrayList<>(original);
            entries.add(0, denied);
            acl.setAcl(entries);
            return () -> acl.setAcl(original);
        }
        var original = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, Set.of());
        return () -> Files.setPosixFilePermissions(directory, original);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.links.junctionRoot", matches = ".+")
    void precreatedWindowsJunctionPublishesCompleteContentOnTheCurrentProvider() throws Exception {
        var root = Path.of(System.getProperty("ysm.links.junctionRoot"));
        var entry = Files.copy(firstFixture, root.resolve("junction-hero.mxc"));
        var expected = Hash256.parse("f033b14236c5610e4868a3f829fbec1fc773a092290d04f8e53618a8d4e7daf7");
        var before = sha256(entry);
        try {
            for (var kind : List.of(CatalogRootKind.CUSTOM, CatalogRootKind.AUTH)) {
                for (var create : new boolean[] {false, true}) {
                    try (var fixture = open(List.of(new ModelCatalogSource(kind, root, create)),
                        (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new))) {
                        fixture.activateAndFinish();
                        assertEquals(0, fixture.catalog().current().report().errorCount());
                        var record = Objects.requireNonNull(fixture.catalog().current().byModelId().get(expected));
                        assertEquals(new CatalogLocation(kind, new ModelPath("junction-hero.mxc")), record.location());
                        assertEquals("junction-hero.mxc", record.entry().path().value());
                        var content = record.binding().content();
                        long count = 0;
                        for (var chunk : content.modelFile().getFileView().getAssetView().getChunkTable().values()) {
                            if (chunk.type().equals(
                                com.elfmcys.ysm.format.container.AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                                continue;
                            try (var bytes =
                                content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY)) {
                                count += bytes.size();
                            }
                        }
                        assertTrue(count > 0);
                        assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
                        assertEquals(record.location(), fixture.catalog().index().entries().get(0).location());
                        assertEquals(before, sha256(entry));
                        System.out.printf(
                            "WINDOWS_JUNCTION kind=%s create=%s path=junction-hero.mxc modelId=%s verifiedBytes=%d%n",
                            kind, create, expected, count);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(entry);
        }
        var nested = root.resolve("nested-junction");
        assertTrue(Files.isDirectory(nested), "Windows must create the nested junction before this run");
        copyRawFixture(root.toRealPath());
        var rawRoot = Files.createDirectory(temp.resolve("cross-provider-raw"));
        Files.createSymbolicLink(rawRoot.resolve("raw-entry"), root);
        try (var fixture = open(rawRoot)) {
            fixture.activateAndFinish();
            assertFalse(fixture.catalog().current().byModelId().containsKey(expected));
            assertTrue(fixture.catalog().current().report().errorCount() > 0);
            Files.delete(nested);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals("raw-entry", fixture.catalog().current().byModelId().get(expected).location().path().value());
            System.out.printf("WINDOWS_JUNCTION raw-entry=accepted nested-junction=rejected modelId=%s%n", expected);
        }
    }

    @Test
    void rootFailureKeepsPublishedContentAndRecoversOnlyOnExplicitReload() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        Files.copy(firstFixture, root.resolve("old.mxc"));
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var saved = Files.move(root, temp.resolve("saved"));
            Files.writeString(root, "not a directory");
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(2, fixture.catalog().current().byModelId().size());
            Files.delete(root);
            Files.move(saved, root);
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
        }
    }

    @Test
    void finalObservationFailureKeepsBothOldMissingAndNewHealthyContent() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var old = Files.copy(firstFixture, root.resolve("old.mxc"));
        try (var fixture = open(root,
            (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new,
                (sequence, policy) -> new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>()) {
                    @Override
                    protected void afterExecute(Runnable runnable, Throwable failure) {
                        if (sequence == 2) {
                            try {
                                Files.createSymbolicLink(root.resolve("late-broken"), temp.resolve("missing-target"));
                            } catch (IOException invalidFixture) {
                                throw new java.io.UncheckedIOException(invalidFixture);
                            }
                        }
                    }
                }))) {
            fixture.activateAndFinish();
            Files.delete(old);
            Files.copy(secondFixture, root.resolve("healthy.mxc"));
            assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
            assertEquals(3, fixture.catalog().current().byModelId().size());
            assertTrue(fixture.catalog().current().report().errors().stream()
                .anyMatch(error -> error.code().equals("CATALOG_FINALIZATION_FAILED")));
            Files.delete(root.resolve("late-broken"));
            assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
            assertFalse(fixture.catalog().current().byModelId().containsKey(identity(firstFixture).modelId()));
        }
    }

    @Test
    void twoVerifiedByteResourcesSurviveLinkedSourceDeletionBadReloadAndPeerRelease() throws Exception {
        var root = Files.createDirectory(temp.resolve("models"));
        var target = Files.copy(firstFixture, temp.resolve("target.data"));
        Files.createSymbolicLink(root.resolve("alias.mxc"), target);
        try (var fixture = open(root)) {
            fixture.activateAndFinish();
            var content =
                fixture.catalog().current().byModelId().get(identity(firstFixture).modelId()).binding().content();
            var chunk = content.modelFile().getFileView().getAssetView()
                .getChunkInfo(com.elfmcys.ysm.format.schema.model.ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            var first = content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY);
            try (var second = first.acquire()) {
                var expected = new byte[second.size()];
                second.nio().get(expected);
                Files.delete(target);
                assertEquals(ReloadStatus.FAILED, fixture.finish(fixture.catalog().reload()).status());
                var actual = new byte[second.size()];
                second.nio().get(actual);
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
                first.close();
                second.nio().get(actual);
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
                assertTrue(Files.isSymbolicLink(root.resolve("alias.mxc")));
            } finally {
                first.close();
            }
        }
    }

    @Test
    void linkedLegacyFixturePublishesAndReadsCompleteContentWithItsLogicalLocation() throws Exception {
        var configured = System.getenv("YSM_LEGACY_LINK_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null && !configured.isBlank());
        var source = Path.of(configured).toRealPath();
        var expectedModelId = Hash256.parse(Objects.requireNonNull(System.getenv("YSM_LEGACY_LINK_MODEL_ID")));
        var expectedSourceHash = Objects.requireNonNull(System.getenv("YSM_LEGACY_LINK_SHA256"));
        assertEquals(expectedSourceHash, sha256(source));
        assertEquals(com.elfmcys.ysm.format.legacy.LegacyContainerHeader.Version.V3_ENCRYPTED,
            com.elfmcys.ysm.format.legacy.LegacyContainerHeader.probe(source));
        for (var kind : List.of(CatalogRootKind.CUSTOM, CatalogRootKind.AUTH)) {
            var root = Files.createDirectory(temp.resolve("models-" + kind));
            var externalGroup = Files.createDirectory(temp.resolve("group-" + kind));
            var physical = Files.copy(source, temp.resolve("physical-" + kind + ".data"));
            var entry = Files.createSymbolicLink(externalGroup.resolve("logical.ysm"), physical);
            var group = root.resolve("team");
            createDirectoryLink(group, externalGroup);
            try (var fixture = open(List.of(new ModelCatalogSource(kind, root, false)),
                (reconciler, initial) -> new ReloadableModelCatalog(reconciler, initial, Thread::new))) {
                fixture.activateAndFinish();
                assertEquals(0, fixture.catalog().current().report().errorCount(),
                    fixture.catalog().current().report().errors().toString());
                var expectedLocation = new CatalogLocation(kind, new ModelPath("team/logical.ysm"));
                var record = Objects.requireNonNull(fixture.catalog().current().byModelId().get(expectedModelId));
                assertEquals(expectedLocation, record.location());
                assertEquals(new HierarchyPath("local:custom/team/logical.ysm"), record.entry().path());
                assertEquals(kind == CatalogRootKind.AUTH ? CatalogAccess.AUTHORIZED : CatalogAccess.PUBLIC,
                    record.entry().access());
                assertEquals(expectedModelId, fixture.catalog().current().byLocation().get(expectedLocation));
                var index = fixture.catalog().index().entries().get(0);
                assertEquals(expectedLocation, index.location());
                assertEquals(expectedModelId, index.modelId());
                var converted = new ConvertedSourceIndexStore(temp.resolve("cache"), "catalog-owner-test").read();
                assertEquals(index.identity(), converted.get(kind.namespace() + "/team/logical.ysm").identity());
                var content = record.binding().content();
                long verifiedBytes = 0;
                for (var chunk : content.modelFile().getFileView().getAssetView().getChunkTable().values()) {
                    if (chunk.type()
                        .equals(com.elfmcys.ysm.format.container.AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                        continue;
                    }
                    try (var bytes =
                        content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY)) {
                        verifiedBytes += bytes.size();
                    }
                }
                assertTrue(verifiedBytes > 0);
                assertEquals(ReloadStatus.COMMITTED, fixture.finish(fixture.catalog().reload()).status());
                assertEquals(expectedModelId, fixture.catalog().current().byLocation().get(expectedLocation));
                System.out.printf("LEGACY_LINK rootKind=%s path=%s modelId=%s containerId=%s verifiedBytes=%d%n", kind,
                    record.entry().path(), expectedModelId, index.identity().containerId(), verifiedBytes);
            } finally {
                Files.delete(group);
            }
            assertEquals(expectedSourceHash, sha256(physical));
            assertTrue(Files.isSymbolicLink(entry));
            assertEquals(physical, Files.readSymbolicLink(entry));
        }
        assertEquals(expectedSourceHash, sha256(source));
    }

    private static String sha256(Path path) throws Exception {
        return java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void zipRaw(Path source, Path target) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(target));
            var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(source.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path parseFixture(String resource, String name, boolean builtin) throws Exception {
        var manifest = ReloadableModelCatalogTest.class.getResource(resource);
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            var output = Files.createDirectories(fixtureTemp.resolve(name));
            var parsed = builtin ? ModelParser.parseBuiltinDefault(vfs, output)
                : ModelParser.parse(vfs, output, DefaultAnimationFilter.keepAll());
            if (builtin)
                return parsed;
            var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(name));
            var content = ManagedContainer.openDirect(parsed, location);
            try {
                return ModelExporter.export(content, TestPreviews.blank(), output.resolve(name + "-portable.mxc"),
                    null);
            } finally {
                content.representation().close();
            }
        }
    }

    private static ManagedContainer openIndexed(Path file, CatalogLocation location) throws Exception {
        return ManagedContainer.openIndexed(new CatalogIndexEntry(identity(file), location, file));
    }

    private static ModelFileIdentity identity(Path file) throws Exception {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }

    private static CatalogSnapshot snapshot(TestContent content, String path) {
        var location = new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(path));
        var binding = new CatalogContentBinding(content.modelId(), content);
        return new CatalogSnapshot(
            Map.of(content.modelId(),
                new CatalogRecord(new CatalogEntry(content.modelId(), new HierarchyPath("local:custom/" + path),
                    CatalogAccess.PUBLIC, CatalogPresentation.empty(path)), location, binding)),
            List.of(), ModelScanReport.empty());
    }

    private static CatalogCandidate candidate(CatalogSnapshot snapshot) {
        return new CatalogCandidate(new CatalogIndexSnapshot(List.of(), snapshot.packs(), snapshot.report()), snapshot);
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }

    private record CatalogFixture(ReloadableModelCatalog catalog) implements AutoCloseable {
        private void activateAndFinish() throws Exception {
            catalog.activate();
            finish();
        }

        private void finish() throws Exception {
            await(() -> {
                catalog.tick();
                return !catalog.busy();
            });
        }

        private void awaitModelCount(int count) throws Exception {
            await(() -> {
                catalog.tick();
                return catalog.current().byModelId().size() == count;
            });
        }

        private ReloadResult finish(CompletableFuture<ReloadResult> completion) throws Exception {
            await(() -> {
                catalog.tick();
                return completion.isDone();
            });
            return completion.join();
        }

        @Override
        public void close() {
            catalog.close();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "Timed out awaiting catalog progress");
    }

    private static final class ProgressGate implements java.util.function.Consumer<CatalogScanProgress> {
        private final java.util.concurrent.BlockingQueue<CatalogScanProgress> observed =
            new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(0);
        private volatile boolean open;

        @Override
        public void accept(CatalogScanProgress progress) {
            if (open)
                return;
            observed.add(progress);
            try {
                permits.acquire();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private CatalogScanProgress next() throws Exception {
            var value = observed.poll(10, TimeUnit.SECONDS);
            assertTrue(value != null, "No progress checkpoint");
            return value;
        }

        private void release() {
            permits.release();
        }

        private void open() {
            open = true;
            permits.release(100);
        }
    }

    private static final class ControlledThreads implements ThreadFactory {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CopyOnWriteArrayList<Thread> threads = new CopyOnWriteArrayList<>();
        private final AtomicInteger completed = new AtomicInteger();
        private Thread coordinator;

        @Override
        public Thread newThread(Runnable runnable) {
            coordinator = new Thread(runnable);
            threads.add(coordinator);
            return coordinator;
        }

        private java.util.concurrent.ExecutorService newExecutor(long sequence,
            com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy policy) {
            var started = new AtomicInteger();
            return new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), runnable -> {
                    var thread = new Thread(runnable);
                    threads.add(thread);
                    return thread;
                }) {
                @Override
                protected void beforeExecute(Thread thread, Runnable runnable) {
                    if (started.incrementAndGet() != 2) {
                        return;
                    }
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                protected void afterExecute(Runnable runnable, Throwable failure) {
                    completed.incrementAndGet();
                }
            };
        }

        private void awaitFirstBatch() throws Exception {
            assertTrue(blocked.await(10, TimeUnit.SECONDS));
            await(() -> completed.get() == 1);
        }

        private void awaitCoordinator() throws Exception {
            coordinator.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(coordinator.isAlive());
        }
    }

    private static final class TestContent implements ModelContent {
        private final Hash256 modelId;
        private final Cleaner.Cleanable cleanupEvent;

        private TestContent(Hash256 modelId, AtomicInteger cleaned) {
            this.modelId = modelId;
            cleanupEvent = CleanerUtil.ref(this, cleaned, value -> value.incrementAndGet());
        }

        @Override
        public Hash256 modelId() {
            return modelId;
        }

        @Override
        public ModelFileView modelFile() {
            return null;
        }

        @Override
        public ModelRepresentation representation() {
            return null;
        }

        @Override
        public ChunkDataSource chunks() {
            return null;
        }

        private Cleaner.Cleanable cleanupEvent() {
            return cleanupEvent;
        }
    }
}
