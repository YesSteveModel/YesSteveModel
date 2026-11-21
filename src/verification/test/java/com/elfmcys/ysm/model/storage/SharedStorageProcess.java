package com.elfmcys.ysm.model.storage;

import static com.elfmcys.ysm.model.storage.TestNamedRoamingSnapshots.named;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Separate JVM endpoint; readiness files make the competing operations independently observable. */
public final class SharedStorageProcess {
    public static void main(String[] args) throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var mode = args[0];
        if (mode.equals("deep-round")) {
            deepRound(args);
            return;
        }
        if (mode.equals("maintenance")) {
            maintenance(args);
            return;
        }
        if (mode.equals("region")) {
            region(args);
            return;
        }
        if (mode.startsWith("sqlite-")) {
            sqlite(args);
            return;
        }
        var cacheRoot = Path.of(args[1]);
        var scope = ConvertedScope.of(Path.of(args[2]), args[3]);
        var cache = new AtomicSharedCache(cacheRoot);
        var ready = Path.of(args[4]);
        if (mode.equals("raw-reload")) {
            try (cache) {
                var root = new com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity(
                    com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS, Path.of(args[2]));
                var sourceName = args.length > 6 ? args[6] : "alex";
                var sourceKind = args.length > 7 ? com.elfmcys.ysm.model.catalog.source.ModelSourceKind.valueOf(args[7])
                    : com.elfmcys.ysm.model.catalog.source.ModelSourceKind.CURRENT_RAW_DIRECTORY;
                var key = new com.elfmcys.ysm.model.catalog.source.ModelSourceKey(root,
                    new com.elfmcys.ysm.model.domain.ModelPath(sourceName), sourceKind);
                var indexes = new ConvertedSourceIndexStore(scope, cache);
                var resolver = new com.elfmcys.ysm.model.catalog.source.ModelSourceResolver(
                    new com.elfmcys.ysm.model.catalog.RawModelImporter(
                        com.elfmcys.ysm.format.parser.DefaultAnimationFilter.keepAll()),
                    indexes, new ConvertedObjectStore(cache));
                try (var recording = new jdk.jfr.Recording()) {
                    if (args.length > 5) {
                        recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO).withStackTrace();
                        recording.enable("jdk.FileWrite").withThreshold(java.time.Duration.ZERO).withStackTrace();
                        recording.start();
                    }
                    var started = System.nanoTime();
                    var resolved = resolver.resolve(com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery
                        .observe(key, Path.of(args[2]).resolve(sourceName))).convertedIndexEntry().orElseThrow();
                    Files.writeString(ready, resolved.instance() + " " + (System.nanoTime() - started));
                    if (args.length > 5) {
                        recording.stop();
                        recording.dump(Path.of(args[5]));
                    }
                }
            }
        } else if (mode.equals("consumer")) {
            try (var consumers = new CacheRootCoordinator(cacheRoot)) {
                Files.writeString(ready.resolveSibling(ready.getFileName() + ".attempt"), "attempting");
                consumers.register();
                Files.writeString(ready, "registered");
                System.in.read();
                if (args.length > 5)
                    Files.writeString(ready.resolveSibling(ready.getFileName() + ".read"),
                        Files.readString(Path.of(args[5])));
            }
        } else if (mode.equals("flat-producer")) {
            try (cache; var staging = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.CONVERTED)) {
                var payload = args[5].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                staging.file().writeBytes(payload);
                Files.writeString(ready, Long.toUnsignedString(staging.location().fileId()));
                if (args[6].equals("hold"))
                    System.in.read();
                staging.publish(file -> Arrays.equals(payload, file.readAllBytes()),
                    AtomicSharedCache.AtomicReplacement::run);
            }
        } else if (mode.equals("cleanup-hold")) {
            try (var consumers = new CacheRootCoordinator(cacheRoot)) {
                consumers.register();
                consumers.cleanup(() -> {
                    Files.writeString(ready, "exclusive");
                    System.in.read();
                    return true;
                }, false);
            }
        } else if (mode.equals("prune")) {
            try (var consumers = new CacheRootCoordinator(cacheRoot)) {
                consumers.register();
                Files.writeString(ready, consumers.cleanup(() -> CacheRootCoordinator.PruneResult.COMPLETED,
                    CacheRootCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER).name());
            }
        } else if (mode.equals("index")) {
            var indexes = new ConvertedSourceIndexStore(scope, cache);
            Files.writeString(ready, "ready");
            await(Path.of(args[5]));
            var entry = StorageContainerFixture.produce(scope, cache, args[6] + "/same.mxc");
            indexes.publish(entry);
            indexes.replaceScopes(Set.of(args[6]), List.of(entry));
        } else if ((mode.equals("write") || mode.equals("derived"))) {
            var target = Path.of(args[5]);
            var payload = Files.readAllBytes(Path.of(args[6]));
            AtomicSharedCache.CacheWriter writer = temporary -> {
                temporary.writeBytes(Arrays.copyOf(payload, Math.max(1, payload.length / 2)));
                Files.writeString(ready, "staged");
                if (args[7].equals("hold"))
                    System.in.read();
                temporary.position(0).writeBytes(payload);
            };
            DerivedStorageWork.Work operation = gate -> {
                try (var staging = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.REMOTE)) {
                    writer.write(staging.file());
                    staging.publish(file -> Arrays.equals(file.readAllBytes(), payload), move -> gate.commit(() -> {
                        move.run();
                        cache.remoteIndex().publish(com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.CHUNK,
                            "process-test", staging.location());
                    }));
                }
            };
            if (mode.equals("derived")) {
                var job = cache.derivedWork().submit(operation);
                cache.derivedWork().awaitIdle();
                if (job.outcome() != DerivedStorageWork.Outcome.SUCCEEDED)
                    throw new IllegalStateException("Derived write failed", job.failure());
            } else {
                var job = cache.derivedWork().submit(operation);
                cache.derivedWork().awaitIdle();
                if (job.outcome() != DerivedStorageWork.Outcome.SUCCEEDED)
                    throw new IllegalStateException("Write failed", job.failure());
            }
            cache.close();
        }
    }

    private static void maintenance(String[] args) throws Exception {
        var game = Path.of(args[1]);
        var home = Path.of(args[2]);
        var now = Long.parseLong(args[3]);
        var ready = Path.of(args[4]);
        var action = args[5];
        var paths = com.elfmcys.ysm.AssetPaths.discover(game, game.resolve("config"), game.resolve("builtin"),
            java.util.Map.of("YSM_HOME", home.toString()), com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        if (!paths.globalEnabled())
            throw new AssertionError(paths.diagnostics());
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(now))) {
            if (action.startsWith("command")) {
                var results = storage.pruneNext(action.equals("command-all")).toCompletableFuture().get();
                if (results.stream().anyMatch(result -> !result.succeeded()))
                    throw new AssertionError(results);
                Files.writeString(ready, "RESET " + CacheMaintenanceTest.state(storage.remote().cache()));
                return;
            }
            if (action.equals("hold")) {
                Files.writeString(ready, "REGISTERED");
                System.in.read();
                return;
            }
            if (action.equals("hold-complete")) {
                var owner = new CacheMaintenance(storage.remote().cache(), storage.remote().pruner(), false,
                    CacheMaintenanceTest.clock(now), (phase, state) -> {
                        if (phase == CacheMaintenance.Phase.BEFORE_COMPLETE) {
                            Files.writeString(ready, "EXCLUSIVE " + state);
                            System.in.read();
                        }
                    });
                owner.initialize();
                var scope = storage.scopes().stream().filter(value -> value.region() == storage.remote()).findFirst()
                    .orElseThrow().indexes().key();
                var result = owner.attempt(Set.of(scope));
                Files.writeString(ready.resolveSibling(ready.getFileName() + ".result"),
                    result.status() + " " + CacheMaintenanceTest.state(storage.remote().cache()));
                return;
            }
            if (action.startsWith("pool-")) {
                var parts = action.split("-");
                var expectedPool = com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.valueOf(parts[1]);
                var paused = new java.util.concurrent.atomic.AtomicBoolean();
                var pruner = new CachePruner(storage.remote().cache(), (phase, pool, location) -> {
                    if (phase == CachePruner.Phase.DELETE && pool == expectedPool
                        && paused.compareAndSet(false, true)) {
                        Files.writeString(ready, "EXCLUSIVE " + pool);
                        System.in.read();
                        if (parts[2].equals("fail"))
                            throw new java.io.IOException("Injected pool deletion failure");
                    }
                });
                var owner = new CacheMaintenance(storage.remote().cache(), pruner, false,
                    CacheMaintenanceTest.clock(now), (phase, state) -> {
                    });
                owner.initialize();
                var scope = storage.scopes().stream().filter(value -> value.region() == storage.remote()).findFirst()
                    .orElseThrow().indexes().key();
                var result = owner.attempt(Set.of(scope));
                Files.writeString(ready.resolveSibling(ready.getFileName() + ".result"),
                    result.status() + " " + CacheMaintenanceTest.state(storage.remote().cache()));
                return;
            }
            var results = storage.finishStartupScan(CacheMaintenanceTest.roots(paths));
            Files.writeString(ready, "LOCAL " + results.get(storage.local()).status() + " "
                + CacheMaintenanceTest.state(storage.local().cache()) + "\nGLOBAL "
                + results.get(storage.remote()).status() + " " + CacheMaintenanceTest.state(storage.remote().cache()));
        }
    }

    private static void deepRound(String[] args) throws Exception {
        var game = Path.of(args[1]);
        var ready = Path.of(args[4]);
        var paths = com.elfmcys.ysm.AssetPaths.localOnly(game, game.resolve("config"), game.resolve("builtin"));
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(Long.parseLong(args[3])))) {
            var root = new com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS, paths.modelsRoot());
            var scope = storage.scope(root);
            var resolver = new com.elfmcys.ysm.model.catalog.source.ModelSourceResolver(
                new com.elfmcys.ysm.model.catalog.RawModelImporter(
                    com.elfmcys.ysm.format.parser.DefaultAnimationFilter.keepAll()),
                scope.indexes(), new ConvertedObjectStore(scope.region().cache()));
            var observation = com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery.observe(
                new com.elfmcys.ysm.model.catalog.source.ModelSourceKey(root,
                    new com.elfmcys.ysm.model.domain.ModelPath("alex"),
                    com.elfmcys.ysm.model.catalog.source.ModelSourceKind.CURRENT_RAW_DIRECTORY),
                paths.modelsRoot().resolve("alex"));
            final String identity;
            try (var content = resolver.resolveMaterialized(observation).resolvedContent()) {
                identity = content.content().modelId().toString();
            }
            var cache = scope.region().cache();
            int stagingLength;
            try (var staging = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.BAKE)) {
                staging.file().writeBytes(new byte[] {1, 2, 3});
                stagingLength = staging.path().toString().length();
                if (!Files.isRegularFile(staging.path()))
                    throw new AssertionError("Staging was not physically created");
            }
            CacheMaintenanceTest.seedObjects(cache.root(), scope.indexes().key());
            var result = storage.finishStartupScan(CacheMaintenanceTest.roots(paths)).get(storage.local());
            if (result.status() != CacheMaintenance.Status.RECORDED)
                throw new AssertionError(result);
            for (var file : List.of("c/0000000000000064", "b/0000000000000065", "b/0000000000000066"))
                if (Files.exists(cache.layoutRoot().resolve(file)))
                    throw new AssertionError("Prune retained an obsolete schema: " + file);
            int maximum;
            try (var files = Files.walk(cache.root())) {
                maximum = files.mapToInt(file -> file.toString().length()).max().orElseThrow();
            }
            Files.writeString(ready, identity + " staging=" + stagingLength + " maximum=" + maximum + " game="
                + game.toString().length() + " " + CacheMaintenanceTest.state(cache));
        }
    }

    private static void region(String[] args) throws Exception {
        var home = Path.of(args[1]);
        var game = Path.of(args[2]);
        var ready = Path.of(args[4]);
        var action = args[5];
        var fallback = Boolean.parseBoolean(args[6]);
        var paths = com.elfmcys.ysm.AssetPaths.discover(game, game.resolve("config"), game.resolve("builtin"),
            java.util.Map.of("YSM_HOME", home.toString()), com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        org.junit.jupiter.api.Assertions.assertEquals(!fallback, paths.globalEnabled(), paths.diagnostics().toString());
        try (var storage = ModelStorageInfra.open(paths)) {
            var roots = new java.util.ArrayList<com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity>();
            roots.add(new com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS, paths.modelsRoot()));
            roots.add(new com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.BUILTIN, paths.builtinModelsResource()));
            paths.global().ifPresent(global -> roots.add(new com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.MODELS, global.modelsRoot())));
            storage.registerCaches();
            var scope = storage.scope(roots.get(fallback ? 0 : 2));
            var object = scope.region().root().resolve("f/c/0000000000000000");
            var u = new java.util.UUID(0, 1);
            var v = new java.util.UUID(0, 2);
            var model = com.elfmcys.ysm.model.domain.PublishedModelId.parse("owner/region@1.0.0");
            if (action.equals("prune")) {
                Files.writeString(ready, scope.consumers().cleanup(() -> CacheRootCoordinator.PruneResult.COMPLETED,
                    CacheRootCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER).name());
                return;
            }
            if (action.equals("write")) {
                PlayerPreferencesTest.committed(storage.preferences(),
                    storage.preferences().writeRoaming(u, model, PlayerPreferencesTest.payload(11)));
                PlayerPreferencesTest.committed(storage.preferences(),
                    storage.preferences().writeRoaming(v, model, PlayerPreferencesTest.payload(22)));
                var usage = storage.remote().cache().remoteIndex()
                    .submit((db, token) -> db.maxUsage(new Hash256(new byte[32]), 102, token)).toCompletableFuture()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertTrue(usage.committed(), usage.toString());
            } else if (action.equals("hold")) {
                Files.createDirectories(object.getParent());
                Files.writeString(object, "region-consumer");
                Files.writeString(ready, object.toString());
                System.in.read();
                org.junit.jupiter.api.Assertions.assertEquals("region-consumer", Files.readString(object));
                Files.writeString(ready.resolveSibling(ready.getFileName() + ".read"), "region-consumer");
            }
            if (!action.equals("empty")) {
                org.junit.jupiter.api.Assertions.assertEquals(named(PlayerPreferencesTest.payload(11)),
                    named(PlayerPreferencesTest.found(storage.preferences(),
                        storage.preferences().readRoaming(u, model, Set.of("value")))));
                org.junit.jupiter.api.Assertions.assertEquals(named(PlayerPreferencesTest.payload(22)),
                    named(PlayerPreferencesTest.found(storage.preferences(),
                        storage.preferences().readRoaming(v, model, Set.of("value")))));
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(
                    com.elfmcys.ysm.natives.storage.StorageResult.Outcome.MISSING,
                    PlayerPreferencesTest
                        .await(storage.preferences(), storage.preferences().readRoaming(u, model, Set.of("value")))
                        .outcome());
            }
            var usage = storage.remote().cache().remoteIndex()
                .submit((db, token) -> db.readUsage(new Hash256(new byte[32]), token)).toCompletableFuture()
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (action.equals("empty"))
                org.junit.jupiter.api.Assertions
                    .assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.MISSING, usage.outcome());
            else
                org.junit.jupiter.api.Assertions.assertEquals(102L, usage.rows().get(0));
            org.junit.jupiter.api.Assertions.assertEquals(!fallback, paths.globalEnabled());
            if (!action.equals("hold"))
                Files.writeString(ready, "region-passed");
        }
    }

    private static void sqlite(String[] args) throws Exception {
        var root = Path.of(args[1]);
        var ready = Path.of(args[4]);
        if (args[0].equals("sqlite-abandon")) {
            sqliteAbandon(root, ready, Path.of(args[5]), java.util.UUID.fromString(args[6]),
                PublishedModelId.parse(args[7]));
            return;
        }
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create()) {
            Files.createDirectories(root);
            if (args[0].equals("sqlite-usage")) {
                try (var work = new DatabaseWork(); var cache = new AtomicSharedCache(root)) {
                    cache.databaseWork(work);
                    cache.coordinator().register();
                    var buffer = new PlayerPreferences(root, work).writes();
                    Files.writeString(ready, "ready");
                    await(Path.of(args[5]));
                    buffer.offerUsage(cache.remoteIndex(), new Hash256(new byte[32]), Long.parseLong(args[6]));
                    buffer.tick(0);
                    var result = buffer.closeAndDrain();
                    if (result.size() != 1 || !result.get(0).storage().committed())
                        throw new IllegalStateException(result.toString());
                }
            } else if (args[0].equals("sqlite-preferences")) {
                try (var work = new DatabaseWork()) {
                    var preferences = new PlayerPreferences(root, work);
                    var player = java.util.UUID.fromString(args[6]);
                    var identity = com.elfmcys.ysm.model.domain.PublishedModelId.parse(args[7]);
                    var number = Integer.parseInt(args[8]);
                    var action = args[9];
                    Files.writeString(ready, "ready");
                    await(Path.of(args[5]));
                    if (action.equals("write")) {
                        for (var index = 0; index < 16; index++)
                            PlayerPreferencesTest.committed(preferences,
                                preferences.writeRoaming(player, identity, PlayerPreferencesTest.payload(number)));
                        PlayerPreferencesTest.committed(preferences,
                            preferences.writeRoaming(player,
                                com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT,
                                PlayerPreferencesTest.payload(number + 1)));
                        PlayerPreferencesTest.committed(preferences,
                            preferences.writeFavourite(player, ModelId.parse(args[7]), number == 22));
                        PlayerPreferencesTest.committed(preferences, preferences.writeSelection(player,
                            new PlayerPreferences.LocalSelection(identity, "texture" + number)));
                    } else if (action.equals("reject")) {
                        var result = PlayerPreferencesTest.await(preferences,
                            preferences.writeRoaming(player, identity, PlayerPreferencesTest.payload(number)));
                        if (result.failure() != com.elfmcys.ysm.natives.storage.StorageResult.Failure.CONTENT
                            || result.committed())
                            throw new IllegalStateException("Invalid existing record was overwritten: " + result);
                    } else if (action.equals("restore")) {
                        var result = PlayerPreferencesTest.await(preferences,
                            preferences.restoreRoaming(player, identity, Set.of("value")).result());
                        if (!result.succeeded() || result.rows().size() != 1
                            || !Set.of(11f, 22f).contains(named(result.rows().get(0)).get("value")))
                            throw new IllegalStateException(result.toString());
                    } else {
                        if (!named(PlayerPreferencesTest.payload(number)).equals(named(PlayerPreferencesTest
                            .found(preferences, preferences.readRoaming(player, identity, Set.of("value")))))
                            || !named(PlayerPreferencesTest.payload(number + 1))
                                .equals(named(PlayerPreferencesTest.found(preferences, preferences.readRoaming(player,
                                    com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, Set.of("value")))))
                            || PlayerPreferencesTest.found(preferences,
                                preferences.readFavourite(player, ModelId.parse(args[7]))) != (number == 22)
                            || !new PlayerPreferences.LocalSelection(identity, "texture" + number)
                                .equals(PlayerPreferencesTest.found(preferences, preferences.readSelection(player))))
                            throw new IllegalStateException("Shared preference identity or complete value mismatch");
                    }
                }
            } else if (args[0].equals("sqlite-initialize")) {
                Files.writeString(ready, "ready");
                await(Path.of(args[5]));
                var resultFile = ready.resolveSibling(ready.getFileName() + ".result");
                try (var db = com.elfmcys.ysm.natives.storage.NativePlayerData.open(root, cancelled)) {
                    var key = com.elfmcys.ysm.natives.storage.NativePlayerData.PreferenceKey.roaming(
                        java.util.UUID.fromString(args[6]),
                        com.elfmcys.ysm.model.domain.PublishedModelId.parse(args[7]));
                    var result = db.putPreference(key,
                        com.elfmcys.ysm.buffer.ArrayBuffer.borrow(new byte[] {(byte)Integer.parseInt(args[8])}), 17,
                        cancelled);
                    if (!result.committed())
                        throw new IllegalStateException(result.toString());
                    Files.writeString(resultFile, "COMMITTED");
                } catch (com.elfmcys.ysm.natives.storage.StorageResult.OpenException failure) {
                    var category = failure.result().failure();
                    if (category != com.elfmcys.ysm.natives.storage.StorageResult.Failure.RESOURCE_BUSY
                        && category != com.elfmcys.ysm.natives.storage.StorageResult.Failure.INCOMPATIBLE)
                        throw failure;
                    Files.writeString(resultFile, category.name());
                }
            } else if (args[0].equals("sqlite-writer")) {
                try (var db = com.elfmcys.ysm.natives.storage.NativePlayerData.open(root, cancelled)) {
                    Files.writeString(ready, "ready");
                    await(Path.of(args[5]));
                    var key = com.elfmcys.ysm.natives.storage.NativePlayerData.PreferenceKey.roaming(
                        java.util.UUID.fromString(args[6]),
                        com.elfmcys.ysm.model.domain.PublishedModelId.parse(args[7]));
                    for (int index = 0; index < 32; index++) {
                        var result = db.putPreference(key, com.elfmcys.ysm.buffer.ArrayBuffer
                            .borrow(new byte[] {(byte)Integer.parseInt(args[8]), (byte)index}), index, cancelled);
                        if (!result.committed())
                            throw new IllegalStateException(result.toString());
                    }
                }
            } else {
                try (var registration = com.elfmcys.ysm.natives.storage.NativeCoordination.open(root, cancelled)) {
                    if (args[0].equals("sqlite-cleaner")) {
                        var result = registration.beginCleanup(cancelled);
                        Files.writeString(ready, result.outcome().name());
                        if (result.outcome() == com.elfmcys.ysm.natives.storage.StorageResult.Outcome.FOUND) {
                            System.in.read();
                            if (!registration.endCleanup().committed())
                                throw new IllegalStateException("Restore failed");
                        }
                    } else {
                        Files.writeString(ready, "registered");
                        System.in.read();
                        if (args.length > 5)
                            Files.writeString(ready.resolveSibling(ready.getFileName() + ".read"),
                                Files.readString(Path.of(args[5])));
                    }
                }
            }
        }
    }

    private static void sqliteAbandon(Path root, Path ready, Path go, java.util.UUID player, PublishedModelId identity)
        throws Exception {
        var work = new DatabaseWork();
        var preferences = new PlayerPreferences(root, work);
        var buffer = preferences.writes();
        var committed = preferences.writeRoaming(player, identity,
            TestNamedRoamingSnapshots.fromMap(java.util.Map.of("first", 11f, "second", 13f)));
        buffer.tick(0);
        buffer.tick(1_000_000_000L).orElseThrow().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (!committed.result().toCompletableFuture().get().committed())
            throw new IllegalStateException("Initial complete preference was not committed");

        var pending = preferences.writeRoaming(player, identity,
            TestNamedRoamingSnapshots.fromMap(java.util.Map.of("first", 22f, "second", 26f)));
        if (buffer.tick(1_000_000_000L).isPresent() || pending.admission() != StorageWriteBuffer.Admission.ACCEPTED
            || pending.result().toCompletableFuture().isDone())
            throw new IllegalStateException("Recent preference must remain accepted and uncommitted");

        var entered = new CountDownLatch(3);
        var neverReleased = new CountDownLatch(1);
        Runnable blockedWorker = () -> {
            if (!Thread.currentThread().isDaemon())
                throw new IllegalStateException("Non-daemon YSM worker: " + Thread.currentThread().getName());
            entered.countDown();
            try {
                neverReleased.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Abandoned worker was interrupted", failure);
            }
        };
        var scheduler = new ClientResourceScheduler(2);
        scheduler.dispatch(WorkPriority.LOCAL_PLAYER).execute(blockedWorker);
        scheduler.dispatch(WorkPriority.BACKGROUND).execute(blockedWorker);
        work.submitTask(cancelled -> {
            blockedWorker.run();
            return new StorageResult<Void>(StorageResult.Outcome.FOUND, StorageResult.Failure.NONE, false, List.of());
        });
        if (!entered.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("YSM workers did not reach the abandonment barrier");
        Files.writeString(ready, "COMMITTED BUFFERED 3_DAEMON_WORKERS");
        await(go);
        if (pending.result().toCompletableFuture().isDone())
            throw new IllegalStateException("Recent preference completed without another owner tick");
        // Natural main return must abandon the blocked workers and open database without a close or flush.
    }

    private static void await(Path path) throws Exception {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(path)) {
            if (System.nanoTime() > deadline)
                throw new IllegalStateException("Process barrier timed out");
            Thread.sleep(2);
        }
    }
}
