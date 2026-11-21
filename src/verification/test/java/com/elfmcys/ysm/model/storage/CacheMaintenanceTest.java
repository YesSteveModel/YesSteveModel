package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import static com.elfmcys.ysm.natives.storage.CacheStorageTypes.formatFileId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class CacheMaintenanceTest {
    @TempDir
    Path temp;
    private static final long NOW = 1_800_000_000L;

    @ParameterizedTest
    @CsvSource({"-1,RECORDED", "0,NOT_DUE", "259199,NOT_DUE", "259200,RECORDED", "259201,RECORDED", "-3600,NOT_DUE"})
    void realCatalogUsesUtcBoundaryAndClockRollback(long elapsed, CacheMaintenance.Status expected) throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            var owner = owner(cache, false, elapsed == -1 ? NOW : NOW + elapsed, (phase, state) -> {
            });
            owner.initialize();
            if (elapsed != -1)
                assertTrue(cache.catalog().execute((db, token) -> db.completeMaintenance(0, NOW, token)).committed());
            var result = owner.attempt(Set.of("scanned"));
            assertEquals(expected, result.status(), result.toString());
            var date = state(cache).lastSuccessUtc();
            assertEquals(expected == CacheMaintenance.Status.RECORDED ? (elapsed == -1 ? NOW : NOW + elapsed) : NOW,
                date.orElseThrow());
            assertEquals(CacheMaintenance.Status.ALREADY_ATTEMPTED, owner.attempt(Set.of("scanned")).status());
        }
    }

    @Test
    void timezoneAndEmptySuccessfulRoundDoNotChangeTheUtcDate() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            var clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneId.of("America/New_York"));
            var owner = new CacheMaintenance(cache, new CachePruner(cache), true, clock, (phase, state) -> {
            });
            owner.initialize();
            assertEquals(CacheMaintenance.Status.RECORDED, owner.attempt(Set.of("scanned")).status());
            assertEquals(NOW, state(cache).lastSuccessUtc().orElseThrow());
            assertFalse(Files.exists(temp.resolve("f/bake.sqlite")));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void newOrdinaryAndAllCommandsSurviveBothAcceptedModes(boolean acceptedAll, boolean nextAll) throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            if (acceptedAll)
                assertTrue(cache.catalog().execute((db, token) -> db.resetMaintenance(true, token)).committed());
            var accepted = new java.util.concurrent.atomic.AtomicReference<Maintenance>();
            var owner = owner(cache, false, NOW, (phase, state) -> {
                if (phase == CacheMaintenance.Phase.ACCEPTED) {
                    accepted.set(state);
                    assertTrue(cache.catalog().execute((db, token) -> db.resetMaintenance(nextAll, token)).committed());
                }
            });
            owner.initialize();
            seedObjects(temp, "scanned");
            assertEquals(CacheMaintenance.Status.COMPLETION_SUPERSEDED, owner.attempt(Set.of("scanned")).status());
            for (var target : List.of("c/0000000000000064", "b/0000000000000065", "b/0000000000000066"))
                assertEquals(!acceptedAll, Files.exists(temp.resolve("f").resolve(target)), target);
            var current = state(cache);
            assertTrue(current.lastSuccessUtc().isEmpty());
            assertEquals(accepted.get().resetRevision() + 1, current.resetRevision());
            assertEquals(acceptedAll || nextAll, current.pendingAll());
        }
    }

    @Test
    void completedRoundThenCommandSchedulesTheNextStartup() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            var owner = owner(cache, false, NOW, (phase, state) -> {
            });
            owner.initialize();
            assertEquals(CacheMaintenance.Status.RECORDED, owner.attempt(Set.of("scanned")).status());
            assertTrue(cache.catalog().execute((db, token) -> db.resetMaintenance(true, token)).committed());
            assertTrue(state(cache).lastSuccessUtc().isEmpty());
            assertTrue(state(cache).pendingAll());
        }
    }

    @Test
    void commandDuringUnfinishedStartupCannotBeConsumedAndOrdinaryResetPreservesAll() throws Exception {
        var paths = localPaths();
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            var result = storage.pruneNext(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.get(0).succeeded());
            assertFalse(state(storage.local().cache()).pendingAll(), "disabled global must not save all");
            assertEquals(CacheMaintenance.Status.RESET_AFTER_STARTUP,
                storage.finishStartupScan(roots(paths)).get(storage.local()).status());
            assertTrue(state(storage.local().cache()).lastSuccessUtc().isEmpty());
            assertEquals(CacheMaintenance.Status.ALREADY_ATTEMPTED,
                storage.finishStartupScan(roots(paths)).get(storage.local()).status());
        }
    }

    @Test
    void oldPrefetchIsRecheckedUnderTheWindow() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            var owner = owner(cache, false, NOW, (phase, state) -> {
                if (phase == CacheMaintenance.Phase.BEFORE_WINDOW)
                    assertTrue(
                        cache.catalog().execute((db, token) -> db.completeMaintenance(0, NOW, token)).committed());
                if (phase == CacheMaintenance.Phase.ACCEPTED)
                    fail("old prefetched date cannot authorize a second round");
            });
            owner.initialize();
            assertEquals(CacheMaintenance.Status.NOT_DUE, owner.attempt(Set.of("scanned")).status());
        }
    }

    @ParameterizedTest
    @CsvSource({"OBSERVE,CONVERTED", "OBSERVE,BAKE", "DELETE,CONVERTED", "DELETE,BAKE"})
    void eitherPoolFailureNeverAdvancesDateOrConsumesAll(CachePruner.Phase point, Pool pool) throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            var pruner = new CachePruner(cache, (phase, currentPool, location) -> {
                if (phase == point && currentPool == pool)
                    throw new IOException("injected pool failure");
            });
            var owner = new CacheMaintenance(cache, pruner, false, clock(NOW), (phase, state) -> {
            });
            assertTrue(cache.catalog().execute((db, token) -> db.resetMaintenance(true, token)).committed());
            owner.initialize();
            seedObjects(temp, "scanned");
            var result = owner.attempt(Set.of("scanned"));
            assertEquals(CacheMaintenance.Status.PRUNE_FAILED, result.status(), result.toString());
            assertTrue(state(cache).lastSuccessUtc().isEmpty());
            assertTrue(state(cache).pendingAll());
        }
    }

    @Test
    void failedDateCommitKeepsAllAndDoesNotReportRecorded() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            assertTrue(cache.catalog().execute((db, token) -> db.resetMaintenance(true, token)).committed());
            try (var blocker = new com.elfmcys.ysm.testutil.SqliteProcess(temp.resolve("f/catalog.sqlite"))) {
                var owner = owner(cache, false, NOW, (phase, state) -> {
                    if (phase == CacheMaintenance.Phase.BEFORE_COMPLETE) {
                        try {
                            blocker.execute("BEGIN IMMEDIATE", "UPDATE maintenance SET reset_revision=reset_revision");
                        } catch (IOException failure) {
                            throw new IOException(failure);
                        }
                    }
                });
                owner.initialize();
                assertEquals(CacheMaintenance.Status.FAILED, owner.attempt(Set.of("scanned")).status());
                blocker.execute("ROLLBACK");
                assertTrue(state(cache).lastSuccessUtc().isEmpty());
                assertTrue(state(cache).pendingAll());
            }
        }
    }

    @Test
    void cancelledRoundRestoresProtectionWithoutConsumingRequest() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            assertTrue(cache.catalog().execute((db, token) -> db.resetMaintenance(true, token)).committed());
            var owner = owner(cache, false, NOW, (phase, state) -> {
                if (phase == CacheMaintenance.Phase.BEFORE_COMPLETE)
                    throw new java.util.concurrent.CancellationException("injected cancellation");
            });
            owner.initialize();
            assertThrows(java.util.concurrent.CancellationException.class, () -> owner.attempt(Set.of("scanned")));
            assertTrue(state(cache).lastSuccessUtc().isEmpty());
            assertTrue(state(cache).pendingAll());
            assertTrue(cache.coordinator().registered());
            assertTrue(cache.coordinator().cleanup(() -> true, false));
        }
    }

    @Test
    void rootsTriggerIndependentlyAndOrdinaryCommandPreservesGlobalAll() throws Exception {
        var paths = globalPaths();
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            assertTrue(storage.pruneNext(true).toCompletableFuture().get(5, TimeUnit.SECONDS).stream()
                .allMatch(ModelStorageInfra.PruneReset::succeeded));
            assertTrue(storage.pruneNext(false).toCompletableFuture().get(5, TimeUnit.SECONDS).stream()
                .allMatch(ModelStorageInfra.PruneReset::succeeded));
            assertTrue(state(storage.remote().cache()).pendingAll());
            assertFalse(state(storage.local().cache()).pendingAll());
        }
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            assertTrue(storage.remote().cache().catalog()
                .execute((db, token) -> db.completeMaintenance(2, NOW - 1, token)).committed());
            var result = storage.finishStartupScan(roots(paths));
            assertEquals(CacheMaintenance.Status.RECORDED, result.get(storage.local()).status());
            assertEquals(CacheMaintenance.Status.NOT_DUE, result.get(storage.remote()).status());
            assertEquals(NOW - 1, state(storage.remote().cache()).lastSuccessUtc().orElseThrow());
        }
        try (var storage = ModelStorageInfra.open(paths, clock(NOW + 259201))) {
            var result = storage.finishStartupScan(roots(paths));
            assertTrue(result.values().stream().allMatch(value -> value.status() == CacheMaintenance.Status.RECORDED));
        }
    }

    @Test
    void incompleteOrFailedRootDoesNotBlockTheOtherRootsDate() throws Exception {
        var paths = globalPaths();
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            var localOnly =
                roots(paths).stream().filter(root -> storage.scope(root).region() == storage.local()).toList();
            var result = storage.finishStartupScan(localOnly);
            assertEquals(CacheMaintenance.Status.RECORDED, result.get(storage.local()).status());
            assertEquals(CacheMaintenance.Status.INCOMPLETE_OBSERVATION, result.get(storage.remote()).status());
            assertTrue(state(storage.remote().cache()).lastSuccessUtc().isEmpty());
        }
        try (var storage = ModelStorageInfra.open(paths, clock(NOW + 259200)); var blocker =
            new com.elfmcys.ysm.testutil.SqliteProcess(storage.local().root().resolve("f/catalog.sqlite"))) {
            blocker.execute("BEGIN IMMEDIATE", "UPDATE maintenance SET reset_revision=reset_revision");
            var result = storage.finishStartupScan(roots(paths));
            assertEquals(CacheMaintenance.Status.PRUNE_FAILED, result.get(storage.local()).status());
            assertEquals(CacheMaintenance.Status.RECORDED, result.get(storage.remote()).status());
            blocker.execute("ROLLBACK");
        }
    }

    @Test
    void commandReportsIndependentCommitFailureAndLeavesSuccessfulRootReset() throws Exception {
        var paths = globalPaths();
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            storage.finishStartupScan(roots(paths));
            sql(storage.local().root(), "UPDATE maintenance SET reset_revision=-1");
            var result = storage.pruneNext(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.get(0).succeeded());
            assertTrue(result.get(1).succeeded());
            assertEquals(NOW, state(storage.local().cache()).lastSuccessUtc().orElseThrow());
            assertTrue(state(storage.remote().cache()).lastSuccessUtc().isEmpty());
            assertTrue(state(storage.remote().cache()).pendingAll());
        }
    }

    @Test
    void hostCommandCanCommitWhileCleanupHoldsTheFileGate() throws Exception {
        var paths = localPaths();
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cleaning = CompletableFuture.runAsync(() -> {
                try {
                    storage.local().consumers().cleanup(() -> {
                        entered.countDown();
                        await(release);
                        return true;
                    }, false);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var reset = storage.pruneNext(false).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(reset.get(0).succeeded());
                assertFalse(cleaning.isDone());
            } finally {
                release.countDown();
                cleaning.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void enabledRootAliasesShareOneResetAndOneRevision() throws Exception {
        var game = temp.resolve("game");
        var root = AssetPaths.gameCacheRoot(game);
        var directories = new AssetPaths.GlobalDirectories(temp.resolve("home"), root, CacheDirectoryGuard.open(root));
        var constructor = AssetPaths.class.getDeclaredConstructor(Path.class, Path.class, Path.class,
            AssetPaths.GlobalDirectories.class, List.class);
        constructor.setAccessible(true);
        var paths =
            constructor.newInstance(game, temp.resolve("config"), temp.resolve("builtin"), directories, List.of());
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            assertSame(storage.local(), storage.remote());
            var results = storage.pruneNext(true).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertTrue(results.get(0).succeeded());
            assertTrue(results.get(0).local() && results.get(0).global());
            assertEquals(1, state(storage.local().cache()).resetRevision());
            assertTrue(state(storage.local().cache()).pendingAll());
        }
    }

    @Test
    void protectedObjectsStillPermitRecordingACompletedRound() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            var owner = owner(cache, true, NOW, (phase, state) -> {
            });
            owner.initialize();
            seedObjects(temp, "scanned");
            try (var token = NativeCancellation.create(); var db = NativeCacheCatalog.open(temp.resolve("f"), token)) {
                var object = db.pageConverted(java.util.OptionalLong.empty(), token).rows().get(0);
                cache.coordinator().retain(Pool.CONVERTED, object.location());
            }
            var result = owner.attempt(Set.of("scanned"));
            assertEquals(CacheMaintenance.Status.RECORDED, result.status());
            assertTrue(Files.exists(temp.resolve("f/c/0000000000000064")));
            assertEquals(NOW, state(cache).lastSuccessUtc().orElseThrow());
        }
    }

    @Test
    void failedStartupSnapshotDoesNotResetOrRebuildTheCatalog() throws Exception {
        var paths = localPaths();
        var file = paths.gameCacheRoot().resolve("f/catalog.sqlite");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken catalog");
        try (var storage = ModelStorageInfra.open(paths, clock(NOW))) {
            assertEquals(CacheMaintenance.Status.NO_STARTUP_SNAPSHOT,
                storage.finishStartupScan(roots(paths)).get(storage.local()).status());
            assertFalse(storage.pruneNext(true).toCompletableFuture().get(5, TimeUnit.SECONDS).get(0).succeeded());
            assertEquals("broken catalog", Files.readString(file));
        }
    }

    @Test
    void interruptedDatabaseWaitRetainsCleanupUntilTheSynchronousBorrowEnds() throws Exception {
        try (var cache = new AtomicSharedCache(temp)) {
            cache.coordinator().register();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var terminal = new CompletableFuture<Throwable>();
            var cleaning = new Thread(() -> {
                try {
                    cache.coordinator().cleanup(() -> cache.catalog().execute((db, token) -> {
                        entered.countDown();
                        await(release);
                        return db.readMaintenance(token);
                    }), null);
                    terminal.complete(null);
                } catch (Throwable failure) {
                    terminal.complete(failure);
                }
            });
            cleaning.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            cleaning.interrupt();
            try {
                Thread.sleep(50);
                assertFalse(terminal.isDone());
            } finally {
                release.countDown();
            }
            assertInstanceOf(java.io.InterruptedIOException.class, terminal.get(5, TimeUnit.SECONDS));
            cleaning.join(5000);
            assertTrue(cache.coordinator().registered());
        }
    }

    @Test
    void shutdownWaitsForAdmittedCommandTerminalWithoutPrematureSuccess() throws Exception {
        var storage = ModelStorageInfra.open(localPaths(), clock(NOW));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        storage.local().cache().catalog().submit((db, token) -> {
            entered.countDown();
            await(release);
            return db.readMaintenance(token);
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var reset = storage.pruneNext(false).toCompletableFuture();
        assertFalse(reset.isDone());
        var closing = CompletableFuture.runAsync(storage::close);
        try {
            var results = reset.get(5, TimeUnit.SECONDS);
            assertFalse(results.get(0).succeeded());
            assertFalse(closing.isDone());
        } finally {
            release.countDown();
            closing.get(5, TimeUnit.SECONDS);
        }
    }

    private CacheMaintenance owner(AtomicSharedCache cache, boolean local, long now,
        CacheMaintenance.Checkpoint point) {
        return new CacheMaintenance(cache, new CachePruner(cache), local, clock(now), point);
    }

    static Clock clock(long now) {
        return Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
    }

    static Maintenance state(AtomicSharedCache cache) throws IOException {
        return StorageIndex.require(cache.catalog().read(NativeCacheCatalog::readMaintenance)).rows().get(0);
    }

    static List<com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity> roots(AssetPaths paths) {
        return ModelCatalogSources.sources(paths).stream().map(ModelCatalogSource::identity).toList();
    }

    static void sql(Path root, String statement) throws IOException {
        com.elfmcys.ysm.testutil.SqliteProcess.execute(root.resolve("f/catalog.sqlite"), statement);
    }

    private AssetPaths localPaths() {
        return AssetPaths.localOnly(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"));
    }

    private AssetPaths globalPaths() {
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", temp.resolve("home").toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        return paths;
    }

    static void await(CountDownLatch latch) {
        var interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("Barrier timed out");
                    return;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    static void seedObjects(Path root, String scope) throws Exception {
        var empty = com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[0]).shareArray();
        var hash = new com.elfmcys.ysm.model.domain.Hash256(new byte[32]);
        var identity = new com.elfmcys.ysm.model.domain.ModelFileIdentity(
            com.elfmcys.ysm.model.domain.ModelId.fromHash(hash), hash);
        var converted =
            new Converted(new Location(100, java.util.UUID.randomUUID()), scope, "old-schema", identity, true);
        Files.createDirectories(root.resolve("f/c"));
        Files.writeString(root.resolve("f/c/0000000000000064"), "converted");
        try (var token = NativeCancellation.create(); var catalog = NativeCacheCatalog.open(root.resolve("f"), token)) {
            assertTrue(catalog
                .publishSource(scope,
                    new Source("custom/old", identity, java.util.Optional.of(converted), empty, empty), token)
                .committed());
        }
        for (var kind : BakeKind.values()) {
            var id = 101 + kind.ordinal();
            Files.createDirectories(root.resolve("f/b"));
            Files.writeString(root.resolve("f/b/" + formatFileId(id)), "bake");
            var key = new BakeKey(new Partition(kind, "old-schema", hash, "", ""), "input", "", "", "", "", "");
            try (var token = NativeCancellation.create();
                var db = com.elfmcys.ysm.natives.storage.NativeBakeIndex.open(root.resolve("f"), token)) {
                assertTrue(db
                    .publish(new BakeObject(new File(new Location(id, java.util.UUID.randomUUID()), empty), key), token)
                    .committed());
            }
        }
    }
}
