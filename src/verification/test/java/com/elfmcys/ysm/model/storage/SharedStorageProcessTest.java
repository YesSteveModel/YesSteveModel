package com.elfmcys.ysm.model.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedStorageProcessTest {
    @TempDir
    Path temp;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"CONVERTED,complete", "BAKE,complete", "CONVERTED,fail", "BAKE,fail",
        "CONVERTED,crash", "BAKE,crash"})
    void bothPoolsKeepAdmissionClosedThroughCompletionFailureAndCrash(String pool, String ending) throws Exception {
        var game = temp.resolve("game");
        var home = temp.resolve("home");
        var paths = com.elfmcys.ysm.AssetPaths.discover(game, game.resolve("config"), game.resolve("builtin"),
            java.util.Map.of("YSM_HOME", home.toString()), com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(1_800_000_000L))) {
            var scope = storage.scopes().stream().filter(value -> value.region() == storage.remote()).findFirst()
                .orElseThrow().indexes().key();
            CacheMaintenanceTest.seedObjects(home.resolve("cache"), scope);
            assertTrue(
                storage.pruneNext(true).toCompletableFuture().get().stream().allMatch(value -> value.succeeded()));
        }
        try (var cleaner =
            child("maintenance", game, home, "1800000001", "pool-cleaner", "pool-" + pool + "-" + ending)) {
            cleaner.ready();
            try (var waiter = child("consumer", home.resolve("cache"), temp.resolve("data"), "v1", "pool-waiter");
                var independent =
                    child("consumer", temp.resolve("other-cache"), temp.resolve("data"), "v1", "other-root")) {
                independent.ready();
                independent.process().getOutputStream().write(1);
                independent.process().getOutputStream().flush();
                independent.success();
                awaitFile(waiter.readyFile().resolveSibling("pool-waiter.ready.attempt"));
                Thread.sleep(100);
                assertFalse(Files.exists(waiter.readyFile()));
                if (ending.equals("crash")) {
                    cleaner.kill();
                } else {
                    cleaner.process().getOutputStream().write(1);
                    cleaner.process().getOutputStream().flush();
                    cleaner.success();
                    var result = Files.readString(cleaner.readyFile().resolveSibling("pool-cleaner.ready.result"));
                    assertTrue(result.startsWith(ending.equals("fail") ? "PRUNE_FAILED" : "RECORDED"), result);
                    assertEquals(ending.equals("fail"), result.contains("pendingAll=true"), result);
                }
                waiter.ready();
                waiter.process().getOutputStream().write(1);
                waiter.process().getOutputStream().flush();
                waiter.success();
            }
        }
        try (var recovery = child("maintenance", game, home, "1800000002", "pool-recovery", "round")) {
            recovery.ready();
            recovery.success();
            var result = Files.readString(recovery.readyFile());
            assertTrue(result.contains("GLOBAL " + (ending.equals("complete") ? "NOT_DUE" : "RECORDED")), result);
            assertFalse(result.contains("pendingAll=true"), result);
        }
        for (var file : java.util.List.of("c/0000000000000064", "b/0000000000000065", "b/0000000000000066"))
            assertFalse(Files.exists(home.resolve("cache/f").resolve(file)), file);
    }

    @Test
    void deepGameDirectoryRunsNativeColdWarmAndTwoPoolMaintenance() throws Exception {
        var game = temp;
        while (game.toString().length() < 175)
            game = game.resolve("deep-game-directory-0123456789");
        var paths = com.elfmcys.ysm.AssetPaths.localOnly(game, game.resolve("config"), game.resolve("builtin"));
        var source = Path.of(java.util.Objects
            .requireNonNull(getClass().getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI()).getParent();
        var raw = paths.modelsRoot().resolve("alex");
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var target = raw.resolve(source.relativize(file));
                if (Files.isDirectory(file))
                    Files.createDirectories(target);
                else
                    Files.copy(file, target);
            }
        }
        String identity = null;
        for (var now : java.util.List.of("1800000000", "1800259200")) {
            try (var process = child("deep-round", game, temp.resolve("unused"), now, "deep-" + now)) {
                process.ready();
                process.success();
                var result = Files.readString(process.readyFile());
                if (identity == null)
                    identity = result.split(" ")[0];
                else
                    assertEquals(identity, result.split(" ")[0]);
                assertTrue(result.contains("lastSuccessUtc=OptionalLong[" + now + "]"), result);
                System.out.println("Deep native storage " + result);
            }
        }
        try (var files = Files.walk(paths.gameCacheRoot().resolve("f"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var relative = paths.gameCacheRoot().resolve("f").relativize(file);
                if (relative.getNameCount() > 1)
                    assertTrue(file.getFileName().toString().matches("[0-9a-f]{16}"), relative.toString());
            }
        }
    }

    @Test
    void maintenanceDatesAndExactBoundarySurviveIndependentJvmRestarts() throws Exception {
        var game = temp.resolve("game");
        var home = temp.resolve("home");
        var cases = new long[] {1_800_000_000L, 1_800_259_199L, 1_799_999_000L, 1_800_259_200L, 1_800_518_401L};
        var expected = new String[] {"RECORDED", "NOT_DUE", "NOT_DUE", "RECORDED", "RECORDED"};
        for (var index = 0; index < cases.length; index++) {
            try (var child = child("maintenance", game, home, Long.toString(cases[index]), "date-" + index, "round")) {
                child.ready();
                child.success();
                var result = Files.readString(child.readyFile());
                assertTrue(result.contains("LOCAL " + expected[index]), result);
                assertTrue(result.contains("GLOBAL " + expected[index]), result);
            }
        }
    }

    @Test
    void persistentAllDeletesOtherSchemasAfterRestartAndThenReturnsToOrdinaryPolicy() throws Exception {
        var game = temp.resolve("game");
        var home = temp.resolve("home");
        var paths = com.elfmcys.ysm.AssetPaths.discover(game, game.resolve("config"), game.resolve("builtin"),
            java.util.Map.of("YSM_HOME", home.toString()), com.elfmcys.ysm.AssetDirectoryPlatform.Kind.WINDOWS, null);
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(1_800_000_000L))) {
            for (var scope : storage.scopes())
                CacheMaintenanceTest.seedObjects(scope.region().root(), scope.indexes().key());
        }
        var global = home.resolve("cache");
        try (var ordinary = child("maintenance", game, home, "1800000000", "ordinary", "round")) {
            ordinary.ready();
            ordinary.success();
        }
        assertFalse(Files.exists(paths.gameCacheRoot().resolve("f/c/0000000000000064")));
        for (var target : java.util.List.of("c/0000000000000064", "b/0000000000000065", "b/0000000000000066"))
            assertTrue(Files.exists(global.resolve("f").resolve(target)), target);
        for (var action : java.util.List.of("command-all", "command-ordinary")) {
            try (var command = child("maintenance", game, home, "1800000001", action, action)) {
                command.ready();
                command.success();
                assertTrue(Files.readString(command.readyFile()).contains("pendingAll=true"));
            }
        }
        try (var next = child("maintenance", game, home, "1800000002", "all-round", "round")) {
            next.ready();
            next.success();
            assertTrue(Files.readString(next.readyFile()).contains("GLOBAL RECORDED"));
            assertFalse(Files.readString(next.readyFile()).contains("pendingAll=true"));
        }
        for (var target : java.util.List.of("c/0000000000000064", "b/0000000000000065", "b/0000000000000066"))
            assertFalse(Files.exists(global.resolve("f").resolve(target)), target);
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(1_800_000_003L))) {
            var scope =
                storage.scopes().stream().filter(value -> value.region() == storage.remote()).findFirst().orElseThrow();
            CacheMaintenanceTest.seedObjects(global, scope.indexes().key());
        }
        try (var later = child("maintenance", game, home, "1800259203", "ordinary-again", "round")) {
            later.ready();
            later.success();
        }
        for (var target : java.util.List.of("c/0000000000000064", "b/0000000000000065", "b/0000000000000066"))
            assertTrue(Files.exists(global.resolve("f").resolve(target)), target);
    }

    @Test
    void peerSkipKeepsAllAndCrashBeforeDateCommitAllowsLaterStartup() throws Exception {
        var game = temp.resolve("game");
        var home = temp.resolve("home");
        try (var command = child("maintenance", game, home, "1800000000", "all", "command-all")) {
            command.ready();
            command.success();
        }
        try (var peer = child("maintenance", game, home, "1800000001", "peer", "hold")) {
            peer.ready();
            try (var round = child("maintenance", game, home, "1800000001", "peer-round", "round")) {
                round.ready();
                round.success();
                var result = Files.readString(round.readyFile());
                assertTrue(result.contains("GLOBAL PEER_PRESENT"), result);
                assertTrue(result.contains("pendingAll=true"), result);
                assertTrue(result.contains("lastSuccessUtc=OptionalLong.empty"), result);
            }
            peer.process().getOutputStream().write(1);
            peer.process().getOutputStream().flush();
            peer.success();
        }
        try (var crash = child("maintenance", game, home, "1800000002", "crash", "hold-complete")) {
            crash.ready();
            assertTrue(Files.readString(crash.readyFile()).startsWith("EXCLUSIVE"));
            crash.kill();
        }
        try (var next = child("maintenance", game, home, "1800000003", "after-crash", "round")) {
            next.ready();
            next.success();
            var result = Files.readString(next.readyFile());
            assertTrue(result.contains("GLOBAL RECORDED"), result);
            assertFalse(result.contains("pendingAll=true"), result);
        }
    }

    @Test
    void waitingStartupCannotEnterBeforeMaintenanceDateCommitCompletes() throws Exception {
        var game = temp.resolve("game");
        var home = temp.resolve("home");
        try (var cleanup = child("maintenance", game, home, "1800000000", "date-window", "hold-complete")) {
            cleanup.ready();
            try (var waiter = child("consumer", home.resolve("cache"), temp.resolve("data"), "v1", "date-waiter")) {
                awaitFile(waiter.readyFile().resolveSibling("date-waiter.ready.attempt"));
                Thread.sleep(100);
                assertFalse(Files.exists(waiter.readyFile()));
                cleanup.process().getOutputStream().write(1);
                cleanup.process().getOutputStream().flush();
                cleanup.success();
                assertTrue(Files.readString(cleanup.readyFile().resolveSibling("date-window.ready.result"))
                    .startsWith("RECORDED"));
                waiter.ready();
                waiter.process().getOutputStream().write(1);
                waiter.process().getOutputStream().flush();
                waiter.success();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void simultaneousFirstOpenPreservesEveryCommittedValue() throws Exception {
        var root = Files.createDirectory(temp.resolve("initialization"));
        var player = new java.util.UUID(0, 1);
        var go = temp.resolve("initialize-go");
        final String firstResult;
        final String secondResult;
        try (
            var first = child("sqlite-initialize", root, temp, "v1", "initialize-first", go.toString(),
                player.toString(), "owner/first", "11");
            var second = child("sqlite-initialize", root, temp, "v1", "initialize-second", go.toString(),
                player.toString(), "owner/second", "22")) {
            first.ready();
            second.ready();
            assertFalse(Files.exists(root.resolve("preferences-c2.sqlite")));
            Files.writeString(go, "go");
            first.success();
            second.success();
            firstResult = Files.readString(temp.resolve("initialize-first.ready.result"));
            secondResult = Files.readString(temp.resolve("initialize-second.ready.result"));
        }
        // An existing uninitialized file can belong to a live creator or an interrupted initialization.
        assertTrue(Set.of("COMMITTED", "RESOURCE_BUSY", "INCOMPATIBLE").contains(firstResult));
        assertTrue(Set.of("COMMITTED", "RESOURCE_BUSY", "INCOMPATIBLE").contains(secondResult));
        assertTrue(firstResult.equals("COMMITTED") || secondResult.equals("COMMITTED"));
        try (var reopened = child("sqlite-initialize", root, temp, "v1", "initialize-reopened", go.toString(),
            player.toString(), "owner/reopened", "33")) {
            reopened.ready();
            reopened.success();
            assertEquals("COMMITTED", Files.readString(temp.resolve("initialize-reopened.ready.result")));
        }
        try (var jdbc = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("preferences-c2.sqlite"));
            var sql = jdbc.createStatement()) {
            try (var metadata = sql.executeQuery("SELECT compatibility_epoch,"
                + "(SELECT user_version FROM pragma_user_version) FROM storage_meta WHERE id=1")) {
                assertTrue(metadata.next());
                assertEquals(2, metadata.getInt(1));
                assertEquals(1, metadata.getInt(2));
                assertFalse(metadata.next());
            }
            try (var rows = sql.executeQuery("SELECT identity,value,updated FROM preferences ORDER BY identity")) {
                var count = 0;
                while (rows.next()) {
                    count++;
                    switch (rows.getString(1)) {
                        case "owner/first" -> {
                            assertEquals("COMMITTED", firstResult);
                            assertArrayEquals(new byte[] {11}, rows.getBytes(2));
                            assertEquals(17, rows.getLong(3));
                        }
                        case "owner/second" -> {
                            assertEquals("COMMITTED", secondResult);
                            assertArrayEquals(new byte[] {22}, rows.getBytes(2));
                            assertEquals(17, rows.getLong(3));
                        }
                        case "owner/reopened" -> {
                            assertArrayEquals(new byte[] {33}, rows.getBytes(2));
                            assertEquals(17, rows.getLong(3));
                        }
                        default -> fail("Unexpected preference identity: " + rows.getString(1));
                    }
                }
                assertEquals(1 + (firstResult.equals("COMMITTED") ? 1 : 0) + (secondResult.equals("COMMITTED") ? 1 : 0),
                    count);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void discoveredRegionsKeepFactsAndRealConsumersAcrossProcesses(boolean fallback) throws Exception {
        var home = temp.resolve("regional-home");
        var game = temp.resolve("first-game");
        var other = temp.resolve("second-game");
        if (fallback)
            Files.writeString(home, "global-unavailable");
        try (var writer = child("region", home, game, "v1", "region-write", "write", Boolean.toString(fallback))) {
            writer.ready();
            writer.success();
        }
        try (var reader =
            child("region", home, fallback ? game : other, "v1", "region-read", "read", Boolean.toString(fallback))) {
            reader.ready();
            reader.success();
        }
        if (fallback) {
            try (var isolated = child("region", home, other, "v1", "region-isolated", "empty", "true")) {
                isolated.ready();
                isolated.success();
            }
        }
        for (var instance : java.util.List.of(game, other)) {
            assertEquals(fallback, Files.isRegularFile(instance.resolve("ysm/preferences-c2.sqlite")));
            // Startup establishes each root's reference authority before handing out consumers.
            assertTrue(Files.isRegularFile(instance.resolve("ysm/cache/f/remote.sqlite")));
        }
        assertEquals(!fallback, Files.isRegularFile(home.resolve("preferences-c2.sqlite")));
        assertEquals(!fallback, Files.isRegularFile(home.resolve("cache/f/remote.sqlite")));
        final Path object;
        try (var consumer = child("region", home, game, "v1", "region-consumer", "hold", Boolean.toString(fallback))) {
            consumer.ready();
            object = Path.of(Files.readString(consumer.readyFile()));
            try (var cleaner = child("region", home, fallback ? game : other, "v1", "region-blocked", "prune",
                Boolean.toString(fallback))) {
                cleaner.ready();
                cleaner.success();
                assertEquals("SKIPPED_ACTIVE_CONSUMER", Files.readString(cleaner.readyFile()));
                assertEquals("region-consumer", Files.readString(object));
            }
            if (fallback) {
                Files.delete(home);
                Files.createDirectory(home);
            }
            consumer.process().getOutputStream().write(1);
            consumer.process().getOutputStream().flush();
            consumer.success();
            assertEquals("region-consumer", Files.readString(temp.resolve("region-consumer.ready.read")));
        }
        if (fallback) {
            assertFalse(Files.exists(home.resolve("preferences-c2.sqlite")));
            assertFalse(Files.exists(home.resolve("cache/f/remote.sqlite")));
            Files.delete(home);
            Files.writeString(home, "global-unavailable-again");
        }
        try (var crashed = child("region", home, game, "v1", "region-crashed", "hold", Boolean.toString(fallback))) {
            crashed.ready();
            crashed.kill();
        }
        try (var cleaner = child("region", home, fallback ? game : other, "v1", "region-recovered", "prune",
            Boolean.toString(fallback))) {
            cleaner.ready();
            cleaner.success();
            assertEquals("COMPLETED", Files.readString(cleaner.readyFile()));
        }
        assertTrue(Files.exists(object));
        try (var reader =
            child("region", home, game, "v1", "region-facts-retained", "read", Boolean.toString(fallback))) {
            reader.ready();
            reader.success();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rawColdAndRestartedWarmReuseThePersistedInstanceWithoutOpeningLockedPayload() throws Exception {
        var source = Path.of(java.util.Objects
            .requireNonNull(getClass().getResource("/assets/ysm/builtin/misc/1_alex/ysm.json")).toURI()).getParent();
        var data = Files.createDirectory(temp.resolve("raw-data"));
        var raw = data.resolve("alex");
        try (var paths = Files.walk(source)) {
            for (var file : paths.toList()) {
                var target = raw.resolve(source.relativize(file));
                if (Files.isDirectory(file))
                    Files.createDirectories(target);
                else
                    Files.copy(file, target);
            }
        }
        var cache = temp.resolve("raw-cache");
        final String cold;
        try (var process = child("raw-reload", cache, data, "v1", "raw-cold")) {
            process.ready();
            process.success();
            cold = Files.readString(process.readyFile());
        }
        final Path payload;
        try (var members = Files.walk(raw)) {
            payload = members.filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().equals("ysm.json")).findFirst().orElseThrow();
        }
        try (
            var channel = java.nio.channels.FileChannel.open(payload, java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE);
            var lock = channel.lock(); var process = child("raw-reload", cache, data, "v1", "raw-warm")) {
            process.ready();
            process.success();
            var warm = Files.readString(process.readyFile());
            assertEquals(cold.split(" ")[0], warm.split(" ")[0]);
            System.out.println("Raw restart sample models=1 coldNanos=" + cold.split(" ")[1] + " warmNanos="
                + warm.split(" ")[1] + " lockedPayloadBytes=" + Files.size(payload));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void naturalExitAbandonsBlockedWorkersAndBufferedPreferencesButPreservesCommittedSnapshot() throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var root = Files.createDirectory(temp.resolve("abandoned-preferences"));
        var go = temp.resolve("abandon-go");
        var player = new java.util.UUID(0, 1);
        var identity = com.elfmcys.ysm.model.domain.PublishedModelId.parse("owner/model@1.0.0");
        try (var child = child("sqlite-abandon", root, temp, "v1", "abandon", go.toString(), player.toString(),
            identity.toString())) {
            child.ready();
            assertEquals("COMMITTED BUFFERED 3_DAEMON_WORKERS", Files.readString(child.readyFile()));
            assertTrue(child.process().isAlive());
            Files.writeString(go, "go");
            child.success();
        }
        try (var work = new DatabaseWork()) {
            var preferences = new PlayerPreferences(root, work);
            assertEquals(java.util.Map.of("first", 11f, "second", 13f),
                TestNamedRoamingSnapshots.named(PlayerPreferencesTest.found(preferences,
                    preferences.readRoaming(player, identity, Set.of("first", "second")))));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void sqliteBufferedUsageKeepsEventMaximumAcrossProcesses() throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var root = Files.createDirectory(temp.resolve("usage"));
        Files.createDirectory(root.resolve("f"));
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create()) {
            // This oracle races usage updates, not first creation of the coordination schema.
            try (var initialized =
                com.elfmcys.ysm.natives.storage.NativeCoordination.open(root.resolve("f"), cancelled)) {
            }
            try (var database = com.elfmcys.ysm.natives.storage.NativeCacheRemote.open(root.resolve("f"), cancelled)) {
                assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.MISSING,
                    database.readUsage(new com.elfmcys.ysm.model.domain.Hash256(new byte[32]), cancelled).outcome());
            }
            var go = temp.resolve("usage-go");
            try (var first = child("sqlite-usage", root, temp, "v1", "usage-100", go.toString(), "100");
                var second = child("sqlite-usage", root, temp, "v1", "usage-102", go.toString(), "102")) {
                first.ready();
                second.ready();
                Files.writeString(go, "go");
                first.success();
                second.success();
            }
            try (var older = child("sqlite-usage", root, temp, "v1", "usage-101", go.toString(), "101")) {
                older.ready();
                older.success();
            }
            try (var database = com.elfmcys.ysm.natives.storage.NativeCacheRemote.open(root.resolve("f"), cancelled)) {
                assertEquals(102L, database.readUsage(new com.elfmcys.ysm.model.domain.Hash256(new byte[32]), cancelled)
                    .rows().get(0));
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void sqlitePreferencesShareAcrossProcessesAndFirstFillNeverOverwritesExistingTarget() throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var root = Files.createDirectory(temp.resolve("preferences"));
        var go = temp.resolve("preferences-go");
        var u = new java.util.UUID(0, 1).toString();
        var v = new java.util.UUID(0, 2).toString();
        try (var work = new DatabaseWork()) {
            var preferences = new PlayerPreferences(root, work);
            PlayerPreferencesTest.committed(preferences,
                preferences.writeRoaming(java.util.UUID.fromString(u),
                    com.elfmcys.ysm.model.domain.PublishedModelId.parse("owner/model@1.0.0"),
                    PlayerPreferencesTest.payload(11)));
        }
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create();
            var database = com.elfmcys.ysm.natives.storage.NativePlayerData.open(root, cancelled)) {
            assertTrue(database.putPreference(
                com.elfmcys.ysm.natives.storage.NativePlayerData.PreferenceKey.roaming(java.util.UUID.fromString(u),
                    com.elfmcys.ysm.model.domain.PublishedModelId.parse("broken/history")),
                com.elfmcys.ysm.buffer.ArrayBuffer.borrow(new byte[] {1, 65}), 0, cancelled).committed());
        }
        try (
            var writer = child("sqlite-preferences", root, temp, "v1", "pref-u", go.toString(), u, "owner/model@2.0.0",
                "22", "write");
            var other = child("sqlite-preferences", root, temp, "v1", "pref-v", go.toString(), v, "owner/model@2.0.0",
                "33", "write");
            var restore = child("sqlite-preferences", root, temp, "v1", "pref-restore", go.toString(), u,
                "owner/model@2.0.0", "11", "restore");
            var reject = child("sqlite-preferences", root, temp, "v1", "pref-reject", go.toString(), u,
                "broken/history", "99", "reject")) {
            writer.ready();
            other.ready();
            restore.ready();
            reject.ready();
            Files.writeString(go, "go");
            writer.success();
            other.success();
            restore.success();
            reject.success();
        }
        try (
            var first = child("sqlite-preferences", root, temp, "v1", "pref-u-read", go.toString(), u,
                "owner/model@2.0.0", "22", "read");
            var second = child("sqlite-preferences", root, temp, "v1", "pref-v-read", go.toString(), v,
                "owner/model@2.0.0", "33", "read")) {
            first.ready();
            second.ready();
            first.success();
            second.success();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void sqliteRealConsumersCrashCleanupGateAndWalWriters() throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var root = Files.createDirectory(temp.resolve("sqlite"));
        var file = Files.writeString(root.resolve("object.mxc"), "still-consumed");
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create()) {
            try (var registration = com.elfmcys.ysm.natives.storage.NativeCoordination.open(root, cancelled);
                var consumer = child("sqlite-consumer", root, temp, "v1", "sqlite-consumer", file.toString())) {
                consumer.ready();
                assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.REJECTED,
                    registration.beginCleanup(cancelled).outcome());
                var go = Files.writeString(temp.resolve("sqlite-go"), "go");
                try (var writer = child("sqlite-writer", root, temp, "v1", "writer-active", go.toString(),
                    new java.util.UUID(0, 1).toString(), "owner/model", "11")) {
                    writer.ready();
                    writer.success();
                }
                consumer.process().getOutputStream().write(1);
                consumer.process().getOutputStream().flush();
                consumer.success();
                assertEquals("still-consumed", Files.readString(temp.resolve("sqlite-consumer.ready.read")));
                assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.FOUND,
                    registration.beginCleanup(cancelled).outcome());
                assertTrue(registration.endCleanup().committed());
                try (var crashed = child("sqlite-consumer", root, temp, "v1", "crashed-sqlite")) {
                    crashed.ready();
                    crashed.kill();
                }
                var recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                var recovery = registration.beginCleanup(cancelled);
                // Process termination can precede completion of OS handle cleanup.
                while (recovery.outcome() == com.elfmcys.ysm.natives.storage.StorageResult.Outcome.REJECTED
                    && System.nanoTime() < recoveryDeadline) {
                    Thread.sleep(2);
                    recovery = registration.beginCleanup(cancelled);
                }
                assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.FOUND, recovery.outcome(),
                    recovery.toString());
                assertTrue(registration.endCleanup().committed());
            }
            try (var cleaner = child("sqlite-cleaner", root, temp, "v1", "exclusive-sqlite")) {
                cleaner.ready();
                assertEquals("FOUND", Files.readString(cleaner.readyFile()));
                var blocked = assertThrows(com.elfmcys.ysm.natives.storage.StorageResult.OpenException.class,
                    () -> com.elfmcys.ysm.natives.storage.NativeCoordination.open(root, cancelled));
                assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Failure.RESOURCE_BUSY,
                    blocked.result().failure());
                var go = Files.writeString(temp.resolve("sqlite-go-cleanup"), "go");
                try (var writer = child("sqlite-writer", root, temp, "v1", "writer-cleanup", go.toString(),
                    new java.util.UUID(0, 2).toString(), "owner/model", "22")) {
                    writer.ready();
                    writer.success();
                }
                cleaner.kill();
            }
            try (var recovered = com.elfmcys.ysm.natives.storage.NativeCoordination.open(root, cancelled)) {
                assertEquals(com.elfmcys.ysm.natives.storage.StorageResult.Outcome.FOUND,
                    recovered.beginCleanup(cancelled).outcome());
                assertTrue(recovered.endCleanup().committed());
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    void sqliteConcurrentRealWritersKeepSameAndDifferentKeysComplete() throws Exception {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        var root = Files.createDirectory(temp.resolve("sqlite"));
        var go = temp.resolve("go");
        var player = new java.util.UUID(0, 1);
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create();
            var database = com.elfmcys.ysm.natives.storage.NativePlayerData.open(root, cancelled)) {
            assertTrue(database.pagePreferences(player,
                com.elfmcys.ysm.natives.storage.NativePlayerData.PreferenceKind.ROAMING, "", cancelled).rows()
                .isEmpty());
        }
        try (
            var first = child("sqlite-writer", root, temp, "v1", "first-sqlite", go.toString(), player.toString(),
                "owner/model", "11");
            var second = child("sqlite-writer", root, temp, "v1", "second-sqlite", go.toString(), player.toString(),
                "owner/model", "22")) {
            first.ready();
            second.ready();
            Files.writeString(go, "go");
            first.success();
            second.success();
        }
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create();
            var db = com.elfmcys.ysm.natives.storage.NativePlayerData.open(root, cancelled)) {
            var key = com.elfmcys.ysm.natives.storage.NativePlayerData.PreferenceKey.roaming(player,
                com.elfmcys.ysm.model.domain.PublishedModelId.parse("owner/model"));
            var row = db.readPreference(key, cancelled).rows().get(0);
            assertEquals(2, row.value().size());
            var bytes = new byte[2];
            row.value().nio().get(bytes);
            assertTrue(bytes[0] == 11 || bytes[0] == 22);
            assertEquals(31, bytes[1]);
        }
        try (
            var first = child("sqlite-writer", root, temp, "v1", "third-sqlite", go.toString(), player.toString(),
                "owner/first", "33");
            var second = child("sqlite-writer", root, temp, "v1", "fourth-sqlite", go.toString(), player.toString(),
                "owner/second", "44")) {
            first.ready();
            second.ready();
            first.success();
            second.success();
        }
        try (var cancelled = com.elfmcys.ysm.natives.NativeCancellation.create();
            var db = com.elfmcys.ysm.natives.storage.NativePlayerData.open(root, cancelled)) {
            var result = db.pagePreferences(player,
                com.elfmcys.ysm.natives.storage.NativePlayerData.PreferenceKind.ROAMING, "", cancelled);
            assertEquals(3, result.rows().size());
        }
    }

    @Test
    void concurrentCategoryUpdatesSurviveReopenAndDoNotCrossDataOrVersion() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        var go = temp.resolve("go");
        var index = index(cache, data, "v1");
        assertTrue(index.read().isEmpty());
        // First-open qualification has a separate oracle; these writers exercise category isolation.
        try (var running = com.elfmcys.ysm.natives.NativeCancellation.create();
            var allocator = com.elfmcys.ysm.natives.storage.NativeFileIdAllocator.open(cache.resolve("f"), running)) {
        }
        try (var first = child("index", cache, data, "v1", "custom", go.toString(), "custom", "11");
            var second = child("index", cache, data, "v1", "auth", go.toString(), "auth", "22")) {
            first.ready();
            second.ready();
            Files.writeString(go, "go");
            first.success();
            second.success();
        }
        assertEquals(Set.of("custom/same.mxc", "auth/same.mxc"), index.read().keySet());
        var original = index.read();
        assertNotEquals(original.get("custom/same.mxc").instance(), original.get("auth/same.mxc").instance());
        try (
            var otherData =
                child("index", cache, temp.resolve("other-data"), "v1", "other-data", go.toString(), "custom", "33");
            var otherVersion = child("index", cache, data, "v2", "other-version", go.toString(), "custom", "44")) {
            otherData.ready();
            otherVersion.ready();
            otherData.success();
            otherVersion.success();
        }
        assertEquals(original.get("custom/same.mxc").instance(), index.read().get("custom/same.mxc").instance());
        assertNotEquals(original.get("custom/same.mxc").instance(),
            index(cache, temp.resolve("other-data"), "v1").read().get("custom/same.mxc").instance());
        assertNotEquals(original.get("custom/same.mxc").instance(),
            index(cache, data, "v2").read().get("custom/same.mxc").instance());
    }

    @Test
    void activeProcessBlocksPruneAndItsCrashedRegistrationDoesNotBlockRecovery() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        try (var peer = child("consumer", cache, data, "v1", "consumer")) {
            peer.ready();
            try (var owner = new CacheRootCoordinator(cache)) {
                owner.register();
                assertFalse(owner.cleanup(() -> true, false));
            }
            peer.kill();
        }
        try (var owner = new CacheRootCoordinator(cache)) {
            owner.register();
            assertTrue(owner.cleanup(() -> true, false));
        }
    }

    @Test
    void probingLocalPeerPreservesItsLockForOtherProcesses() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        try (var peer = new CacheRootCoordinator(cache)) {
            peer.register();
            try (var first = new CacheRootCoordinator(cache)) {
                first.register();
                assertTrue(first.cleanup(() -> true, false));
            }
            try (var observer = child("prune", cache, data, "v1", "local-peer-observer")) {
                observer.ready();
                observer.success();
                assertEquals("SKIPPED_ACTIVE_CONSUMER", Files.readString(observer.readyFile()));
            }
        }
    }

    @Test
    void startupWaitsBeyondBusinessTimeoutAndContinuesAfterCleanup() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        try (var cleaner = child("cleanup-hold", cache, data, "v1", "cleaner")) {
            cleaner.ready();
            try (var waiter = child("consumer", cache, data, "v1", "waiter")) {
                awaitFile(waiter.readyFile().resolveSibling(waiter.readyFile().getFileName() + ".attempt"));
                Thread.sleep(1200);
                assertTrue(waiter.process().isAlive());
                assertFalse(Files.exists(waiter.readyFile()));
                cleaner.process().getOutputStream().write(1);
                cleaner.process().getOutputStream().flush();
                cleaner.success();
                waiter.ready();
                waiter.kill();
            }
        }
    }

    @Test
    void cleanupCrashReleasesWaitingStartupAndCancellationDoesNotAdmitLateConsumer() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        try (var cleaner = child("cleanup-hold", cache, data, "v1", "crashing-cleaner")) {
            cleaner.ready();
            var owner = new CacheRootCoordinator(cache);
            var opened = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    owner.register();
                    throw new AssertionError("Cancelled startup admitted");
                } catch (java.io.IOException expected) {
                }
            });
            Thread.sleep(100);
            owner.close();
            opened.get(10, java.util.concurrent.TimeUnit.SECONDS);
            try (var waiter = child("consumer", cache, data, "v1", "crash-waiter")) {
                Thread.sleep(100);
                assertFalse(Files.exists(waiter.readyFile()));
                cleaner.kill();
                waiter.ready();
                waiter.kill();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"write", "derived"})
    void interruptedWriterLeavesOldCompleteBytesAndNextProcessCanCommit(String mode) throws Exception {
        var cache = temp.resolve("cache");
        var target = TestCacheFiles.path(cache, com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.CHUNK,
            "process-test", true);
        var old = new byte[] {1, 3, 5, 7};
        var replacement = new byte[1024 * 1024];
        java.util.Arrays.fill(replacement, (byte)0x5a);
        Files.write(target, old);
        var input = Files.write(temp.resolve("payload.bin"), replacement);
        try (var writer =
            child(mode, cache, temp.resolve("data"), "v1", "writer", target.toString(), input.toString(), "hold")) {
            writer.ready();
            assertArrayEquals(old, Files.readAllBytes(target));
            writer.kill();
        }
        assertArrayEquals(old, Files.readAllBytes(target));
        try (var writer = child(mode, cache, temp.resolve("data"), "v1", "next-writer", target.toString(),
            input.toString(), "finish")) {
            writer.ready();
            writer.success();
        }
        var published = TestCacheFiles.path(cache, com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.CHUNK,
            "process-test", true);
        assertNotEquals(target, published);
        assertArrayEquals(old, Files.readAllBytes(target));
        assertArrayEquals(replacement, Files.readAllBytes(published));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"write", "derived"})
    void concurrentProcessesPublishOnlyACompleteContainer(String mode) throws Exception {
        var manifest = getClass().getResource("/assets/ysm/builtin/default/ysm.json");
        final Path fixture;
        try (var directory = new com.elfmcys.ysm.format.vfs.Directory(
            Path.of(java.util.Objects.requireNonNull(manifest).toURI()).getParent())) {
            fixture = com.elfmcys.ysm.format.parser.ModelParser.parseBuiltinDefault(directory,
                Files.createDirectories(temp.resolve("fixture")));
        }
        var expected = Files.readAllBytes(fixture);
        var cache = temp.resolve("cache");
        var target = TestCacheFiles.path(cache, com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.CHUNK,
            "process-test", true);
        try (var first = child(mode, cache, temp.resolve("data"), "v1", "first-container", target.toString(),
            fixture.toString(), "hold")) {
            first.ready();
            assertFalse(Files.exists(target));
            try (var second = child(mode, cache, temp.resolve("data"), "v1", "second-container", target.toString(),
                fixture.toString(), "finish")) {
                first.process().getOutputStream().write(1);
                first.process().getOutputStream().flush();
                first.success();
                second.success();
            }
        }
        var published = TestCacheFiles.path(cache, com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.CHUNK,
            "process-test", true);
        assertArrayEquals(expected, Files.readAllBytes(published));
        try (var source = ManagedContainer.verifyFile(fixture); var reopened = ManagedContainer.verifyFile(published)) {
            assertEquals(source.identity(), reopened.identity());
        }
    }

    @Test
    void duplicateAllocationDuringAnInFlightMoveCannotCoverAnotherProcessesBytes() throws Exception {
        var cache = temp.resolve("cache");
        var data = temp.resolve("data");
        try (var first = child("flat-producer", cache, data, "v1", "flat-first", "first-bytes", "hold")) {
            first.ready();
            assertEquals("1", Files.readString(first.readyFile()));
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + cache.resolve("f/ids.sqlite"));
                var statement = db.createStatement()) {
                statement.execute("UPDATE allocator SET next_id=1,exhausted=0");
            }
            try (var second = child("flat-producer", cache, data, "v1", "flat-second", "second-bytes", "finish")) {
                second.ready();
                second.success();
                assertEquals("2", Files.readString(second.readyFile()));
            }
            assertEquals("first-bytes", Files.readString(cache.resolve("f/c/s/0000000000000001")));
            first.process().getOutputStream().write(1);
            first.process().getOutputStream().flush();
            first.success();
        }
        assertEquals("first-bytes", Files.readString(cache.resolve("f/c/0000000000000001")));
        assertEquals("second-bytes", Files.readString(cache.resolve("f/c/0000000000000002")));
    }

    private static void awaitFile(Path file) throws Exception {
        var end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(file) && System.nanoTime() < end)
            Thread.sleep(10);
        assertTrue(Files.exists(file), file.toString());
    }

    private ConvertedSourceIndexStore index(Path cache, Path data, String version) {
        return new ConvertedSourceIndexStore(ConvertedScope.of(data, version),
            com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(cache)));
    }

    private Child child(String mode, Path cache, Path data, String version, String name, String... rest)
        throws Exception {
        var ready = temp.resolve(name + ".ready");
        var log = temp.resolve(name + ".log");
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-cp");
        args.add(classpath());
        args.add(SharedStorageProcess.class.getName());
        args.add(mode);
        args.add(cache.toString());
        args.add(data.toString());
        args.add(version);
        args.add(ready.toString());
        args.addAll(java.util.List.of(rest));
        var argfile = temp.resolve(name + ".args");
        Files.write(argfile, args.subList(1, args.size()).stream()
            .map(value -> "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"").toList());
        var process = new ProcessBuilder(args.get(0), "@" + argfile).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        return new Child(process, ready, log);
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private record Child(Process process, Path readyFile, Path log) implements AutoCloseable {
        void ready() throws Exception {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(readyFile) || Files.size(readyFile) == 0) {
                if (!process.isAlive() || System.nanoTime() > deadline)
                    fail(new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8));
                Thread.sleep(2);
            }
        }

        void success() throws Exception {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Child timed out: " + log);
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception failure) {
                    return failure.toString();
                }
            });
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            if (System.getProperty("os.name").startsWith("Windows")) {
                // Windows can signal process termination before closing inherited file handles.
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    try (var ignored = java.nio.channels.FileChannel.open(log, java.nio.file.StandardOpenOption.READ,
                        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE)) {
                        break;
                    } catch (java.nio.file.FileSystemException closing) {
                        if (System.nanoTime() > deadline)
                            throw closing;
                        Thread.sleep(10);
                    }
                }
            }
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive())
                kill();
        }
    }
}
