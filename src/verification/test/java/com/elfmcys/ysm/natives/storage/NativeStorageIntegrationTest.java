package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.NativeObject;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeStorageIntegrationTest {
    @TempDir
    Path temp;
    private final UUID player = new UUID(0, 1);
    private final NativePlayerData.PreferenceKey key =
        NativePlayerData.PreferenceKey.roaming(player, PublishedModelId.model(ModelId.parse("owner/model@1.0.0")));

    @Test
    void isolatedProbeAndPartialCoordinationConstructionReleaseEveryHandle() throws Exception {
        try (var running = NativeCancellation.create()) {
            StorageProbe.check(temp, running);
            try (var files = Files.list(temp)) {
                assertEquals(0, files.count());
            }
            var scope = Files.createDirectory(temp.resolve("partial"));
            Files.createFile(scope.resolve("consumers.sqlite"));
            var refused =
                assertThrows(StorageResult.OpenException.class, () -> NativeCoordination.open(scope, running));
            assertEquals(StorageResult.Failure.INCOMPATIBLE, refused.result().failure());
            assertEquals(0, Files.size(scope.resolve("consumers.sqlite")));
            Files.delete(scope.resolve("consumers.sqlite"));
            try (var recovered = NativeCoordination.open(scope, running)) {
                assertEquals(StorageResult.Outcome.FOUND, recovered.beginCleanup(running).outcome());
                assertTrue(recovered.endCleanup().committed());
            }
        }
    }

    @Test
    void firstFillFactsPagingAndCopiedPayloadStayIndependentOfOwners() throws Exception {
        StorageResult<NativePlayerData.Preference> retained;
        try (var running = NativeCancellation.create(); var db = NativePlayerData.open(temp, running)) {
            assertEquals(StorageResult.Outcome.MISSING, db.readPreference(key, running).outcome());
            assertThrows(IllegalArgumentException.class,
                () -> db.putPreference(key, ArrayBuffer.borrow(new byte[0]), -1, running));
            assertThrows(IllegalArgumentException.class,
                () -> db.putPreference(key, ArrayBuffer.borrow(new byte[StorageResult.MAXIMUM_BYTES + 1]), 0, running));
            assertEquals(StorageResult.Outcome.MISSING, db.readPreference(key, running).outcome());
            var first = db.insertIfAbsentAndRead(key, ArrayBuffer.borrow(new byte[] {1, 2, 3}), 10, running);
            assertTrue(first.committed());
            retained = first;
            var second = db.insertIfAbsentAndRead(key, ArrayBuffer.borrow(new byte[] {9}), 20, running);
            assertArrayEquals(new byte[] {1, 2, 3}, bytes(second.rows().get(0).value()));
            assertEquals(10, second.rows().get(0).updatedUtcSeconds());
            var other = NativePlayerData.PreferenceKey.roaming(new UUID(0, 2), PublishedModelId.INTRINSIC_DEFAULT);
            assertTrue(db.putPreference(other, ArrayBuffer.borrow(new byte[] {7}), 20, running).committed());
            assertTrue(db.putPreference(NativePlayerData.PreferenceKey.selection(player),
                ArrayBuffer.borrow(new byte[0]), 30, running).committed());
            var favourite = NativePlayerData.PreferenceKey.favourite(player, ModelId.parse("owner/model@1.0.0"));
            assertEquals(StorageResult.Outcome.MISSING, db.readPreference(favourite, running).outcome());
            for (int index = 0; index < 70; index++) {
                var next = NativePlayerData.PreferenceKey.roaming(player,
                    PublishedModelId.model(ModelId.parse("owner/record" + String.format("%03d", index))));
                assertTrue(db.putPreference(next, ArrayBuffer.borrow(new byte[0]), index, running).committed());
            }
            var page = db.pagePreferences(player, NativePlayerData.PreferenceKind.ROAMING, "", running);
            assertEquals(64, page.rows().size());
            var next = db.pagePreferences(player, NativePlayerData.PreferenceKind.ROAMING,
                page.rows().get(63).key().identity(), running);
            assertEquals(7, next.rows().size());
            db.close();
            assertEquals(StorageResult.Failure.CLOSED, db.readPreference(key, running).failure());
        }
        assertArrayEquals(new byte[] {1, 2, 3}, bytes(retained.rows().get(0).value()));
        try (var running = NativeCancellation.create(); var reopened = NativePlayerData.open(temp, running)) {
            assertEquals(3, reopened.readPreference(key, running).rows().get(0).value().size());
        }
    }

    @Test
    void refusesUnknownWrongRoleCorruptAndWrongEpochWithoutReplacement() throws Exception {
        var emptyRoot = Files.createDirectory(temp.resolve("empty"));
        var empty = Files.createFile(emptyRoot.resolve("preferences-c2.sqlite"));
        var corruptRoot = Files.createDirectory(temp.resolve("corrupt"));
        var corrupt = Files.writeString(corruptRoot.resolve("preferences-c2.sqlite"), "unrecognized original content");
        try (var running = NativeCancellation.create()) {
            assertEquals(StorageResult.Failure.INCOMPATIBLE,
                assertThrows(StorageResult.OpenException.class, () -> NativePlayerData.open(emptyRoot, running))
                    .result().failure());
            assertEquals(0, Files.size(empty));
            assertEquals(StorageResult.Failure.CONTENT,
                assertThrows(StorageResult.OpenException.class, () -> NativePlayerData.open(corruptRoot, running))
                    .result().failure());
            assertEquals("unrecognized original content", Files.readString(corrupt));
            try (var db = NativePlayerData.open(temp, running)) {
                assertTrue(db.putPreference(key, ArrayBuffer.borrow(new byte[] {4}), 1, running).committed());
            }
            Files.copy(temp.resolve("preferences-c2.sqlite"), temp.resolve("catalog.sqlite"));
            assertEquals(StorageResult.Failure.INCOMPATIBLE,
                assertThrows(StorageResult.OpenException.class, () -> NativeCacheCatalog.open(temp, running)).result()
                    .failure());
            var path = temp.resolve("preferences-c2.sqlite");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement()) {
                statement.execute("UPDATE storage_meta SET compatibility_epoch=77");
            }
            assertEquals(StorageResult.Failure.INCOMPATIBLE,
                assertThrows(StorageResult.OpenException.class, () -> NativePlayerData.open(temp, running)).result()
                    .failure());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT compatibility_epoch FROM storage_meta WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals(77, rows.getInt(1));
            }
        }
    }

    @Test
    void jdbcLockBudgetCancellationAndFreshOperationsRetainActualCommit() throws Exception {
        var path = temp.resolve("preferences-c2.sqlite");
        try (var running = NativeCancellation.create(); var db = NativePlayerData.open(temp, running);
            var jdbc = new com.elfmcys.ysm.testutil.SqliteProcess(path)) {
            assertTrue(db.putPreference(key, ArrayBuffer.borrow(new byte[] {1}), 1, running).committed());
            jdbc.execute("BEGIN IMMEDIATE");
            var busy = CompletableFuture
                .supplyAsync(() -> db.putPreference(key, ArrayBuffer.borrow(new byte[] {2}), 2, running))
                .get(2, TimeUnit.SECONDS);
            assertEquals(StorageResult.Failure.RESOURCE_BUSY, busy.failure());
            assertFalse(busy.committed());
            try (var cancel = NativeCancellation.create()) {
                var entered = new CountDownLatch(1);
                var pending = CompletableFuture.supplyAsync(() -> {
                    entered.countDown();
                    return db.putPreference(key, ArrayBuffer.borrow(new byte[] {3}), 3, cancel);
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                Thread.sleep(20);
                cancel.request();
                var cancelled = pending.get(2, TimeUnit.SECONDS);
                assertEquals(StorageResult.Outcome.CANCELLED, cancelled.outcome());
                assertFalse(cancelled.committed());
            }
            jdbc.execute("ROLLBACK");
            assertArrayEquals(new byte[] {1}, bytes(db.readPreference(key, running).rows().get(0).value()));
            var committed = db.putPreference(key, ArrayBuffer.borrow(new byte[] {4}), 4, running);
            running.request();
            assertTrue(committed.committed());
            assertEquals(StorageResult.Outcome.COMMITTED, committed.outcome());
            assertEquals(StorageResult.Outcome.CANCELLED,
                db.putPreference(key, ArrayBuffer.borrow(new byte[] {5}), 5, running).outcome());
        }
        try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = jdbc.createStatement();
            var rows = statement.executeQuery("SELECT value FROM preferences")) {
            assertTrue(rows.next());
            assertArrayEquals(new byte[] {4}, rows.getBytes(1));
        }
    }

    @Test
    void newerCompatibleRevisionKeepsUnknownDataAcrossOldConnectionsAndWrites() throws Exception {
        var path = temp.resolve("preferences-c2.sqlite");
        try (var running = NativeCancellation.create(); var original = NativePlayerData.open(temp, running)) {
            assertTrue(original.putPreference(key, ArrayBuffer.borrow(new byte[] {1}), 1, running).committed());
            assertEquals(1, original.readPreference(key, running).rows().size());
            com.elfmcys.ysm.testutil.SqliteProcess.execute(path, "BEGIN IMMEDIATE",
                "ALTER TABLE preferences ADD COLUMN extension TEXT NOT NULL DEFAULT 'new-row'",
                "UPDATE preferences SET extension='kept'",
                "CREATE TABLE future_data(id INTEGER PRIMARY KEY,value TEXT NOT NULL) STRICT",
                "INSERT INTO future_data(id,value) VALUES(1,'independent')", "PRAGMA user_version=99", "COMMIT");
            assertArrayEquals(new byte[] {1}, bytes(original.readPreference(key, running).rows().get(0).value()));
            assertTrue(original.putPreference(key, ArrayBuffer.borrow(new byte[] {2}), 2, running).committed());
            try (var reopened = NativePlayerData.open(temp, running)) {
                assertArrayEquals(new byte[] {2}, bytes(reopened.readPreference(key, running).rows().get(0).value()));
                assertTrue(reopened.putPreference(key, ArrayBuffer.borrow(new byte[] {3}), 3, running).committed());
            }
        }
        try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = jdbc.createStatement();
            var row = sql.executeQuery("SELECT value,extension,(SELECT value FROM future_data WHERE id=1),"
                + "(SELECT user_version FROM pragma_user_version) FROM preferences")) {
            assertTrue(row.next());
            assertArrayEquals(new byte[] {3}, row.getBytes(1));
            assertEquals("kept", row.getString(2));
            assertEquals("independent", row.getString(3));
            assertEquals(99, row.getInt(4));
            assertFalse(row.next());
        }
    }

    @Test
    void corruptPreviousEpochDoesNotBlockInitializationOrReopen() throws Exception {
        var legacy = Files.writeString(temp.resolve("preferences.sqlite"), "unreadable legacy database");
        var original = Files.readAllBytes(legacy);
        try (var running = NativeCancellation.create(); var database = NativePlayerData.open(temp, running)) {
            assertEquals(StorageResult.Outcome.MISSING, database.readPreference(key, running).outcome());
            assertTrue(database.putPreference(key, ArrayBuffer.borrow(new byte[] {7}), 1, running).committed());
        }
        try (var running = NativeCancellation.create(); var database = NativePlayerData.open(temp, running)) {
            assertArrayEquals(new byte[] {7}, bytes(database.readPreference(key, running).rows().get(0).value()));
        }
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test
    void unreadableFactsRemainPresentAndCannotBeOverwritten() throws Exception {
        var path = temp.resolve("preferences-c2.sqlite");
        try (var running = NativeCancellation.create(); var db = NativePlayerData.open(temp, running);
            var jdbc = new com.elfmcys.ysm.testutil.SqliteProcess(path)) {
            db.putPreference(key, ArrayBuffer.borrow(new byte[] {7}), 1, running);
            jdbc.execute("UPDATE preferences SET format=99");
            assertEquals(StorageResult.Failure.CONTENT, db.readPreference(key, running).failure());
            assertEquals(StorageResult.Failure.CONTENT,
                db.putPreference(key, ArrayBuffer.borrow(new byte[] {8}), 2, running).failure());
            assertEquals(StorageResult.Failure.CONTENT,
                db.insertIfAbsentAndRead(key, ArrayBuffer.borrow(new byte[] {9}), 3, running).failure());
            try (var check = DriverManager.getConnection("jdbc:sqlite:" + path); var query = check.createStatement();
                var rows = query.executeQuery("SELECT format,value FROM preferences")) {
                assertTrue(rows.next());
                assertEquals(99, rows.getInt(1));
                assertArrayEquals(new byte[] {7}, rows.getBytes(2));
            }
            jdbc.execute("UPDATE preferences SET format=1,identity='invalid@'");
            assertEquals(StorageResult.Failure.CONTENT,
                db.pagePreferences(player, NativePlayerData.PreferenceKind.ROAMING, "", running).failure());
        }
    }

    @Test
    void coordinationRetainsOtherConsumersAndRestoresAfterCancellation() throws Exception {
        try (var running = NativeCancellation.create(); var cancelled = NativeCancellation.create();
            var first = NativeCoordination.open(temp, running); var second = NativeCoordination.open(temp, running)) {
            assertEquals(StorageResult.Outcome.REJECTED, first.beginCleanup(running).outcome());
            second.close();
            cancelled.request();
            assertEquals(StorageResult.Outcome.CANCELLED, first.beginCleanup(cancelled).outcome());
            try (var third = NativeCoordination.open(temp, running)) {
                assertEquals(StorageResult.Outcome.REJECTED, first.beginCleanup(running).outcome());
            }
            assertEquals(StorageResult.Outcome.FOUND, first.beginCleanup(running).outcome());
            assertEquals(StorageResult.Failure.RESOURCE_BUSY,
                assertThrows(StorageResult.OpenException.class, () -> NativeCoordination.open(temp, running)).result()
                    .failure());
            assertTrue(first.endCleanup().committed());
            try (var restored = NativeCoordination.open(temp, running)) {
                assertEquals(StorageResult.Outcome.REJECTED, first.beginCleanup(running).outcome());
            }
        }
    }

    @Test
    void committedPayloadConstructionFailureClosesResultAndRetainsDatabaseFact() throws Exception {
        var failedResult = new AtomicReference<NativeStorageResult>();
        var injected = new OutOfMemoryError("Injected Java payload construction failure");
        try (var running = NativeCancellation.create(); var db = NativePlayerData.open(temp, running)) {
            var failure = assertThrows(StorageDeliveryException.class, () -> running
                .borrow(token -> NativeStorageResult.read(() -> rawPut(db.get(), token, new byte[] {8, 6}), result -> {
                    failedResult.set(result);
                    assertEquals(key.identity(), result.preferenceIdentity(0));
                    throw injected;
                })));
            assertSame(injected, failure.getCause());
            assertEquals(StorageDeliveryException.CommitState.COMMITTED, failure.commitState());
            assertThrows(IllegalStateException.class, failedResult.get()::get);
            try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("preferences-c2.sqlite"));
                var statement = jdbc.createStatement();
                var rows = statement.executeQuery("SELECT value FROM preferences")) {
                assertTrue(rows.next());
                assertArrayEquals(new byte[] {8, 6}, rows.getBytes(1));
            }
            assertArrayEquals(new byte[] {8, 6}, bytes(db.readPreference(key, running).rows().get(0).value()));
        }
    }

    @Test
    void nativeFallbackIsAnUnknownDeliveryFailureAndResultAdoptionRollsBack() throws Exception {
        try (var running = NativeCancellation.create(); var db = NativePlayerData.open(temp, running)) {
            var failure = assertThrows(StorageDeliveryException.class, () -> running.borrow(
                token -> NativeStorageResult.read(() -> rawPut(0, token, new byte[] {1}), result -> List.of())));
            assertEquals(StorageDeliveryException.CommitState.UNKNOWN, failure.commitState());
            var adopt = NativeObject.class.getDeclaredMethod("adopt", long.class, Function.class);
            adopt.setAccessible(true);
            var stateType = Class.forName(NativeObject.class.getName() + "$State");
            var constructor = NativeStorageResult.class.getDeclaredConstructor(stateType);
            constructor.setAccessible(true);
            var open = stateType.getDeclaredField("open");
            open.setAccessible(true);
            for (boolean construct : new boolean[] {false, true}) {
                var state = new AtomicReference<Object>();
                var wrapper = new AtomicReference<NativeStorageResult>();
                var injected = new OutOfMemoryError("Injected result adoption failure");
                Function<Object, NativeStorageResult> factory = adopted -> {
                    state.set(adopted);
                    if (construct) {
                        try {
                            wrapper.set(constructor.newInstance(adopted));
                        } catch (ReflectiveOperationException exception) {
                            throw new AssertionError(exception);
                        }
                    }
                    throw injected;
                };
                var result = running.borrow(token -> rawPut(db.get(), token, new byte[] {4}));
                assertNotEquals(0, result);
                var thrown = assertThrows(InvocationTargetException.class, () -> adopt.invoke(null, result, factory));
                assertSame(injected, thrown.getCause());
                assertFalse(((AtomicBoolean)open.get(state.get())).get());
                if (wrapper.get() != null) {
                    assertThrows(IllegalStateException.class, wrapper.get()::get);
                    wrapper.get().close();
                }
            }
        }
    }

    @Test
    void simpleResultsValidateHeadersAndUsageShapeWithoutInventingACommit() {
        var committed = NativeStorageResult.terminal(() -> new long[] {2, 0, 1, 0});
        assertEquals(StorageResult.Outcome.COMMITTED, committed.outcome());
        assertTrue(committed.committed());
        var cleanupFailed = NativeStorageResult.terminal(() -> new long[] {4, 8, 1, 0});
        assertEquals(StorageResult.Outcome.FAILED, cleanupFailed.outcome());
        assertEquals(StorageResult.Failure.INTERNAL, cleanupFailed.failure());
        assertTrue(cleanupFailed.committed());
        assertEquals(StorageDeliveryException.CommitState.UNKNOWN,
            assertThrows(StorageDeliveryException.class, () -> NativeStorageResult.terminal(() -> null)).commitState());
        assertEquals(StorageDeliveryException.CommitState.UNKNOWN,
            assertThrows(StorageDeliveryException.class, () -> NativeStorageResult.terminal(() -> new long[] {2, 0, 1}))
                .commitState());
        assertEquals(StorageDeliveryException.CommitState.COMMITTED, assertThrows(StorageDeliveryException.class,
            () -> NativeStorageResult.terminal(() -> new long[] {2, 0, 1, 0, 99})).commitState());
        assertEquals(List.of(123L), NativeStorageResult.usage(() -> new long[] {0, 0, 0, 1, 123}).rows());
        assertEquals(StorageResult.Outcome.MISSING, NativeStorageResult.usage(() -> new long[] {1, 0, 0, 0}).outcome());
        assertEquals(StorageDeliveryException.CommitState.NOT_COMMITTED,
            assertThrows(StorageDeliveryException.class, () -> NativeStorageResult.usage(() -> new long[] {0, 0, 0, 1}))
                .commitState());
    }

    @Test
    void simpleNativeCommitSurvivesJavaValueFailureAndNullFallbackCannotBecomeMissing() throws Exception {
        var container = Hash256.parse("23".repeat(32));
        var injected = new OutOfMemoryError("Injected simple result construction failure");
        try (var running = NativeCancellation.create(); var remote = NativeCacheRemote.open(temp, running)) {
            var delivery = assertThrows(StorageDeliveryException.class, () -> running.borrow(
                token -> NativeStorageResult.simple(() -> rawMaxUsage(remote.get(), token, container, 123), value -> {
                    throw injected;
                })));
            assertEquals(StorageDeliveryException.CommitState.COMMITTED, delivery.commitState());
            assertSame(injected, delivery.getCause());
            assertEquals(123L, remote.readUsage(container, running).rows().get(0));
            try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("remote.sqlite"));
                var statement = jdbc.createStatement();
                var rows = statement.executeQuery("SELECT seconds FROM usage")) {
                assertTrue(rows.next());
                assertEquals(123L, rows.getLong(1));
            }
            var fallback = assertThrows(StorageDeliveryException.class, () -> running
                .borrow(token -> NativeStorageResult.terminal(() -> rawMaxUsage(0, token, container, 456))));
            assertEquals(StorageDeliveryException.CommitState.UNKNOWN, fallback.commitState());
            assertEquals(123L, remote.readUsage(container, running).rows().get(0));
        }
    }

    private static long[] rawMaxUsage(long owner, long token, Hash256 container, long utcSeconds) {
        try {
            var method = NativeCacheRemote.class.getDeclaredMethod("nMaxUsage", long.class, String.class, long.class,
                long.class);
            method.setAccessible(true);
            return (long[])method.invoke(null, owner, container.toString(), utcSeconds, token);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private long rawPut(long owner, long token, byte[] value) {
        try {
            var method = NativePlayerData.class.getDeclaredMethod("nPutPreference", long.class, String.class, int.class,
                String.class, byte[].class, long.class, long.class);
            method.setAccessible(true);
            return (long)method.invoke(null, owner, player.toString(), key.kind().ordinal(), key.identity(), value, 1L,
                token);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] bytes(com.elfmcys.ysm.buffer.UniBuffer buffer) {
        var bytes = new byte[buffer.size()];
        buffer.nio().get(bytes);
        return bytes;
    }

    @Test
    void flatIdsRoundTripEveryBitAndExhaustionAcrossJni() throws Exception {
        long[] ids = {0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1};
        try (var running = NativeCancellation.create(); var allocator = NativeFileIdAllocator.open(temp, running)) {
            for (long expected : ids) {
                com.elfmcys.ysm.testutil.SqliteProcess.execute(temp.resolve("ids.sqlite"),
                    "UPDATE allocator SET next_id=" + expected + ",exhausted=0");
                var allocated = allocator.allocate(running);
                assertTrue(allocated.committed());
                assertEquals(expected, allocated.rows().get(0));
                var name = CacheStorageTypes.formatFileId(expected);
                assertEquals(16, name.length());
                assertTrue(name.matches("[0-9a-f]{16}"));
                assertEquals(expected, CacheStorageTypes.parseFileId(name));
            }
            assertEquals("0000000000000001", CacheStorageTypes.formatFileId(1));
            assertEquals(StorageResult.Failure.CAPACITY, allocator.allocate(running).failure());
            assertThrows(IllegalArgumentException.class, () -> CacheStorageTypes.parseFileId("FFFFFFFFFFFFFFFF"));
            assertThrows(IllegalArgumentException.class, () -> CacheStorageTypes.parseFileId("1"));
            allocator.close();
            assertEquals(StorageResult.Failure.CLOSED, allocator.allocate(running).failure());
        }
    }

    @Test
    void flatCatalogSourceFactsOwnershipScopeAndMaintenanceSurviveReopen() throws Exception {
        var identity = new ModelFileIdentity(ModelId.parse("owner/model"), new Hash256(new byte[32]));
        var oldLocation = new CacheStorageTypes.Location(Long.MIN_VALUE, new UUID(0, 1));
        var newLocation = new CacheStorageTypes.Location(-1, new UUID(0, 2));
        var empty = com.elfmcys.ysm.buffer.SharedArrayBuffer.adopt(ArrayBuffer.move(new byte[0]));
        var object = new CacheStorageTypes.Converted(oldLocation, "scope", "model-schema", identity, true);
        var source = new CacheStorageTypes.Source("model.zip", identity, java.util.Optional.of(object), empty, empty);
        StorageResult<CacheStorageTypes.Source> snapshot;
        try (var running = NativeCancellation.create(); var catalog = NativeCacheCatalog.open(temp, running)) {
            assertEquals(CacheStorageTypes.ReferenceState.UNKNOWN,
                catalog.references(identity.containerId(), running).rows().get(0).state());
            assertTrue(catalog.setReferencesComplete(true, running).committed());
            assertTrue(catalog.publishSource("scope", source, running).committed());
            assertTrue(catalog.setScopeComplete("scope", true, running).committed());
            snapshot = catalog.lookupSource("scope", "model.zip", running);
            assertEquals(source.identity(), snapshot.rows().get(0).identity());
            assertEquals(source.converted(), snapshot.rows().get(0).converted());
            assertEquals(1, catalog.pageSources("scope", "", running).rows().size());
            assertEquals(new CacheStorageTypes.Scope("scope", true), catalog.pageScopes("", running).rows().get(0));
            assertEquals(object,
                catalog.pageConvertedScope("scope", java.util.OptionalLong.empty(), running).rows().get(0));
            assertTrue(catalog.pageConvertedScope("other", java.util.OptionalLong.empty(), running).rows().isEmpty());
            var file = new CacheStorageTypes.File(oldLocation, empty);
            assertTrue(
                catalog.publishExact(NativeArtifactIndex.ExactKind.PREVIEW, "preview", file, running).committed());
            assertCacheFile(file,
                catalog.lookupExact(NativeArtifactIndex.ExactKind.PREVIEW, "preview", running).rows().get(0));
            var replacement = new CacheStorageTypes.Converted(newLocation, "scope", "model-schema", identity, true);
            assertTrue(catalog.publishSource("scope",
                new CacheStorageTypes.Source("model.zip", identity, java.util.Optional.of(replacement), empty, empty),
                running).committed());
            assertTrue(catalog.revokeConverted(oldLocation, running).committed());
            assertTrue(catalog.forgetConverted(oldLocation, running).committed());
            assertEquals(newLocation,
                catalog.lookupSource("scope", "model.zip", running).rows().get(0).converted().orElseThrow().location());
            assertEquals(newLocation,
                catalog.pageConverted(java.util.OptionalLong.empty(), running).rows().get(0).location());
            assertEquals(CacheStorageTypes.ReferenceState.REFERENCED,
                catalog.references(identity.containerId(), running).rows().get(0).state());
            assertTrue(catalog.finishScan("scope", List.of("model.zip"), running).committed());
            assertEquals(CacheStorageTypes.ReferenceState.UNREFERENCED,
                catalog.references(identity.containerId(), running).rows().get(0).state());
            var initial = catalog.readMaintenance(running).rows().get(0);
            assertTrue(initial.lastSuccessUtc().isEmpty());
            assertEquals(0, initial.resetRevision());
            assertTrue(catalog.resetMaintenance(true, running).committed());
            assertTrue(catalog.resetMaintenance(false, running).committed());
            assertEquals(StorageResult.Outcome.REJECTED, catalog.completeMaintenance(0, 100, running).outcome());
            var pending = catalog.readMaintenance(running).rows().get(0);
            assertTrue(pending.pendingAll());
            assertEquals(2, pending.resetRevision());
            assertTrue(pending.lastSuccessUtc().isEmpty());
        }
        assertEquals(oldLocation, snapshot.rows().get(0).converted().orElseThrow().location());
        try (var running = NativeCancellation.create(); var catalog = NativeCacheCatalog.open(temp, running)) {
            assertEquals(2, catalog.readMaintenance(running).rows().get(0).resetRevision());
            assertTrue(catalog.completeMaintenance(2, 100, running).committed());
            var complete = catalog.readMaintenance(running).rows().get(0);
            assertEquals(100, complete.lastSuccessUtc().orElseThrow());
            assertFalse(complete.pendingAll());
        }
    }

    @Test
    void independentBakeSupportsFullIdentityPartitionsAndReadOnlyAccess() throws Exception {
        try (var running = NativeCancellation.create()) {
            assertTrue(NativeBakeIndex.openReadOnly(temp, running).isEmpty());
            assertFalse(Files.exists(temp.resolve("bake.sqlite")));
        }
        var cid = new Hash256(new byte[32]);
        var partition = new CacheStorageTypes.Partition(CacheStorageTypes.BakeKind.ANIMATION, "animation-schema", cid,
            "binding", "GUI");
        var key = new CacheStorageTypes.BakeKey(partition, "input", "cache-abi", "capability", "engine-package",
            "compiler", "frozen");
        var location = new CacheStorageTypes.Location(-1, new UUID(0, 1));
        var metadata = com.elfmcys.ysm.buffer.SharedArrayBuffer.adopt(ArrayBuffer.move(new byte[] {1, 2, 3}));
        var file = new CacheStorageTypes.File(location, metadata);
        var object = new CacheStorageTypes.BakeObject(file, key);
        try (var running = NativeCancellation.create(); var bake = NativeBakeIndex.open(temp, running)) {
            assertTrue(bake.publish(object, running).committed());
            assertTrue(bake.hasPartition(partition, running).rows().get(0));
            assertFalse(bake.hasPartition(
                new CacheStorageTypes.Partition(partition.kind(), partition.cacheVersion(), cid, "binding", "FULL"),
                running).rows().get(0));
            assertCacheFile(file, bake.lookupExact(key, running).rows().get(0));
            assertEquals(object.key(), bake.pageObjects(java.util.OptionalLong.empty(), running).rows().get(0).key());
            assertCacheFile(object.file(),
                bake.pageObjects(java.util.OptionalLong.empty(), running).rows().get(0).file());
            assertTrue(bake.pageObjects(java.util.OptionalLong.of(-1), running).rows().isEmpty());
            var otherKey = new CacheStorageTypes.BakeKey(partition, "input", "other-abi", key.capability(),
                key.enginePackage(), key.compilation(), key.frozen());
            var otherFile = new CacheStorageTypes.File(new CacheStorageTypes.Location(0, new UUID(0, 2)), metadata);
            assertTrue(bake.publish(new CacheStorageTypes.BakeObject(otherFile, otherKey), running).committed());
            assertCacheFile(file, bake.lookupExact(key, running).rows().get(0));
            assertCacheFile(otherFile, bake.lookupExact(otherKey, running).rows().get(0));
            assertEquals(2, bake.pageObjects(java.util.OptionalLong.empty(), running).rows().size());
        }
        try (var running = NativeCancellation.create();
            var bake = NativeBakeIndex.openReadOnly(temp, running).orElseThrow()) {
            assertCacheFile(file, bake.lookupExact(key, running).rows().get(0));
            assertEquals(StorageResult.Failure.ACCESS_IO, bake.publish(object, running).failure());
        }
        try (var running = NativeCancellation.create(); var bake = NativeBakeIndex.open(temp, running)) {
            assertTrue(bake.revoke(location, running).committed());
            assertTrue(bake.forget(location, running).committed());
            assertEquals(StorageResult.Outcome.MISSING, bake.lookupExact(key, running).outcome());
        }
    }

    @Test
    void flatRemoteReferencesRequireAcceptedIdentityAndCoverage() throws Exception {
        var identity = new ModelFileIdentity(ModelId.parse("owner/model"), new Hash256(new byte[32]));
        var empty = com.elfmcys.ysm.buffer.SharedArrayBuffer.adopt(ArrayBuffer.move(new byte[0]));
        var file = new CacheStorageTypes.File(new CacheStorageTypes.Location(Long.MIN_VALUE, new UUID(0, 1)), empty);
        try (var running = NativeCancellation.create(); var remote = NativeCacheRemote.open(temp, running)) {
            assertEquals(CacheStorageTypes.ReferenceState.UNKNOWN,
                remote.references(identity.containerId(), running).rows().get(0).state());
            assertTrue(remote.setReferencesComplete(true, running).committed());
            assertTrue(
                remote.publishExact(NativeArtifactIndex.ExactKind.METADATA, "metadata", file, running).committed());
            assertCacheFile(file,
                remote.lookupExact(NativeArtifactIndex.ExactKind.METADATA, "metadata", running).rows().get(0));
            assertTrue(remote.maxUsage(identity.containerId(), 20, running).committed());
            assertEquals(20, remote.readUsage(identity.containerId(), running).rows().get(0));
            assertEquals(CacheStorageTypes.ReferenceState.UNREFERENCED,
                remote.references(identity.containerId(), running).rows().get(0).state());
            assertTrue(remote.setReferencesComplete(false, running).committed());
            assertEquals(CacheStorageTypes.ReferenceState.UNKNOWN,
                remote.references(identity.containerId(), running).rows().get(0).state());
            assertTrue(remote.setReference("accepted", identity, true, running).committed());
            assertEquals(CacheStorageTypes.ReferenceState.REFERENCED,
                remote.references(identity.containerId(), running).rows().get(0).state());
            assertTrue(remote.setReference("accepted", identity, false, running).committed());
            assertTrue(remote.setReferencesComplete(true, running).committed());
            assertEquals(CacheStorageTypes.ReferenceState.UNREFERENCED,
                remote.references(identity.containerId(), running).rows().get(0).state());
            try (var cancelled = NativeCancellation.create()) {
                cancelled.request();
                assertEquals(StorageResult.Outcome.CANCELLED,
                    remote.references(identity.containerId(), cancelled).outcome());
            }
        }
    }

    private static void assertCacheFile(CacheStorageTypes.File expected, CacheStorageTypes.File actual) {
        assertEquals(expected.location(), actual.location());
        assertArrayEquals(bytes(expected.metadata()), bytes(actual.metadata()));
    }

}
