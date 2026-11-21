package com.elfmcys.ysm;

import com.elfmcys.ysm.model.storage.ModelStorageInfra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.elfmcys.ysm.AssetDirectoryPlatform.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetPathsTest {
    @TempDir
    Path temp;

    private AssetPaths discover(Map<String, String> env, AssetDirectoryPlatform.Kind platform) {
        return AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"), env, platform,
            null);
    }

    @Test
    void explicitRootExcludesPlatformAndCleansEveryProbe() throws Exception {
        var root = temp.resolve("shared");
        var platform = temp.resolve("platform");
        var paths = discover(Map.of("YSM_HOME", root.toString(), "LOCALAPPDATA", platform.toString()), WINDOWS);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        var global = paths.global().orElseThrow();
        assertEquals(root, global.dataRoot());
        assertEquals(root.resolve("cache").toRealPath(), global.cacheRoot());
        assertEquals(root.resolve("models"), paths.defaultModelsRoot());
        assertEquals(root.resolve("cache").toRealPath().resolve("remote"), paths.remoteRoot());
        assertFalse(Files.exists(platform));
        try (var entries = Files.walk(root)) {
            assertEquals(java.util.Set.of("", "models", "cache"),
                entries.map(root::relativize).map(Path::toString).collect(java.util.stream.Collectors.toSet()));
        }
        assertFalse(Files.exists(paths.gameModelsRoot()));
        Files.writeString(root.resolve("models/shared.marker"), "shared-fixture");
        var second = AssetPaths.discover(temp.resolve("second-game"), temp, temp, Map.of("YSM_HOME", root.toString()),
            WINDOWS, null);
        assertEquals("shared-fixture", Files.readString(second.defaultModelsRoot().resolve("shared.marker")));
        assertEquals(paths.global().orElseThrow().cacheRoot(), second.global().orElseThrow().cacheRoot());
    }

    @Test
    void onlyModelsRootsAreScannedWithoutMovingLegacyDirectories() throws Exception {
        var shared = temp.resolve("shared");
        for (var root : java.util.List.of(shared, temp.resolve("game/ysm"))) {
            for (var legacy : java.util.List.of("custom", "auth")) {
                Files.createDirectories(root.resolve(legacy));
                Files.writeString(root.resolve(legacy).resolve("old.mxc"), "legacy source");
            }
        }
        var paths = discover(Map.of("YSM_HOME", shared.toString()), WINDOWS);
        var sources = com.elfmcys.ysm.model.catalog.source.ModelCatalogSources.reloadableSources(paths);
        assertEquals(
            java.util.List.of(com.elfmcys.ysm.model.catalog.source.CatalogRoot.GLOBAL,
                com.elfmcys.ysm.model.catalog.source.CatalogRoot.LOCAL),
            sources.stream().map(value -> value.publicRoot()).toList());
        assertTrue(sources.get(1).createIfMissing());
        for (var source : sources) {
            assertTrue(com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery.inventory(source).sources().isEmpty());
            assertTrue(Files.isDirectory(source.path()));
            assertEquals("legacy source", Files.readString(source.path().getParent().resolve("custom/old.mxc")));
            assertEquals("legacy source", Files.readString(source.path().getParent().resolve("auth/old.mxc")));
        }
        Files.createDirectories(paths.modelsRoot().resolve("custom"));
        Files.writeString(paths.modelsRoot().resolve("custom/model.mxc"), "literal nested directory");
        var inventory = com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery.inventory(sources.get(1));
        assertEquals(java.util.List.of("custom/model.mxc"),
            inventory.sources().keySet().stream().map(key -> key.relativePath().value()).toList());
    }

    @Test
    void invalidExplicitValuesNeverFallThrough() {
        for (var value : new String[] {"", "  ", "relative", temp.resolve("game").toString(),
            temp.resolve("game/child").toString()}) {
            var platform = temp.resolve("unused-platform");
            var paths = discover(Map.of("YSM_HOME", value, "LOCALAPPDATA", platform.toString()), WINDOWS);
            assertFalse(paths.globalEnabled(), value);
            assertFalse(paths.diagnostics().isEmpty());
            assertEquals(temp.resolve("game/ysm/cache/remote"), paths.remoteRoot());
            assertEquals(temp.resolve("game/ysm/models"), paths.defaultModelsRoot());
            assertFalse(Files.exists(platform));
        }
    }

    @Test
    void windowsRequiresAbsoluteLocalAppData() {
        for (var env : java.util.List.of(Map.<String, String>of(), Map.of("LOCALAPPDATA", ""),
            Map.of("LOCALAPPDATA", "relative"))) {
            assertFalse(discover(env, WINDOWS).globalEnabled());
        }
        var paths = discover(Map.of("LOCALAPPDATA", temp.resolve("windows").toString()), WINDOWS);
        assertEquals(temp.resolve("windows/ysm"), paths.global().orElseThrow().dataRoot());
    }

    @Test
    void linuxSeparatesExplicitDataAndCacheWithoutRequiringHome() throws Exception {
        var paths = discover(Map.of("XDG_DATA_HOME", temp.resolve("data").toString(), "XDG_CACHE_HOME",
            temp.resolve("cache").toString(), "XDG_DATA_DIRS", temp.resolve("must-not-exist").toString()), LINUX);
        assertEquals(temp.resolve("data/ysm"), paths.global().orElseThrow().dataRoot());
        assertEquals(temp.resolve("cache/ysm/cache").toRealPath(), paths.global().orElseThrow().cacheRoot());
        assertFalse(Files.exists(temp.resolve("must-not-exist")));
    }

    @Test
    void linuxRequiresBothXdgDirectoriesWithoutHomeFallback() {
        var home = temp.resolve("home");
        var data = temp.resolve("data");
        var cache = temp.resolve("cache");
        for (var key : new String[] {"XDG_DATA_HOME", "XDG_CACHE_HOME"}) {
            for (var value : new String[] {null, "", "  ", "relative"}) {
                var env = new HashMap<>(Map.of("HOME", home.toString(), "XDG_DATA_HOME", data.toString(),
                    "XDG_CACHE_HOME", cache.toString()));
                if (value == null)
                    env.remove(key);
                else
                    env.put(key, value);
                var paths = discover(env, LINUX);
                assertFalse(paths.globalEnabled(), key + "=" + value);
                assertTrue(
                    paths.diagnostics().stream()
                        .anyMatch(d -> d.operation().equals("SELECT") && d.message().contains(key)),
                    paths.diagnostics().toString());
                assertEquals(temp.resolve("game/ysm/models"), paths.defaultModelsRoot());
                assertEquals(temp.resolve("game/ysm/cache/remote"), paths.remoteRoot());
                assertFalse(Files.exists(home));
                assertFalse(Files.exists(data));
                assertFalse(Files.exists(cache));
            }
        }
    }

    @Test
    void androidUsesAtMostTwoCandidatesWithoutLauncherAdmission() throws Exception {
        var home = temp.resolve("android-home");
        var game = temp.resolve(".minecraft/versions/one");
        var paths = AssetPaths.discover(game, temp.resolve("config"), temp.resolve("builtin"),
            Map.of("HOME", home.toString(), "FCL_VERSION_CODE", "0"), ANDROID, null);
        assertEquals(home.resolve("ysm"), paths.global().orElseThrow().dataRoot());
        assertFalse(Files.exists(temp.resolve(".minecraft/ysm")));
        Files.writeString(temp.resolve("blocked-home"), "block");
        paths = AssetPaths.discover(game, temp.resolve("config"), temp.resolve("builtin"),
            Map.of("HOME", temp.resolve("blocked-home").toString()), ANDROID, null);
        assertEquals(temp.resolve(".minecraft/ysm"), paths.global().orElseThrow().dataRoot());
        assertFalse(paths.diagnostics().isEmpty());
        assertFalse(AssetPaths.discover(temp.resolve("wrong/versions/two"), temp.resolve("config"),
            temp.resolve("builtin"), Map.of(), ANDROID, null).globalEnabled());
        assertFalse(AssetPaths.discover(game, temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", " ", "HOME", home.toString()), ANDROID, null).globalEnabled());
    }

    @Test
    void macCacheQueryAbsencePermitsMergeButProbeFailureDoesNot() throws Exception {
        var support = temp.resolve("support");
        var cache = temp.resolve("mac-cache");
        var split = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"), Map.of(),
            MACOS, new AssetDirectoryPlatform.MacDirectories(support, cache));
        assertEquals(support.resolve("ysm"), split.global().orElseThrow().dataRoot());
        assertEquals(cache.resolve("ysm/cache").toRealPath(), split.global().orElseThrow().cacheRoot());
        var merged = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of(), MACOS, new AssetDirectoryPlatform.MacDirectories(support, null));
        assertEquals(support.resolve("ysm/cache").toRealPath(), merged.global().orElseThrow().cacheRoot());
        var blocked = Files.writeString(temp.resolve("blocked"), "file");
        var failed = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of(), MACOS, new AssetDirectoryPlatform.MacDirectories(temp.resolve("other-support"), blocked));
        assertFalse(failed.globalEnabled());
        assertFalse(Files.exists(temp.resolve("other-support/ysm/cache")));
    }

    @Test
    void gettersHaveNoSideEffects() {
        var paths = AssetPaths.localOnly(temp.resolve("isolated"), temp.resolve("config"), temp.resolve("builtin"));
        for (int i = 0; i < 10; i++) {
            assertEquals(temp.resolve("isolated/ysm/cache"), paths.gameCacheRoot());
            assertEquals(temp.resolve("config/ysm/export"), paths.exportRoot());
            paths.defaultModelsRoot();
            paths.remoteRoot();
        }
        assertFalse(Files.exists(temp.resolve("isolated")));
        assertFalse(Files.exists(temp.resolve("config")));
        assertThrows(UnsupportedOperationException.class, () -> paths.diagnostics().clear());
    }

    @Test
    void processSelectionCachesOnceAndSurvivesOwnerReconstruction() throws Exception {
        var probes = new AtomicInteger();
        var env = new HashMap<>(Map.of("YSM_HOME", temp.resolve("once").toString()));
        var process = new ProcessAssetPaths(() -> {
            probes.incrementAndGet();
            return AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
                Map.copyOf(env), WINDOWS, null);
        });
        var first = CompletableFuture.supplyAsync(process::get);
        var other = CompletableFuture.supplyAsync(process::get);
        var selected = first.get(5, TimeUnit.SECONDS);
        assertSame(selected, other.get(5, TimeUnit.SECONDS));
        env.put("YSM_HOME", temp.resolve("later").toString());
        var owner = ModelStorageInfra.open(selected);
        owner.close();
        var rebuilt = ModelStorageInfra.open(process.get());
        assertSame(selected, rebuilt.paths());
        rebuilt.close();
        assertEquals(1, probes.get());
        assertFalse(Files.exists(temp.resolve("later")));
        var isolated = AssetPaths.localOnly(temp.resolve("another-game"), temp, temp);
        assertNotEquals(selected.gameCacheRoot(), isolated.gameCacheRoot());
    }
}
