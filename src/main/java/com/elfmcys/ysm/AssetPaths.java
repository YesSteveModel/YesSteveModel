package com.elfmcys.ysm;

import com.elfmcys.ysm.model.storage.CacheDirectoryGuard;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Complete directory selection published after startup probes and cleanup finish. */
public final class AssetPaths {
    private final Path gameDirectory;
    private final Path configDirectory;
    private final Path builtinModelsResource;
    private final Path localDataRoot;
    private final Path localCacheRoot;
    private final GlobalDirectories global;
    private final List<Diagnostic> diagnostics;

    AssetPaths(Path gameDirectory, Path configDirectory, Path builtinModelsResource,
        GlobalDirectories global, List<Diagnostic> diagnostics) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory).toAbsolutePath().normalize();
        this.configDirectory = Objects.requireNonNull(configDirectory).toAbsolutePath().normalize();
        this.builtinModelsResource = Objects.requireNonNull(builtinModelsResource);
        localDataRoot = gameModelsRoot(this.gameDirectory);
        localCacheRoot = gameCacheRoot(this.gameDirectory);
        this.global = global;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static AssetPaths discover(Path gameDirectory, Path configDirectory, Path builtinModelsResource,
        Map<String, String> environment, AssetDirectoryPlatform.Kind platform,
        AssetDirectoryPlatform.MacDirectories macDirectories) {
        return AssetDirectoryProbe.discover(gameDirectory, configDirectory, builtinModelsResource,
            Map.copyOf(environment), platform, macDirectories, (operation, path) -> {});
    }

    /** Explicit layout for tests and embedded callers; no environment or filesystem access. */
    public static AssetPaths localOnly(Path gameDirectory, Path configDirectory, Path builtinModelsResource) {
        return new AssetPaths(gameDirectory, configDirectory, builtinModelsResource, null, List.of());
    }

    public Path gameDirectory() { return gameDirectory; }
    public Path configDirectory() { return configDirectory; }
    public Path configRoot() { return configDirectory.resolve("ysm"); }
    public Path exportRoot() { return configRoot().resolve("export"); }
    public Path builtinModelsResource() { return builtinModelsResource; }
    public Path builtinIndex() { return builtinModelsResource.resolveSibling("builtin-index.json"); }
    public Path gameModelsRoot() { return localDataRoot; }
    public Path gameCacheRoot() { return localCacheRoot; }
    public Path customModelsRoot() { return customModelsRoot(localDataRoot); }
    public Path authModelsRoot() { return authModelsRoot(localDataRoot); }
    public boolean globalEnabled() { return global != null; }
    public Optional<GlobalDirectories> global() { return Optional.ofNullable(global); }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public Path defaultCustomRoot() { return global == null ? customModelsRoot() : global.customRoot(); }
    public Path remoteRoot() { return (global == null ? localCacheRoot : global.cacheRoot()).resolve("remote"); }

    public static Path gameModelsRoot(Path gameDirectory) { return gameDirectory.resolve("ysm"); }
    public static Path gameCacheRoot(Path gameDirectory) { return gameModelsRoot(gameDirectory).resolve("cache"); }
    public static Path customModelsRoot(Path dataRoot) { return dataRoot.resolve("custom"); }
    public static Path authModelsRoot(Path dataRoot) { return dataRoot.resolve("auth"); }
    public static Path convertedRoot(Path cacheRoot) { return cacheRoot.resolve("converted"); }
    public static Path bakedRoot(Path gameDirectory) { return gameCacheRoot(gameDirectory).resolve("bake"); }

    public record GlobalDirectories(Path dataRoot, Path cacheRoot, CacheDirectoryGuard cacheGuard) {
        public GlobalDirectories {
            Objects.requireNonNull(dataRoot);
            Objects.requireNonNull(cacheRoot);
            Objects.requireNonNull(cacheGuard);
        }
        public Path customRoot() { return customModelsRoot(dataRoot); }
        public Path authRoot() { return authModelsRoot(dataRoot); }
    }

    /** Host-only diagnostic; never include this record in catalog or network messages. */
    public record Diagnostic(String operation, Path path, String message) {}
}
