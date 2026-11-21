package com.elfmcys.ysm.model.storage;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.named;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.fromMap;

import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.NativePlayerData;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.natives.storage.StorageDeliveryException;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static com.elfmcys.ysm.model.storage.PlayerPreferencesTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class StorageWriteBufferTest {
    @TempDir
    Path temp;

    @Test
    void logicalReadsKeepTheirPositionsAcrossClearAndOldCompletion() throws Exception {
        try (var work = new DatabaseWork(); var gate = new WorkerGate(work)) {
            var prefs = new PlayerPreferences(temp, work);
            var buffer = prefs.writes();
            var first = prefs.writeRoaming(U, id("1.0.0"), payload(1));
            var clear = buffer.clearRoaming(U, id("1.0.0"));
            var empty = prefs.readRoaming(U, id("1.0.0"), FIELDS);
            var last = prefs.writeRoaming(U, id("1.0.0"), payload(2));
            var latest = prefs.readRoaming(U, id("1.0.0"), FIELDS);
            assertEquals(StorageWriteBuffer.Admission.QUEUED, clear.admission());
            buffer.tick(0);
            assertEquals(StorageWriteBuffer.Admission.ACCEPTED, clear.admission());
            var emptySnapshot = empty.poll().result();
            assertTrue(named(emptySnapshot.rows().get(0)).isEmpty());
            assertEquals(named(payload(2)), named(latest.poll().result().rows().get(0)));
            assertFalse(first.result().toCompletableFuture().isDone());
            assertFalse(last.result().toCompletableFuture().isDone());
            gate.close();
            assertTrue(await(prefs, first).committed());
            assertTrue(await(prefs, clear).committed());
            assertTrue(await(prefs, last).committed());
            assertTrue(named(emptySnapshot.rows().get(0)).isEmpty());
            assertEquals(named(payload(2)), named(found(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS))));
            assertTrue(buffer.closeAndDrain().isEmpty());
        }
    }

    @Test
    void databaseReadBeforeClearCanReturnLateWithoutChangingNewView() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(1)));
            try (var gate = new WorkerGate(work)) {
                var old = prefs.readRoaming(U, id("1.0.0"), FIELDS);
                var clear = prefs.clearRoaming(U, id("1.0.0"));
                var last = prefs.writeRoaming(U, id("1.0.0"), payload(2));
                prefs.writes().tick(0);
                assertNull(old.poll());
                gate.close();
                // The worker publishes without another logical-owner tick.
                work.awaitIdle();
                assertEquals(named(payload(1)), named(old.poll().result().rows().get(0)));
                assertTrue(await(prefs, clear).committed());
                assertTrue(await(prefs, last).committed());
                assertEquals(named(payload(2)), named(found(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS))));
            }
        }
    }

    @Test
    void coalescingKeepsReadClearAndFlushBoundariesAndUsageMaximum() throws Exception {
        try (var work = new DatabaseWork();
            var region = new CacheRegion(temp.resolve("remote"), null, new DerivedStorageWork(), work)) {
            region.consumers().register();
            var prefs = new PlayerPreferences(temp.resolve("players"), work);
            var first = prefs.writeRoaming(U, id("1.0.0"), payload(1));
            var second = prefs.writeRoaming(U, id("1.0.0"), payload(2));
            var read = prefs.readRoaming(U, id("1.0.0"), FIELDS);
            var third = prefs.writeRoaming(U, id("1.0.0"), payload(3));
            var flush = prefs.writes().flush();
            var fourth = prefs.writeRoaming(U, id("1.0.0"), payload(4));
            assertEquals(named(payload(2)), named(found(prefs, read)));
            assertEquals(StorageResult.Outcome.SUPERSEDED, await(prefs, first).outcome());
            assertTrue(await(prefs, second).committed());
            assertTrue(await(prefs, third).committed());
            assertTrue(await(prefs, flush).committed());
            assertTrue(await(prefs, fourth).committed());
            var container = Hash256.parse("a".repeat(64));
            var index = region.cache().remoteIndex();
            prefs.writes().offerUsage(index, container, 20);
            prefs.writes().offerUsage(index, container, 10);
            await(prefs, prefs.writes().flush());
            assertEquals(20L, index.execute((db, token) -> db.readUsage(container, token)).rows().get(0));
        }
    }

    @Test
    void failedLatestValueIsNotRetriedByTicksAndFlushRetriesOnlyItsCapturedLatestValue() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(1)));
            sql("CREATE TRIGGER reject_update BEFORE UPDATE ON preferences BEGIN SELECT RAISE(ABORT, 'test'); END");
            var failed = prefs.writeRoaming(U, id("1.0.0"), payload(2));
            assertFalse(await(prefs, failed).committed());
            var executions = prefs.writes().metrics().executed();
            for (int i = 0; i < 10; i++)
                PreferenceTestDriver.tick(prefs);
            assertEquals(named(payload(2)), named(found(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS))));
            assertEquals(executions, prefs.writes().metrics().executed());
            sql("DROP TRIGGER reject_update");
            var flush = prefs.writes().flush();
            assertTrue(await(prefs, flush).committed());
            assertFalse(failed.result().toCompletableFuture().join().committed());
            assertEquals(named(payload(2)), named(raw(prefs, id("1.0.0"))));
            assertTrue(prefs.writes().closeAndDrain().isEmpty());
        }
    }

    @Test
    void independentTransactionsRollbackOnlyTheirOwnEffectAndNewWriteResolvesHistoricalFailure() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(1)));
            sql("CREATE TRIGGER reject_update BEFORE UPDATE ON preferences WHEN NEW.identity='owner/model@1.0.0' BEGIN SELECT RAISE(ABORT, 'test'); END");
            var failed = prefs.writeRoaming(U, id("1.0.0"), payload(2));
            var other = prefs.writeRoaming(V, id("2.0.0"), payload(3));
            assertFalse(await(prefs, failed).committed());
            assertTrue(await(prefs, other).committed());
            assertEquals(named(payload(1)), named(raw(prefs, id("1.0.0"))));
            var clear = prefs.clearRoaming(U, id("1.0.0"));
            assertFalse(await(prefs, clear).committed());
            sql("DROP TRIGGER reject_update");
            var last = prefs.writeRoaming(U, id("1.0.0"), payload(4));
            var flush = prefs.writes().flush();
            assertTrue(await(prefs, flush).committed());
            assertTrue(last.result().toCompletableFuture().join().committed());
            assertFalse(failed.result().toCompletableFuture().join().committed());
        }
    }

    @Test
    void knownCommitAndUnknownDeliveryAreNeverReplayed() throws Exception {
        for (var state : List.of(StorageDeliveryException.CommitState.COMMITTED,
            StorageDeliveryException.CommitState.UNKNOWN)) {
            var root = temp.resolve(state.name());
            try (var work = new DatabaseWork()) {
                var prefs = new PlayerPreferences(root, work);
                var key = new StorageWriteBuffer.RoamingKey(U, id("1.0.0"));
                var calls = new AtomicInteger();
                var operation = prefs.writes().write(key, payload(1), 512, token -> {
                    calls.incrementAndGet();
                    var result = prefs.roamingWrite(U, id("1.0.0"), payload(1)).run(token);
                    assertTrue(result.committed());
                    throw new StorageDeliveryException(state, new IllegalStateException("result delivery"));
                }, false);
                assertThrows(ExecutionException.class, () -> await(prefs, operation));
                for (int i = 0; i < 4; i++)
                    PreferenceTestDriver.tick(prefs);
                var flush = prefs.writes().flush();
                assertEquals(state == StorageDeliveryException.CommitState.COMMITTED, await(prefs, flush).committed());
                assertEquals(1, calls.get());
                if (state == StorageDeliveryException.CommitState.UNKNOWN)
                    assertEquals(state, flush.report().get(0).deliveryFailure().commitState());
                var report = prefs.writes().closeAndDrain();
                assertEquals(state == StorageDeliveryException.CommitState.UNKNOWN ? 1 : 0, report.size());
            }
        }
    }

    @Test
    void newWriteDoesNotEraseAnUnknownCommitFromFlushAndClose() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var key = new StorageWriteBuffer.RoamingKey(U, id("1.0.0"));
            var calls = new AtomicInteger();
            var unknown = prefs.writes().write(key, payload(1), 512, token -> {
                calls.incrementAndGet();
                var result = prefs.roamingWrite(U, id("1.0.0"), payload(1)).run(token);
                assertTrue(result.committed());
                throw new StorageDeliveryException(StorageDeliveryException.CommitState.UNKNOWN,
                    new IllegalStateException("result delivery"));
            }, false);
            assertThrows(ExecutionException.class, () -> await(prefs, unknown));
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(2)));
            var flush = prefs.writes().flush();
            var result = await(prefs, flush);
            var closed = prefs.writes().closeAndDrain();
            assertEquals(1, calls.get());
            assertAll(() -> assertFalse(result.committed(), "Flush must retain an unresolved unknown effect"),
                () -> assertTrue(
                    flush.report().stream()
                        .anyMatch(row -> row.deliveryFailure() != null
                            && row.deliveryFailure().commitState() == StorageDeliveryException.CommitState.UNKNOWN),
                    "Flush report must retain the unknown commit"),
                () -> assertTrue(
                    closed.stream()
                        .anyMatch(row -> row.deliveryFailure() != null
                            && row.deliveryFailure().commitState() == StorageDeliveryException.CommitState.UNKNOWN),
                    "Close report must retain the unknown commit"));
        }
    }

    @Test
    void flushKeepsReplacedInFlightUnknownButAllowsAKnownFailureToBeResolved() throws Exception {
        for (var state : List.of(StorageDeliveryException.CommitState.UNKNOWN,
            StorageDeliveryException.CommitState.NOT_COMMITTED)) {
            try (var work = new DatabaseWork(); var gate = new WorkerGate(work)) {
                var prefs = new PlayerPreferences(temp.resolve(state.name()), work);
                var buffer = prefs.writes();
                var calls = new AtomicInteger();
                var unknown =
                    buffer.write(new StorageWriteBuffer.RoamingKey(U, id("1.0.0")), payload(1), 512, token -> {
                        calls.incrementAndGet();
                        if (state == StorageDeliveryException.CommitState.UNKNOWN)
                            assertTrue(prefs.roamingWrite(U, id("1.0.0"), payload(1)).run(token).committed());
                        throw new StorageDeliveryException(state, new IllegalStateException("result delivery"));
                    }, false);
                buffer.tick(0);
                assertTrue(buffer.tick(TimeUnit.SECONDS.toNanos(1)).isPresent());
                var replacement = prefs.writeRoaming(U, id("1.0.0"), payload(2));
                var flush = buffer.flush();
                buffer.tick(TimeUnit.SECONDS.toNanos(2));
                gate.close();
                var result = await(prefs, flush);
                var closed = buffer.closeAndDrain();
                assertAll(() -> assertEquals(state != StorageDeliveryException.CommitState.UNKNOWN, result.committed()),
                    () -> assertEquals(state == StorageDeliveryException.CommitState.UNKNOWN,
                        flush.report().stream().anyMatch(row -> row.deliveryFailure() != null
                            && row.deliveryFailure().commitState() == StorageDeliveryException.CommitState.UNKNOWN)),
                    () -> assertEquals(state == StorageDeliveryException.CommitState.UNKNOWN,
                        closed.stream().anyMatch(row -> row.deliveryFailure() != null
                            && row.deliveryFailure().commitState() == StorageDeliveryException.CommitState.UNKNOWN)));
                assertThrows(CompletionException.class, () -> unknown.result().toCompletableFuture().join());
                assertTrue(replacement.result().toCompletableFuture().join().committed());
                assertEquals(named(payload(2)), named(raw(prefs, id("1.0.0"))));
                assertEquals(1, calls.get());
                assertEquals(0, buffer.metrics().operations());
                assertEquals(0, buffer.metrics().bytes());
            }
        }
    }

    @Test
    void favouritePagesIncludeNewOverlayKeysFalseMembersAndEachRequestsOwnLayout() throws Exception {
        try (var work = new DatabaseWork(); var gate = new WorkerGate(work)) {
            var prefs = new PlayerPreferences(temp, work);
            for (int i = 0; i < 70; i++)
                prefs.writeFavourite(U, ModelId.parse("owner/item" + String.format("%02d", i)), i % 2 == 0);
            var first = prefs.pageFavourites(U, "");
            prefs.writes().tick(0);
            gate.close();
            var page = await(prefs, first);
            assertEquals(64, page.rows().size());
            assertFalse(page.rows().get(1).selected());
            var next = await(prefs, prefs.pageFavourites(U, page.rows().get(63).identity().toString()));
            assertEquals(6, next.rows().size());
            prefs.writeRoaming(U, id("1.0.0"), payload(1));
            assertEquals(StorageResult.Failure.CONTENT,
                await(prefs, prefs.readRoaming(U, id("1.0.0"), Set.of())).failure());
        }
    }

    @Test
    void queuedQueriesUseWeakReferencesAndCloseDoesNotNeedConsumerProgress() throws Exception {
        try (var work = new DatabaseWork(); var gate = new WorkerGate(work)) {
            var prefs = new PlayerPreferences(temp, work);
            var closed = prefs.readSelection(U);
            closed.close();
            var abandoned = prefs.readSelection(U);
            var ingressField = StorageWriteBuffer.class.getDeclaredField("ingress");
            ingressField.setAccessible(true);
            var ingress = (ArrayDeque<?>)ingressField.get(prefs.writes());
            var abandonedNode = ingress.getLast();
            var referenceField = abandonedNode.getClass().getDeclaredField("query");
            referenceField.setAccessible(true);
            ((WeakReference<?>)referenceField.get(abandonedNode)).clear();
            prefs.writes().tick(0);
            gate.close();
            work.awaitIdle();
            assertNull(abandoned.poll());
            assertTrue(closed.poll().closed());
            PreferenceTestDriver.tick(prefs);
            assertEquals(0, prefs.writes().metrics().executed());
            assertEquals(0, prefs.writes().metrics().operations());
            assertTrue(prefs.writes().closeAndDrain().isEmpty());
        }
    }

    @Test
    void publicationAndCloseHaveOneOrderAndDetachedSnapshotsSurvive() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(3)));
            var query = prefs.readRoaming(U, id("1.0.0"), FIELDS);
            PreferenceTestDriver.tick(prefs);
            work.awaitIdle();
            var snapshot = query.poll().result().rows().get(0);
            query.close();
            assertTrue(query.poll().closed());
            committed(prefs, prefs.writeRoaming(U, id("1.0.0"), payload(4)));
            assertEquals(named(payload(3)), named(snapshot));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var active = prefs.writes().query(null, 512, ignored -> token -> {
                entered.countDown();
                waitFor(release);
                return new StorageResult<>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false,
                    List.of(snapshot));
            });
            PreferenceTestDriver.tick(prefs);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            active.close();
            assertTrue(prefs.writes().metrics().operations() > 0);
            release.countDown();
            work.awaitIdle();
            assertTrue(active.poll().closed());
            PreferenceTestDriver.tick(prefs);
            assertEquals(0, prefs.writes().metrics().operations());
        }
    }

    @Test
    void restoreUsesOverlayDonorAndExplicitEmptyAndRetainsStartedSideEffect() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            prefs.writeRoaming(U, id("1.0.0"), payload(8));
            var restore = prefs.restoreRoaming(U, id("2.0.0"), FIELDS);
            assertEquals(named(payload(8)), named(await(prefs, restore).rows().get(0)));
            prefs.clearRoaming(U, id("3.0.0"));
            var empty = prefs.restoreRoaming(U, id("3.0.0"), FIELDS);
            assertTrue(named(await(prefs, empty).rows().get(0)).isEmpty());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var started =
                prefs.writes().restore(new StorageWriteBuffer.RoamingKey(U, id("4.0.0")), 512, ignored -> token -> {
                    entered.countDown();
                    waitFor(release);
                    try (var nativeData = NativePlayerData.open(temp, token)) {
                        return nativeData.insertIfAbsentAndRead(NativePlayerData.PreferenceKey.roaming(U, id("4.0.0")),
                            com.elfmcys.ysm.buffer.ArrayBuffer.borrow(PreferenceCodec.roaming(payload(9))), 0, token)
                            .map(row -> PreferenceCodec.roaming(row.value()));
                    }
                });
            for (int attempt = 0; attempt < 1000 && entered.getCount() != 0; attempt++) {
                PreferenceTestDriver.tick(prefs);
                Thread.sleep(1);
            }
            assertEquals(0, entered.getCount());
            started.cancel();
            release.countDown();
            work.awaitIdle();
            assertTrue(started.result().toCompletableFuture().join().committed());
            assertEquals(named(payload(9)), named(raw(prefs, id("4.0.0"))));
        }
    }

    @Test
    void capacityRejectsWithoutHalfCoverageAndCloseDrainsAfterOrdinaryTicksStop() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var buffer = prefs.writes();
            for (int i = 0; i < StorageWriteBuffer.MAXIMUM_PENDING; i++)
                assertEquals(StorageWriteBuffer.Admission.QUEUED,
                    buffer.offerRoaming(U, PublishedModelId.parse("owner/item" + i), payload(i)));
            var refused = prefs.clearRoaming(U, id("1.0.0"));
            assertEquals(StorageWriteBuffer.Admission.FULL, refused.admission());
            assertEquals(StorageResult.Failure.CAPACITY, refused.result().toCompletableFuture().join().failure());
            buffer.tick(0);
            PreferenceTestDriver.tick(prefs);
            work.awaitIdle();
            PreferenceTestDriver.tick(prefs);
            var unadmitted = prefs.clearRoaming(U, id("1.0.0"));
            var report = buffer.closeAndDrain();
            assertTrue(report.stream().filter(result -> result.storage().failure() != StorageResult.Failure.CLOSED)
                .allMatch(StorageWriteBuffer.WriteResult::succeeded));
            assertEquals(StorageWriteBuffer.Admission.CLOSED, unadmitted.admission());
            assertEquals(0, buffer.metrics().operations());
            assertTrue(buffer.metrics().peakOperations() <= StorageWriteBuffer.MAXIMUM_PENDING);
            assertTrue(buffer.metrics().peakBytes() <= StorageWriteBuffer.MAXIMUM_BYTES);
            assertEquals(StorageWriteBuffer.Admission.CLOSED, prefs.clearRoaming(U, id("1.0.0")).admission());
        }
    }

    @Test
    void continuousReplacementKeepsTheFirstDeadlineAndCrossThreadIngressOrder() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var buffer = prefs.writes();
            for (int i = 0; i < 4; i++) {
                int value = i;
                var submitter = new Thread(() -> buffer.offerRoaming(U, id("1.0.0"), payload(value)));
                submitter.start();
                submitter.join();
                assertTrue(buffer.tick(TimeUnit.MILLISECONDS.toNanos(i * 250L)).isEmpty());
            }
            buffer.offerRoaming(U, id("1.0.0"), payload(4));
            assertTrue(buffer.tick(TimeUnit.SECONDS.toNanos(1)).isPresent());
            work.awaitIdle();
            assertEquals(named(payload(4)), named(raw(prefs, id("1.0.0"))));
            assertEquals(1, buffer.metrics().executed());
            assertTrue(buffer.closeAndDrain().stream().allMatch(StorageWriteBuffer.WriteResult::succeeded));
        }
    }

    @Test
    void flushDoesNotRetryAnInFlightFailureAndDoesNotPromiseTheLaterWrite() throws Exception {
        try (var work = new DatabaseWork(); var gate = new WorkerGate(work)) {
            var prefs = new PlayerPreferences(temp, work);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var key = new StorageWriteBuffer.RoamingKey(U, id("1.0.0"));
            var failed = prefs.writes().write(key, payload(1), 512, token -> {
                attempts.incrementAndGet();
                return StorageResult.<NamedRoamingSnapshot>refused(StorageResult.Failure.ACCESS_IO);
            }, false);
            var flush = prefs.writes().flush();
            prefs.writes().tick(0);
            prefs.writeRoaming(U, id("1.0.0"), payload(2));
            prefs.writes().tick(1);
            gate.close();
            assertFalse(await(prefs, flush).succeeded());
            assertEquals(1, attempts.get());
            assertEquals(StorageResult.Failure.ACCESS_IO, flush.report().get(0).storage().failure());
            assertEquals(named(payload(2)),
                named(await(prefs, prefs.readRoaming(U, id("1.0.0"), FIELDS)).rows().get(0)));
            assertTrue(await(prefs, prefs.writes().flush()).committed());
            assertFalse(failed.result().toCompletableFuture().join().committed());
        }
    }

    @Test
    void byteCapacityCountsLegalPayloadsAndRefusesWithoutReplacingTheLatestValue() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var values = new java.util.TreeMap<String, Float>();
            for (int i = 0; i < 64; i++)
                values.put("field" + String.format("%027d", i), (float)i);
            var large = fromMap(values);
            int accepted = 0;
            while (prefs.writes().offerRoaming(U, PublishedModelId.parse("owner/item" + accepted),
                large) == StorageWriteBuffer.Admission.QUEUED)
                accepted++;
            assertTrue(accepted > 0 && accepted < StorageWriteBuffer.MAXIMUM_PENDING);
            assertTrue(prefs.writes().metrics().peakBytes() <= StorageWriteBuffer.MAXIMUM_BYTES);
            prefs.writes().tick(0);
            assertTrue(prefs.writes().closeAndDrain().stream().allMatch(StorageWriteBuffer.WriteResult::succeeded));
            assertEquals(0, prefs.writes().metrics().bytes());
        }
    }

    @Test
    void closeWaitsForTheActiveNativeBorrowAfterAllOrdinaryTicksStop() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var operation =
                prefs.writes().write(new StorageWriteBuffer.RoamingKey(U, id("1.0.0")), payload(7), 512, token -> {
                    entered.countDown();
                    waitFor(release);
                    return prefs.roamingWrite(U, id("1.0.0"), payload(7)).run(token);
                }, false);
            prefs.writes().tick(0);
            prefs.writes().tick(TimeUnit.SECONDS.toNanos(1));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var unadmitted = prefs.clearRoaming(U, id("1.0.0"));
            var releaser = new Thread(() -> {
                while (unadmitted.admission() != StorageWriteBuffer.Admission.CLOSED)
                    Thread.onSpinWait();
                release.countDown();
            });
            releaser.start();
            var report = prefs.writes().closeAndDrain();
            releaser.join(10_000);
            assertFalse(releaser.isAlive());
            assertTrue(operation.result().toCompletableFuture().join().committed());
            assertEquals(StorageResult.Failure.CLOSED, unadmitted.result().toCompletableFuture().join().failure());
            assertEquals(1, report.stream().filter(result -> result.storage().committed()).count());
            assertEquals(named(payload(7)), named(raw(prefs, id("1.0.0"))));
            assertEquals(0, prefs.writes().metrics().operations());
        }
    }

    @Test
    void flushDoesNotReplayAnInvalidStorageCapability() throws Exception {
        try (var work = new DatabaseWork()) {
            var prefs = new PlayerPreferences(temp, work);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var operation =
                prefs.writes().write(new StorageWriteBuffer.RoamingKey(U, id("1.0.0")), payload(1), 512, token -> {
                    attempts.incrementAndGet();
                    return StorageResult.<NamedRoamingSnapshot>refused(StorageResult.Failure.CLOSED);
                }, false);
            assertEquals(StorageResult.Failure.CLOSED, await(prefs, operation).failure());
            var flush = prefs.writes().flush();
            assertFalse(await(prefs, flush).succeeded());
            assertEquals(1, attempts.get());
            assertEquals(StorageResult.Failure.CLOSED, flush.report().get(0).storage().failure());
        }
    }

    private void sql(String statement) throws Exception {
        try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("preferences-c2.sqlite"));
            var sql = jdbc.createStatement()) {
            sql.execute(statement);
        }
    }

    private static com.elfmcys.ysm.model.storage.NamedRoamingSnapshot raw(PlayerPreferences prefs,
        PublishedModelId identity) throws Exception {
        var rootField = PlayerPreferences.class.getDeclaredField("guard");
        rootField.setAccessible(true);
        var guard = (CacheDirectoryGuard)rootField.get(prefs);
        try (var token = NativeCancellation.create(); var db = NativePlayerData.open(guard.root(), token)) {
            return PreferenceCodec.roaming(
                db.readPreference(NativePlayerData.PreferenceKey.roaming(U, identity), token).rows().get(0).value());
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Test gate timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class WorkerGate implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);

        WorkerGate(DatabaseWork work) throws Exception {
            var entered = new CountDownLatch(1);
            work.submitTask(token -> {
                entered.countDown();
                waitFor(release);
                return StorageResult.refused(StorageResult.Failure.CANCELLED);
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
