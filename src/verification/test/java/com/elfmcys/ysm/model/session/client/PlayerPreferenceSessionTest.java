package com.elfmcys.ysm.model.session.client;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.named;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.fromMap;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.storage.DatabaseWork;
import com.elfmcys.ysm.model.storage.PlayerPreferences;
import com.elfmcys.ysm.model.storage.PreferenceTestDriver;
import com.elfmcys.ysm.model.storage.StorageWriteBuffer;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.NativePlayerData;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(com.elfmcys.ysm.testutil.NativeLibraryExtension.class)
class PlayerPreferenceSessionTest {
    @TempDir
    Path directory;
    private static final UUID U = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID V = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final PublishedModelId A = PublishedModelId.model(ModelId.parse("fixture/model@2.0.0"));
    private static final PublishedModelId B = PublishedModelId.model(ModelId.parse("fixture/other@1.0.0"));

    @Test
    void localSelectionRestoreCannotCrossRetirementOrReplaceNewUserIntent() throws Exception {
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var completions = new LinkedBlockingQueue<Runnable>();
            var failures = new ArrayList<Throwable>();
            var session = new PlayerPreferenceSession(store, writes, completions::add, failures::add, failures::add);
            var saved = new PlayerPreferences.LocalSelection(A, "saved");
            var fallback = new PlayerPreferences.LocalSelection(B, "default");
            var intrinsic = new PlayerPreferences.LocalSelection(PublishedModelId.INTRINSIC_DEFAULT, "");
            PreferenceTestDriver.await(store, store.writeSelection(U, saved));
            var adopted = new ArrayList<PlayerPreferences.LocalSelection>();
            session.restoreLocalSelection(U, fallback, adopted::add);
            settle(store, work);
            session.retire();
            session.restoreLocalSelection(V, fallback, adopted::add);
            assertTrue(adopted.isEmpty());
            settle(store, work);
            session.tick();
            assertEquals(java.util.List.of(fallback), adopted);
            assertTrue(PreferenceTestDriver.await(store, store.readSelection(V)).rows().isEmpty());
            session.retire();
            session.restoreLocalSelection(U, fallback, adopted::add);
            settle(store, work);
            session.writeLocalSelection(U, intrinsic);
            session.tick();
            assertEquals(java.util.List.of(fallback), adopted);
            assertEquals(intrinsic, PreferenceTestDriver.await(store, store.readSelection(U)).rows().get(0));
            // Adopting an online assignment cannot become a Local selection write.
            session.select(V, new Object(), A, 1, payload(1));
            assertTrue(PreferenceTestDriver.await(store, store.readSelection(V)).rows().isEmpty());
            assertTrue(failures.isEmpty());
            writes.closeAndDrain();
        }
    }

    @Test
    void failedLocalSelectionReadUsesFallbackWithoutWritingIt() throws Exception {
        var invalid = new byte[] {1, 0, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't', 0, 1, 'x'};
        try (var cancelled = NativeCancellation.create(); var database = NativePlayerData.open(directory, cancelled)) {
            assertTrue(database
                .putPreference(NativePlayerData.PreferenceKey.selection(U), ArrayBuffer.borrow(invalid), 0, cancelled)
                .committed());
        }
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var completions = new LinkedBlockingQueue<Runnable>();
            var failures = new ArrayList<Throwable>();
            var readFailures = new ArrayList<Throwable>();
            var session =
                new PlayerPreferenceSession(store, writes, completions::add, failures::add, readFailures::add);
            var fallback = new PlayerPreferences.LocalSelection(B, "default");
            var adopted = new ArrayList<PlayerPreferences.LocalSelection>();
            session.restoreLocalSelection(U, fallback, adopted::add);
            settle(store, work);
            session.tick();
            assertEquals(java.util.List.of(fallback), adopted);
            assertEquals(1, readFailures.size());
            assertTrue(failures.isEmpty());
            assertEquals(StorageResult.Failure.CONTENT,
                PreferenceTestDriver.await(store, store.readSelection(U)).failure());
            assertTrue(writes.closeAndDrain().isEmpty());
        }
        try (var cancelled = NativeCancellation.create(); var database = NativePlayerData.open(directory, cancelled)) {
            assertEquals(java.nio.ByteBuffer.wrap(invalid), database
                .readPreference(NativePlayerData.PreferenceKey.selection(U), cancelled).rows().get(0).value().nio());
        }
    }

    @Test
    void delayedRestoreCannotCrossSelectionAccountOrAuthorityIncarnations() throws Exception {
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var completions = new LinkedBlockingQueue<Runnable>();
            var failures = new ArrayList<Throwable>();
            var session = new PlayerPreferenceSession(store, writes, completions::add, failures::add, failures::add);
            assertTrue(PreferenceTestDriver.await(store, store.writeRoaming(U, A, payload(1))).committed());
            var authority = new Object();
            var first = session.select(U, authority, A, 1, null);
            var applied = new java.util.concurrent.atomic.AtomicInteger();
            session.restore(first, Set.of("a"), applied::incrementAndGet);
            settle(store, work);
            session.select(U, authority, B, 2, payload(2));
            var current = session.select(U, authority, A, 3, payload(3));
            session.tick();
            assertEquals(0, applied.get());
            assertEquals(Map.of("a", 3F), named(session.snapshot(current)));
            assertFalse(session.adopt(first, payload(99)));
            var nextAccount = session.select(V, authority, A, 4, payload(4));
            assertFalse(session.adopt(current, payload(99)));
            var nextAuthority = session.select(V, new Object(), A, 1, payload(5));
            assertFalse(session.adopt(nextAccount, payload(99)));
            assertTrue(session.adopt(nextAuthority, payload(6)));
            assertTrue(failures.isEmpty());
            assertEquals(1F,
                named(PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a"))).rows().get(0)).get("a"));
            assertEquals(6F,
                named(PreferenceTestDriver.await(store, store.readRoaming(V, A, Set.of("a"))).rows().get(0)).get("a"));
            writes.closeAndDrain();
        }
    }

    @Test
    void incompatibleRestoreUsesNonPersistableEmptyUntilNewSelection() throws Exception {
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var completions = new LinkedBlockingQueue<Runnable>();
            var failures = new ArrayList<Throwable>();
            var readFailures = new ArrayList<Throwable>();
            var session =
                new PlayerPreferenceSession(store, writes, completions::add, failures::add, readFailures::add);
            PreferenceTestDriver.await(store, store.writeRoaming(U, A, payload(7)));
            var authority = new Object();
            var scope = session.select(U, authority, A, 1, null);
            var applied = new java.util.concurrent.atomic.AtomicInteger();
            session.restore(scope, Set.of("other"), applied::incrementAndGet);
            settle(store, work);
            session.tick();
            assertTrue(named(session.snapshot(scope)).isEmpty());
            assertSame(session.snapshot(scope), session.reportableSnapshot(scope));
            assertFalse(session.persistable(scope));
            assertEquals(1, applied.get());
            assertEquals(1, readFailures.size());
            assertTrue(failures.isEmpty());
            assertTrue(session.adopt(scope, payload(99)));
            assertEquals(named(payload(99)), named(session.snapshot(scope)));
            assertFalse(session.persistable(scope));
            session.restore(scope, Set.of("a"), () -> fail("Failed scope retried restoration"));
            work.awaitIdle();
            assertTrue(completions.isEmpty());
            assertTrue(writes.tick(0).isEmpty());
            assertTrue(writes.tick(TimeUnit.SECONDS.toNanos(1)).isEmpty());
            assertEquals(7F,
                named(PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a"))).rows().get(0)).get("a"));
            session.select(U, authority, B, 2, payload(2));
            var retried = session.select(U, authority, A, 3, null);
            assertFalse(session.persistable(scope));
            session.restore(retried, Set.of("a"), applied::incrementAndGet);
            settle(store, work);
            session.tick();
            assertEquals(2, applied.get());
            assertTrue(session.persistable(retried));
            assertEquals(named(payload(7)), named(session.snapshot(retried)));
            assertTrue(session.adopt(retried, payload(9)));
            assertEquals(named(payload(9)),
                named(PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a"))).rows().get(0)));
            writes.closeAndDrain();
        }
    }

    @Test
    void clearDiscardsTheOldProducerAndResetsPersistenceEligibility() throws Exception {
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var completions = new LinkedBlockingQueue<Runnable>();
            var session = new PlayerPreferenceSession(store, writes, completions::add, failure -> fail(failure),
                failure -> fail(failure));
            var authority = new Object();
            var valid = session.select(U, authority, A, 1, payload(8));
            assertTrue(session.adopt(valid, payload(9)));
            var degraded = session.select(U, authority, A, 2, fromMap(Map.of()), false);
            assertTrue(session.adopt(degraded, payload(99)));
            assertFalse(session.persistable(degraded));
            session.clear(U, A, result -> assertTrue(result.committed()));
            assertTrue(named(session.snapshot(session.scope())).isEmpty());
            assertTrue(session.persistable(session.scope()));
            assertNotNull(session.reportableSnapshot(session.scope()));
            assertFalse(session.adopt(valid, payload(99)));
            assertFalse(session.adopt(degraded, payload(99)));
            settle(store, work);
            var callback = completions.poll(10, TimeUnit.SECONDS);
            assertNotNull(callback);
            callback.run();
            assertTrue(
                named(PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a"))).rows().get(0)).isEmpty());
            assertTrue(session.adopt(session.scope(), payload(10)));
            assertNotNull(session.reportableSnapshot(session.scope()));
            assertEquals(named(payload(10)),
                named(PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a"))).rows().get(0)));
            writes.closeAndDrain();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"selection", "retirement", "clear"})
    void scopeRetirementCancelsQueuedRestoreBeforeItCanFillStorage(String action) throws Exception {
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var completions = new LinkedBlockingQueue<Runnable>();
            var session = new PlayerPreferenceSession(store, writes, completions::add, failure -> fail(failure),
                failure -> fail(failure));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocker = work.createResource(token -> (AutoCloseable)() -> {
            });
            work.submit(blocker, (unused, token) -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("Worker was not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return StorageResult.refused(StorageResult.Failure.CANCELLED);
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var old = session.select(U, new Object(), A, 1, null);
                session.restore(old, Set.of("a"), () -> fail("Cancelled restore was adopted"));
                if (action.equals("clear")) {
                    session.clear(U, A, result -> assertTrue(result.committed()));
                } else {
                    if (action.equals("retirement"))
                        session.retire();
                    var current = session.select(U, new Object(), B, 2, null);
                    session.restore(current, Set.of("a"), () -> {
                    });
                }
                PreferenceTestDriver.tick(store);
                session.tick();
                assertNull(session.snapshot(old));
            } finally {
                release.countDown();
            }
            settle(store, work);
            session.tick();
            for (Runnable callback; (callback = completions.poll()) != null;)
                callback.run();
            assertTrue(named(session.snapshot(session.scope())).isEmpty());
            var original = PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a")));
            assertEquals(action.equals("clear") ? StorageResult.Outcome.FOUND : StorageResult.Outcome.MISSING,
                original.outcome());
            writes.closeAndDrain();
        }
    }

    @Test
    void textureReinstallKeepsTheSameScopeAndDefaultRolesRemainIndependent() throws Exception {
        try (var work = new DatabaseWork()) {
            var store = new PlayerPreferences(directory, work);
            var writes = store.writes();
            var session = new PlayerPreferenceSession(store, writes, Runnable::run, failure -> fail(failure),
                failure -> fail(failure));
            var authority = new Object();
            var ordinary = session.select(U, authority, A, 1, payload(1));
            assertSame(ordinary, session.scope());
            assertTrue(session.adopt(ordinary, payload(2)));
            var intrinsic = session.select(U, authority, PublishedModelId.INTRINSIC_DEFAULT, 2, payload(3));
            assertFalse(session.adopt(ordinary, payload(99)));
            assertTrue(session.adopt(intrinsic, payload(4)));
            assertEquals(2F,
                named(PreferenceTestDriver.await(store, store.readRoaming(U, A, Set.of("a"))).rows().get(0)).get("a"));
            assertEquals(4F,
                named(PreferenceTestDriver
                    .await(store, store.readRoaming(U, PublishedModelId.INTRINSIC_DEFAULT, Set.of("a"))).rows().get(0))
                    .get("a"));
            writes.closeAndDrain();
        }
    }

    private static void settle(PlayerPreferences store, DatabaseWork work) throws Exception {
        PreferenceTestDriver.tick(store);
        work.awaitIdle();
        PreferenceTestDriver.tick(store);
        work.awaitIdle();
        PreferenceTestDriver.tick(store);
    }

    private static NamedRoamingSnapshot payload(float value) {
        return fromMap(Map.of("a", value));
    }
}
