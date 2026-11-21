package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.elfmcys.ysm.natives.storage.NativeBakeIndex;
import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.NativeCacheRemote;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CachePrunerTest {
    @TempDir
    Path temp;

    @Test
    void localSchemaPolicyIsIndependentOfSourcesAndPreservesUnscannedScope() throws Exception {
        authorities(true);
        var expired = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
        var unscanned = converted(2, "other", AbiVersion.MODEL_SCHEMA, false);
        var oldSchema = converted(3, "other", "old-schema", true);
        var current = converted(4, "scanned", AbiVersion.MODEL_SCHEMA, true);
        var referenced = identity(9);
        source("direct", "direct/model", referenced);
        var unsupported = bake(10, BakeKind.GEOMETRY, AbiVersion.CACHE_GEOMETRY, "9.0.0", referenced);
        var otherVersion = bake(11, BakeKind.ANIMATION, "old-schema", AbiVersion.CACHE_ANIMATION, referenced);
        var unknown = bake(12, BakeKind.ANIMATION, AbiVersion.CACHE_ANIMATION, "", referenced);
        var supported = bake(13, BakeKind.GEOMETRY, AbiVersion.CACHE_GEOMETRY, AbiVersion.CACHE_GEOMETRY, referenced);
        try (var cache = new AtomicSharedCache(temp)) {
            var result = run(cache, new CachePruner(cache), true);
            assertTrue(result.completed(), result.toString());
            assertEquals(2, result.convertedReclaimed());
            assertEquals(2, result.bakeReclaimed());
            absent(Pool.CONVERTED, expired.location(), oldSchema.location());
            present(Pool.CONVERTED, unscanned.location(), current.location());
            absent(Pool.BAKE, unsupported.file().location(), otherVersion.file().location());
            present(Pool.BAKE, unknown.file().location(), supported.file().location());
            try (var token = NativeCancellation.create();
                var db = NativeCacheCatalog.openExisting(temp.resolve("f"), token)) {
                assertEquals(ReferenceState.REFERENCED,
                    db.references(oldSchema.identity().containerId(), token).rows().get(0).state());
                assertTrue(db.lookupSource("other", "custom/3", token).rows().get(0).converted().isEmpty());
                assertTrue(db.readMaintenance(token).rows().get(0).lastSuccessUtc().isEmpty());
            }
        }
    }

    @Test
    void globalOrdinaryPreservesOtherSchemasAndAcceptedAllReclaimsAllThreeTypes() throws Exception {
        authorities(true);
        var converted = converted(1, "scanned", "old-schema", true);
        var referenced = identity(3);
        source("scanned", "builtin/model", referenced);
        var geometry = bake(2, BakeKind.GEOMETRY, "old-schema", AbiVersion.CACHE_GEOMETRY, referenced);
        var animation = bake(3, BakeKind.ANIMATION, "old-schema", AbiVersion.CACHE_ANIMATION, referenced);
        try (var cache = new AtomicSharedCache(temp)) {
            var pruner = new CachePruner(cache);
            var ordinary = run(cache, pruner, false);
            assertTrue(ordinary.completed(), ordinary.toString());
            assertEquals(0, ordinary.convertedReclaimed() + ordinary.bakeReclaimed());
            present(Pool.CONVERTED, converted.location());
            present(Pool.BAKE, geometry.file().location(), animation.file().location());
            var all = run(cache, pruner, true);
            assertTrue(all.completed(), all.toString());
            assertEquals(1, all.convertedReclaimed());
            assertEquals(2, all.bakeReclaimed());
        }
    }

    @Test
    void lastCatalogOrRemoteReferenceProtectsEveryAbiAndVersionAcrossPages() throws Exception {
        authorities(true);
        var identity = identity(1);
        source("scanned", "custom/raw", identity);
        source("direct", "auth/direct", identity);
        source("builtin", "builtin/model", identity);
        try (var token = NativeCancellation.create(); var remote = NativeCacheRemote.open(temp.resolve("f"), token)) {
            assertTrue(remote.setReference("remote", identity, true, token).committed());
        }
        for (var id = 1; id <= 80; id++)
            bake(id, id % 2 == 0 ? BakeKind.GEOMETRY : BakeKind.ANIMATION, "schema-" + id, "", identity);
        bake(-1, BakeKind.ANIMATION, "last-schema", "", identity);
        try (var cache = new AtomicSharedCache(temp)) {
            var bakeObservations = new java.util.concurrent.atomic.AtomicInteger();
            var pruner = new CachePruner(cache, (phase, pool, location) -> {
                if (phase == CachePruner.Phase.OBSERVE && pool == Pool.BAKE)
                    bakeObservations.incrementAndGet();
            });
            for (var entry : List.of(new String[] {"scanned", "custom/raw"}, new String[] {"direct", "auth/direct"},
                new String[] {"builtin", "builtin/model"})) {
                var kept = run(cache, pruner, false);
                assertTrue(kept.completed(), kept.toString());
                assertEquals(0, kept.bakeReclaimed());
                try (var token = NativeCancellation.create();
                    var db = NativeCacheCatalog.openExisting(temp.resolve("f"), token)) {
                    assertTrue(db.finishScan(entry[0], List.of(entry[1]), token).committed());
                }
            }
            assertEquals(0, run(cache, pruner, false).bakeReclaimed());
            try (var token = NativeCancellation.create();
                var remote = NativeCacheRemote.openExisting(temp.resolve("f"), token)) {
                assertTrue(remote.setReference("remote", identity, false, token).committed());
            }
            var reclaimed = run(cache, pruner, false);
            assertTrue(reclaimed.completed(), reclaimed.toString());
            assertEquals(81, reclaimed.bakeReclaimed());
            assertEquals(5, bakeObservations.get(), "Each round needs only one batch for the shared CID");
            try (var token = NativeCancellation.create(); var db = NativeBakeIndex.open(temp.resolve("f"), token)) {
                assertTrue(db.pageObjects(OptionalLong.empty(), token).rows().isEmpty());
            }
            assertFalse(Files.exists(path(Pool.BAKE, -1)));
        }
    }

    @Test
    void missingRequiredAuthoritiesPreventBothPoolsAndAreNotRecreated() throws Exception {
        for (var missing : List.of("catalog.sqlite", "remote.sqlite")) {
            authorities(true);
            converted(1, "scanned", "old-schema", true);
            bake(2, BakeKind.GEOMETRY, "old-schema", "9.0.0", identity(2));
            Files.delete(temp.resolve("f").resolve(missing));
            try (var cache = new AtomicSharedCache(temp)) {
                var result = run(cache, new CachePruner(cache), true);
                assertEquals(CachePruner.Status.FAILED, result.status());
                assertEquals(CachePruner.Phase.OBSERVE, result.failure().orElseThrow().phase());
                assertEquals(0, result.convertedReclaimed() + result.bakeReclaimed());
                assertTrue(Files.exists(path(Pool.CONVERTED, 1)));
                assertTrue(Files.exists(path(Pool.BAKE, 2)));
                assertFalse(Files.exists(temp.resolve("f").resolve(missing)));
            }
            try (var files = Files.walk(temp)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    if (!file.equals(temp))
                        Files.delete(file);
            }
        }
    }

    @Test
    void unknownCoverageOnlyDisablesTheUnreferencedReason() throws Exception {
        authorities(false);
        var unknown = bake(1, BakeKind.ANIMATION, AbiVersion.CACHE_ANIMATION, "", identity(1));
        var abi = bake(2, BakeKind.ANIMATION, AbiVersion.CACHE_ANIMATION, "9.0.0", identity(2));
        var schema = bake(3, BakeKind.GEOMETRY, "old-schema", "", identity(3));
        try (var cache = new AtomicSharedCache(temp)) {
            assertFalse(cache.coordinator().cleanup(() -> new CachePruner(cache).initializeReferences(), true));
            var result = run(cache, new CachePruner(cache), true);
            assertTrue(result.completed(), result.toString());
            assertEquals(2, result.bakeReclaimed());
            present(Pool.BAKE, unknown.file().location());
            absent(Pool.BAKE, abi.file().location(), schema.file().location());
        }
    }

    @Test
    void corruptRequiredIndexPreventsIndependentSchemaDeletionAndPreservesTheDatabase() throws Exception {
        authorities(true);
        var object = converted(1, "scanned", "old-schema", true);
        bake(2, BakeKind.ANIMATION, "old-schema", "9.0.0", identity(2));
        var remote = temp.resolve("f/remote.sqlite");
        var corrupt = new byte[] {4, 5, 6};
        Files.write(remote, corrupt);
        try (var cache = new AtomicSharedCache(temp)) {
            var result = run(cache, new CachePruner(cache), true);
            assertFalse(result.completed());
            assertEquals(CachePruner.Phase.OBSERVE, result.failure().orElseThrow().phase());
            present(Pool.CONVERTED, object.location());
            assertTrue(Files.exists(path(Pool.BAKE, 2)));
            assertArrayEquals(corrupt, Files.readAllBytes(remote));
        }
    }

    @Test
    void retainedLazyCandidateAndAdmittedProductionWinOverEveryDeletionReason() throws Exception {
        authorities(true);
        var lazy = bake(1, BakeKind.ANIMATION, "old-schema", "9.0.0", identity(1));
        try (var cache = new AtomicSharedCache(temp)) {
            var sources = new ConvertedSourceIndexStore(ConvertedScope.of(temp.resolve("data"), "old-schema"), cache);
            var active = converted(2, sources.key(), "old-schema", true);
            var candidate = sources.find("custom/2").orElseThrow();
            assertEquals(lazy.file().location(), cache.bakeIndex().find(lazy.key()).orElseThrow().location());
            try (var opened = cache.openRead(candidate.file()); var staging = cache.reserve(Pool.BAKE)) {
                staging.file().writeBytes(new byte[] {1, 2});
                var pending = staging.path();
                var result = run(cache, new CachePruner(cache), true);
                assertTrue(result.completed(), result.toString());
                assertEquals(0, result.convertedReclaimed() + result.bakeReclaimed());
                assertTrue(Files.exists(pending));
                present(Pool.BAKE, lazy.file().location());
                present(Pool.CONVERTED, active.location());
                assertTrue(opened.size() > 0);
            }
        }
    }

    @Test
    void cancelledRunningAndQueuedOptionalWorkKeepTheirDiskInputs() throws Exception {
        authorities(true);
        var runningInput = bake(1, BakeKind.GEOMETRY, "old-schema", "9.0.0", identity(1));
        var queuedInput = bake(2, BakeKind.ANIMATION, "old-schema", "9.0.0", identity(2));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var cache = new AtomicSharedCache(temp)) {
            var work = cache.derivedWork();
            assertTrue(cache.bakeIndex().find(runningInput.key()).isPresent());
            var running = work.submit(gate -> {
                entered.countDown();
                boolean waiting = true;
                while (waiting) {
                    try {
                        release.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                    }
                }
            });
            assertNotNull(running);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertTrue(cache.bakeIndex().find(queuedInput.key()).isPresent());
                assertNotNull(work.submit(gate -> gate.commit(() -> {
                })));
                assertTrue(running.cancel());
                assertEquals(2, work.snapshot().jobs());
                var result = run(cache, new CachePruner(cache), true);
                assertTrue(result.completed(), result.toString());
                assertEquals(0, result.bakeReclaimed());
                present(Pool.BAKE, runningInput.file().location(), queuedInput.file().location());
            } finally {
                release.countDown();
                work.awaitIdle();
            }
        }
    }

    @Test
    void partialFailurePreservesOwnershipAndReportsBothPoolProgress() throws Exception {
        authorities(true);
        var converted = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
        var bake = bake(2, BakeKind.GEOMETRY, AbiVersion.CACHE_GEOMETRY, "", identity(2));
        try (var cache = new AtomicSharedCache(temp)) {
            var failure = new IOException("injected physical delete failure");
            var pruner = new CachePruner(cache, (phase, pool, location) -> {
                if (phase == CachePruner.Phase.DELETE && pool == Pool.BAKE)
                    throw failure;
            });
            var result = run(cache, pruner, false);
            assertFalse(result.completed());
            assertEquals(1, result.convertedReclaimed());
            assertEquals(0, result.bakeReclaimed());
            assertSame(failure, result.failure().orElseThrow().cause());
            absent(Pool.CONVERTED, converted.location());
            present(Pool.BAKE, bake.file().location());
            try (var token = NativeCancellation.create(); var db = NativeBakeIndex.open(temp.resolve("f"), token)) {
                assertEquals(1, db.pageObjects(OptionalLong.empty(), token).rows().size());
                assertTrue(db.lookupExact(bake.key(), token).rows().isEmpty());
            }
            assertEquals(1, run(cache, new CachePruner(cache), false).bakeReclaimed());
        }
    }

    @Test
    void accessDenialReportsTheFailedRoundAndKeepsOwnership() throws Exception {
        authorities(true);
        var object = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
        try (var cache = new AtomicSharedCache(temp)) {
            var denied = new SecurityException("injected access denial");
            var pruner = new CachePruner(cache, (phase, pool, location) -> {
                if (phase == CachePruner.Phase.DELETE)
                    throw denied;
            });
            var result = run(cache, pruner, false);
            assertEquals(CachePruner.Status.FAILED, result.status());
            assertSame(denied, result.failure().orElseThrow().cause().getCause());
            assertEquals(0, result.convertedReclaimed());
            present(Pool.CONVERTED, object.location());
            assertEquals(1, run(cache, new CachePruner(cache), false).convertedReclaimed());
        }
    }

    @Test
    void allocatorReconstructionAvoidsUnfinishedOwnershipAcrossReopen() throws Exception {
        var base = temp;
        for (var pool : List.of(Pool.CONVERTED, Pool.BAKE)) {
            temp = base.resolve(pool.directory());
            authorities(true);
            var old = pool == Pool.CONVERTED ? converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false).location()
                : bake(1, BakeKind.GEOMETRY, AbiVersion.CACHE_GEOMETRY, "", identity(1)).file().location();
            try (var cache = new AtomicSharedCache(temp)) {
                var pruner = new CachePruner(cache, (phase, selectedPool, location) -> {
                    if (phase == CachePruner.Phase.FORGET)
                        throw new IOException("injected ownership cleanup failure");
                });
                assertEquals(CachePruner.Phase.FORGET, run(cache, pruner, false).failure().orElseThrow().phase());
                absent(pool, old);
            }
            // No allocator survives. A fresh owner must avoid the old ownership even without a physical file.
            Path published;
            try (var cache = new AtomicSharedCache(temp); var staging = cache.reserve(pool)) {
                assertEquals(2, staging.location().fileId());
                staging.file().writeBytes(new byte[] {7, 8, 9});
                published = staging.publish(file -> true, AtomicSharedCache.AtomicReplacement::run);
                var result = run(cache, new CachePruner(cache), false);
                assertTrue(result.completed(), result.toString());
                assertEquals(1, result.convertedReclaimed() + result.bakeReclaimed());
                assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(published));
            }
            try (var cache = new AtomicSharedCache(temp)) {
                assertTrue(run(cache, new CachePruner(cache), false).completed());
                assertArrayEquals(new byte[] {7, 8, 9}, Files.readAllBytes(published));
            }
        }
        temp = base;
    }

    @Test
    void observeRevokeAndForgetFailuresNeverBecomeSuccessfulRounds() throws Exception {
        for (var stop : List.of(CachePruner.Phase.OBSERVE, CachePruner.Phase.REVOKE, CachePruner.Phase.FORGET)) {
            authorities(true);
            var object = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
            try (var cache = new AtomicSharedCache(temp)) {
                var pruner = new CachePruner(cache, (phase, pool, location) -> {
                    if (phase == stop)
                        throw new IOException("injected " + stop);
                });
                var result = run(cache, pruner, false);
                assertFalse(result.completed());
                assertEquals(stop, result.failure().orElseThrow().phase());
                assertEquals(stop != CachePruner.Phase.FORGET, Files.exists(path(Pool.CONVERTED, 1)));
                assertEquals(1, run(cache, new CachePruner(cache), false).convertedReclaimed());
                absent(Pool.CONVERTED, object.location());
            }
        }
    }

    @Test
    void sourcePublicationCannotEnterBetweenQualificationAndDeletion() throws Exception {
        authorities(true);
        var old = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
        try (var cache = new AtomicSharedCache(temp)) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var pruner = new CachePruner(cache, (phase, pool, location) -> {
                if (phase == CachePruner.Phase.DELETE && pool == Pool.CONVERTED) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException();
                    }
                }
            });
            var cleanup = CompletableFuture.supplyAsync(() -> {
                try {
                    return run(cache, pruner, false);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var publication = CompletableFuture.runAsync(() -> {
                try {
                    cache.coordinator().acquire(() -> {
                        try {
                            source("scanned", "custom/new", old.identity());
                        } catch (Exception failure) {
                            throw new IOException(failure);
                        }
                        return null;
                    });
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> publication.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(cleanup.get(10, TimeUnit.SECONDS).completed());
            publication.get(10, TimeUnit.SECONDS);
            absent(Pool.CONVERTED, old.location());
        }
    }

    @Test
    void initializationRequiresNoBakeDatabaseAndDoesNotAdoptUnknownFiles() throws Exception {
        var unknown = path(Pool.BAKE, 55);
        Files.createDirectories(unknown.getParent());
        Files.writeString(unknown, "unknown");
        try (var cache = new AtomicSharedCache(temp)) {
            var pruner = new CachePruner(cache);
            assertThrows(IllegalStateException.class, pruner::initializeReferences);
            assertTrue(cache.coordinator().cleanup(pruner::initializeReferences, false));
            assertTrue(run(cache, pruner, false).completed());
            assertEquals("unknown", Files.readString(unknown));
            assertFalse(Files.exists(temp.resolve("f/bake.sqlite")));
            try (var token = NativeCancellation.create(); var db = NativeBakeIndex.open(temp.resolve("f"), token)) {
            }
            assertFalse(cache.coordinator().cleanup(pruner::initializeReferences, true));
        }
    }

    @Test
    void lastReferenceBatchFailureStillPrecedesAllDeletion() throws Exception {
        authorities(true);
        var object = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
        for (int id = 1; id <= 65; id++)
            bake(id, BakeKind.ANIMATION, "old-schema", "9.0.0", identity(id));
        try (var cache = new AtomicSharedCache(temp)) {
            var observations = new java.util.concurrent.atomic.AtomicInteger();
            var pruner = new CachePruner(cache, (phase, pool, location) -> {
                if (phase == CachePruner.Phase.OBSERVE && pool == Pool.BAKE && observations.incrementAndGet() == 2) {
                    try (var jdbc = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("f/remote.sqlite"));
                        var statement = jdbc.createStatement()) {
                        assertEquals(1, statement.executeUpdate("DELETE FROM coverage WHERE id=1"));
                    } catch (SQLException failure) {
                        throw new AssertionError(failure);
                    }
                }
            });
            var result = run(cache, pruner, true);
            assertEquals(2, observations.get());
            assertFalse(result.completed());
            assertEquals(CachePruner.Phase.OBSERVE, result.failure().orElseThrow().phase());
            assertEquals(0, result.convertedReclaimed());
            assertEquals(0, result.bakeReclaimed());
            present(Pool.CONVERTED, object.location());
            for (int id = 1; id <= 65; id++)
                assertTrue(Files.exists(path(Pool.BAKE, id)));
        }
    }

    @Test
    void internalLinksAndOtherPoolsNeverGrantDeletionAuthority() throws Exception {
        authorities(true);
        var object = bake(1, BakeKind.ANIMATION, "old-schema", "9.0.0", identity(1));
        var source = Files.writeString(temp.resolve("source"), "source-bytes");
        Files.delete(path(Pool.BAKE, 1));
        Files.createSymbolicLink(path(Pool.BAKE, 1), source);
        var snapshots = List.of(path(Pool.REMOTE, 2), path(Pool.PREVIEW, 3), temp.resolve("converted/old.mxc"));
        for (var snapshot : snapshots) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, "unchanged");
        }
        try (var cache = new AtomicSharedCache(temp)) {
            var result = run(cache, new CachePruner(cache), true);
            assertFalse(result.completed());
            assertEquals(CachePruner.Phase.DELETE, result.failure().orElseThrow().phase());
            assertEquals("source-bytes", Files.readString(source));
            assertTrue(Files.isSymbolicLink(path(Pool.BAKE, object.file().location().fileId())));
            for (var snapshot : snapshots)
                assertEquals("unchanged", Files.readString(snapshot));
        }
    }

    @Test
    void incompleteObservationAndCancellationDoNotReportCompletion() throws Exception {
        authorities(true);
        var object = converted(1, "scanned", AbiVersion.MODEL_SCHEMA, false);
        try (var cache = new AtomicSharedCache(temp)) {
            var pruner = new CachePruner(cache);
            var incomplete = cache.coordinator().cleanup(() -> pruner.prune(Set.of(), false), null);
            assertEquals(CachePruner.Status.INCOMPLETE_OBSERVATION, incomplete.status());
            var cancelled = new CachePruner(cache, (phase, pool, location) -> {
                throw new java.util.concurrent.CancellationException("injected cancellation");
            });
            assertThrows(java.util.concurrent.CancellationException.class, () -> run(cache, cancelled, false));
            present(Pool.CONVERTED, object.location());
            assertTrue(cache.coordinator().registered());
        }
    }

    @Test
    void abiPolicyRetainsTheExistingStableAndDevelopmentRulesWithoutRendererQueries() {
        assertEquals(CachePruner.AbiSupport.SUPPORTED, CachePruner.abiSupport("1.2", "1.2.0"));
        assertEquals(CachePruner.AbiSupport.UNSUPPORTED, CachePruner.abiSupport("1.2.0-dev", "1.2.0-DEV"));
        assertEquals(CachePruner.AbiSupport.SUPPORTED, CachePruner.abiSupport("1.2.0-dev", "1.2.0-dev"));
        assertEquals(CachePruner.AbiSupport.UNKNOWN, CachePruner.abiSupport("1.2.0", ""));
        assertEquals(CachePruner.AbiSupport.UNSUPPORTED, CachePruner.abiSupport("1.2.0", "9.0.0"));
        assertEquals(CachePruner.AbiSupport.SUPPORTED,
            CachePruner.abiSupport(BakeKind.ANIMATION, AbiVersion.CACHE_ANIMATION));
    }

    private CachePruner.Result run(AtomicSharedCache cache, CachePruner pruner, boolean all) throws IOException {
        return cache.coordinator().cleanup(() -> pruner.prune(Set.of("scanned"), all), null);
    }

    private void authorities(boolean complete) throws Exception {
        Files.createDirectories(temp.resolve("f"));
        try (var token = NativeCancellation.create(); var catalog = NativeCacheCatalog.open(temp.resolve("f"), token);
            var remote = NativeCacheRemote.open(temp.resolve("f"), token)) {
            assertTrue(catalog.setReferencesComplete(complete, token).committed());
            assertTrue(remote.setReferencesComplete(complete, token).committed());
        }
    }

    private void source(String scope, String source, ModelFileIdentity identity) throws Exception {
        var empty = ArrayBuffer.move(new byte[0]).shareArray();
        try (var token = NativeCancellation.create(); var db = NativeCacheCatalog.open(temp.resolve("f"), token)) {
            assertTrue(db.publishSource(scope, new Source(source, identity, Optional.empty(), empty, empty), token)
                .committed());
            assertTrue(db.setScopeComplete(scope, true, token).committed());
        }
    }

    private Converted converted(long id, String scope, String schema, boolean linked) throws Exception {
        var object = new Converted(new Location(id, UUID.randomUUID()), scope, schema, identity((int)id), true);
        var empty = ArrayBuffer.move(new byte[0]).shareArray();
        Files.createDirectories(path(Pool.CONVERTED, id).getParent());
        Files.writeString(path(Pool.CONVERTED, id), "converted-" + id);
        try (var token = NativeCancellation.create(); var db = NativeCacheCatalog.open(temp.resolve("f"), token)) {
            assertTrue(db.publishSource(scope,
                new Source("custom/" + id, object.identity(), Optional.of(object), empty, empty), token).committed());
            assertTrue(db.setScopeComplete(scope, true, token).committed());
            if (!linked)
                assertTrue(db.finishScan(scope, List.of("custom/" + id), token).committed());
        }
        return object;
    }

    private BakeObject bake(long id, BakeKind kind, String schema, String abi, ModelFileIdentity identity)
        throws Exception {
        var object =
            new BakeObject(new File(new Location(id, UUID.randomUUID()), ArrayBuffer.move(new byte[0]).shareArray()),
                new BakeKey(new Partition(kind, schema, identity.containerId(), "binding", "purpose"), "input-" + id,
                    abi, "", "", "", ""));
        Files.createDirectories(path(Pool.BAKE, id).getParent());
        Files.writeString(path(Pool.BAKE, id), "bake-" + id);
        try (var token = NativeCancellation.create(); var db = NativeBakeIndex.open(temp.resolve("f"), token)) {
            assertTrue(db.publish(object, token).committed());
        }
        return object;
    }

    private static ModelFileIdentity identity(int value) {
        var bytes = new byte[Hash256.SIZE];
        java.nio.ByteBuffer.wrap(bytes).putInt(value);
        return new ModelFileIdentity(ModelId.fromHash(new Hash256(bytes)), new Hash256(bytes));
    }

    private Path path(Pool pool, long id) {
        return temp.resolve("f").resolve(pool.directory())
            .resolve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.formatFileId(id));
    }

    private void present(Pool pool, Location... locations) {
        for (var location : locations)
            assertTrue(Files.exists(path(pool, location.fileId())), location.toString());
    }

    private void absent(Pool pool, Location... locations) {
        for (var location : locations)
            assertFalse(Files.exists(path(pool, location.fileId())), location.toString());
    }
}
