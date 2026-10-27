package com.elfmcys.ysm;

import com.elfmcys.ysm.model.storage.CacheDirectoryGuard;
import com.elfmcys.ysm.util.DirectoryAccess;
import com.elfmcys.ysm.util.FileIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All mutable startup state stays in this factory; consumers see only a completed layout. */
final class AssetDirectoryProbe {
    private AssetDirectoryProbe() {}

    enum Operation {
        SELECT, LOCATION, CREATE, LIST, WRITE, READ, DELETE, ATOMIC_REPLACE, LOCK, IDENTITY
    }

    @FunctionalInterface
    interface Hook {
        void before(Operation operation, Path path) throws IOException;
    }

    static AssetPaths discover(Path game, Path config, Path builtin, Map<String, String> environment,
        AssetDirectoryPlatform.Kind platform, AssetDirectoryPlatform.MacDirectories mac, Hook hook) {
        var diagnostics = new ArrayList<AssetPaths.Diagnostic>();
        var gameRoot = game.toAbsolutePath().normalize();
        var candidates = new ArrayList<Candidate>();
        try {
            if (environment.containsKey("YSM_HOME")) {
                var data = absolute(environment.get("YSM_HOME"), "YSM_HOME");
                candidates.add(new Candidate(data, data.resolve("cache")));
            } else {
                switch (platform) {
                    case WINDOWS -> {
                        var data = absolute(environment.get("LOCALAPPDATA"), "LOCALAPPDATA").resolve("ysm");
                        candidates.add(new Candidate(data, data.resolve("cache")));
                    }
                    case LINUX -> {
                        var data = absolute(environment.get("XDG_DATA_HOME"), "XDG_DATA_HOME").resolve("ysm");
                        var cache = absolute(environment.get("XDG_CACHE_HOME"), "XDG_CACHE_HOME").resolve("ysm/cache");
                        candidates.add(new Candidate(data, cache));
                    }
                    case ANDROID -> {
                        try {
                            var data = absolute(environment.get("HOME"), "HOME").resolve("ysm");
                            candidates.add(new Candidate(data, data.resolve("cache")));
                        } catch (IllegalArgumentException invalid) {
                            diagnostics.add(new AssetPaths.Diagnostic("SELECT", null, invalid.getMessage()));
                        }
                        var data = gameRoot.resolve("../../ysm").normalize();
                        if (data.getParent() != null && data.getParent().getFileName() != null
                            && data.getParent().getFileName().toString().equals(".minecraft")) {
                            candidates.add(new Candidate(data, data.resolve("cache")));
                        } else {
                            // Record this only if the first candidate does not succeed.
                            candidates.add(new Candidate(null, null));
                        }
                    }
                    case MACOS -> {
                        if (mac == null)
                            throw new IllegalArgumentException("macOS directory query unavailable");
                        var data = absolute(mac.applicationSupport(), "Application Support").resolve("ysm");
                        var cache = mac.caches() == null ? data.resolve("cache")
                            : absolute(mac.caches(), "Caches").resolve("ysm/cache");
                        candidates.add(new Candidate(data, cache));
                    }
                    case UNSUPPORTED -> throw new IllegalArgumentException("Unsupported directory platform");
                }
            }
        } catch (RuntimeException error) {
            diagnostics.add(new AssetPaths.Diagnostic("SELECT", null, error.getMessage()));
        }
        for (var candidate : candidates) {
            if (candidate.data == null) {
                diagnostics.add(new AssetPaths.Diagnostic("SELECT", null,
                    "Android second candidate is not directly below .minecraft"));
                continue;
            }
            try {
                var global = probe(candidate, gameRoot, hook, diagnostics);
                return new AssetPaths(gameRoot, config, builtin, global, diagnostics);
            } catch (ProbeFailure error) {
                diagnostics
                    .add(new AssetPaths.Diagnostic(error.operation.name(), error.path, error.getCause().toString()));
            }
        }
        return new AssetPaths(gameRoot, config, builtin, null, diagnostics);
    }

    private static Path absolute(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is empty or unset");
        return absolute(Path.of(value), name);
    }

    private static Path absolute(Path value, String name) {
        if (value == null || !value.isAbsolute())
            throw new IllegalArgumentException(name + " must be absolute");
        return value.normalize();
    }

    private static AssetPaths.GlobalDirectories probe(Candidate candidate, Path game, Hook hook,
        List<AssetPaths.Diagnostic> diagnostics) throws ProbeFailure {
        var roots =
            List.of(candidate.data, candidate.data.resolve("custom"), candidate.data.resolve("auth"), candidate.cache);
        var observedRoots = new ArrayList<RootObservation>();
        for (var root : roots) {
            run(Operation.LOCATION, root, hook, () -> {
                var realGame = DirectoryAccess.prospectiveRealPath(game);
                if (root.startsWith(game) || DirectoryAccess.prospectiveRealPath(root).startsWith(realGame)) {
                    throw new IOException("Global directory is inside the game directory: " + root);
                }
            });
        }
        for (var root : roots) {
            run(Operation.CREATE, root, hook, () -> DirectoryAccess.ensureDirectory(root));
            run(Operation.LOCATION, root, hook, () -> {
                var real = root.toRealPath();
                if (real.startsWith(DirectoryAccess.prospectiveRealPath(game))) {
                    throw new IOException("Global directory moved inside the game directory: " + root);
                }
                var key = FileIdentity.read(real);
                observedRoots.add(new RootObservation(root, real, key));
            });
            probeDirectory(root, hook, diagnostics);
        }
        final CacheDirectoryGuard guard;
        try {
            hook.before(Operation.IDENTITY, candidate.cache);
            guard = CacheDirectoryGuard.open(candidate.cache);
        } catch (IOException | RuntimeException error) {
            throw new ProbeFailure(Operation.IDENTITY, candidate.cache, error);
        }
        probeSharedStorage(guard, hook, diagnostics);
        for (var observed : observedRoots) {
            run(Operation.IDENTITY, observed.logical, hook, () -> {
                if (!observed.real.equals(observed.logical.toRealPath())
                    || !observed.key.equals(FileIdentity.read(observed.real))) {
                    throw new IOException("Global directory changed during startup: " + observed.logical);
                }
            });
        }
        return new AssetPaths.GlobalDirectories(candidate.data, guard.root(), guard);
    }

    private static void probeDirectory(Path directory, Hook hook, List<AssetPaths.Diagnostic> diagnostics)
        throws ProbeFailure {
        var temporary = directory.resolve(".ysm-probe-" + UUID.randomUUID());
        var bytes = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DirectoryAccess capability = null;
        Object ownedKey = null;
        ProbeFailure failure = null;
        try {
            capability = DirectoryAccess.open(directory.toRealPath());
            var held = capability;
            run(Operation.LIST, directory, hook, held::names);
            hook.before(Operation.CREATE, temporary);
            try (var channel = held.file(temporary.getFileName().toString(), true, true, true)) {
                ownedKey = held.fileIdentity(temporary.getFileName().toString());
                run(Operation.WRITE, temporary, hook, () -> write(channel, bytes));
                run(Operation.READ, temporary, hook, () -> check(channel, bytes));
            }
        } catch (ProbeFailure error) {
            failure = error;
        } catch (IOException | RuntimeException error) {
            failure = new ProbeFailure(Operation.CREATE, temporary, error);
        } finally {
            if (ownedKey != null) {
                var expectedKey = ownedKey;
                var held = capability;
                try {
                    run(Operation.DELETE, temporary, hook,
                        () -> held.delete(temporary.getFileName().toString(), expectedKey, false));
                } catch (ProbeFailure cleanup) {
                    diagnostics.add(new AssetPaths.Diagnostic("DELETE", temporary, cleanup.getCause().toString()));
                    if (failure == null)
                        failure = cleanup;
                }
            }
            if (capability != null)
                try {
                    capability.close();
                } catch (IOException cleanup) {
                    if (failure == null)
                        failure = new ProbeFailure(Operation.DELETE, directory, cleanup);
                }
        }
        if (failure != null)
            throw failure;
    }

    private static void probeSharedStorage(CacheDirectoryGuard guard, Hook hook,
        List<AssetPaths.Diagnostic> diagnostics) throws ProbeFailure {
        var files = new ArrayList<OwnedProbe>();
        ProbeFailure failure = null;
        DirectoryAccess parent = null;
        var oldBytes = new byte[] {1, 2, 3};
        var newBytes = new byte[] {4, 5, 6, 7};
        try {
            parent = guard.observe(guard.root().resolve(".guard"), false).acquire();
            var prefix = ".ysm-probe-" + UUID.randomUUID();
            var target = create(guard, parent, prefix + "-target", oldBytes, files, hook);
            var staging = create(guard, parent, prefix + "-staging", newBytes, files, hook);
            run(Operation.ATOMIC_REPLACE, target.path(), hook, () -> {
                target.verify();
                staging.verify();
                staging.parent.move(staging.name, staging.key, target.parent, target.name);
                target.key = staging.key;
                staging.key = null;
            });
            run(Operation.READ, target.path(), hook, () -> {
                target.verify();
                try (var channel = target.parent.file(target.name, false, false, false)) {
                    check(channel, newBytes);
                }
            });
            var lockFile = create(guard, parent, prefix + "-lock", new byte[0], files, hook);
            run(Operation.LOCK, lockFile.path(), hook, () -> {
                lockFile.verify();
                try (var channel = lockFile.parent.file(lockFile.name, true, false, false);
                    var lock = channel.tryLock()) {
                    if (lock == null || !lock.isValid())
                        throw new IOException("OS file lock unavailable");
                }
            });
        } catch (ProbeFailure error) {
            failure = error;
        } catch (IOException | RuntimeException error) {
            failure = new ProbeFailure(Operation.IDENTITY, guard.root(), error);
        } finally {
            for (var file : files) {
                if (file.key == null)
                    continue;
                try {
                    run(Operation.DELETE, file.path(), hook, () -> file.parent.delete(file.name, file.key, false));
                } catch (ProbeFailure cleanup) {
                    diagnostics.add(new AssetPaths.Diagnostic("DELETE", file.path(), cleanup.getCause().toString()));
                    if (failure == null)
                        failure = cleanup;
                }
            }
            if (parent != null)
                try {
                    parent.close();
                } catch (IOException cleanup) {
                    if (failure == null)
                        failure = new ProbeFailure(Operation.DELETE, guard.root(), cleanup);
                }
        }
        if (failure != null)
            throw failure;
    }

    private static OwnedProbe create(CacheDirectoryGuard guard, DirectoryAccess parent, String name, byte[] bytes,
        List<OwnedProbe> owned, Hook hook) throws ProbeFailure {
        var path = parent.label().resolve(name);
        try {
            hook.before(Operation.CREATE, path);
            try (var channel = parent.file(name, true, true, true)) {
                var created = new OwnedProbe(guard, parent, name, parent.fileIdentity(name));
                owned.add(created);
                run(Operation.WRITE, path, hook, () -> write(channel, bytes));
                run(Operation.READ, path, hook, () -> check(channel, bytes));
                return created;
            }
        } catch (IOException | RuntimeException error) {
            throw new ProbeFailure(Operation.CREATE, path, error);
        }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
            channel.write(buffer);
        channel.force(true);
    }

    private static void check(FileChannel channel, byte[] expected) throws IOException {
        if (channel.size() != expected.length)
            throw new IOException("Probe size mismatch");
        channel.position(0);
        var buffer = ByteBuffer.allocate(expected.length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0)
                throw new IOException("Probe ended early");
        }
        if (!Arrays.equals(expected, buffer.array()))
            throw new IOException("Probe content mismatch");
    }

    private static void run(Operation operation, Path path, Hook hook, IoOperation action) throws ProbeFailure {
        try {
            hook.before(operation, path);
            action.run();
        } catch (IOException | RuntimeException error) {
            throw new ProbeFailure(operation, path, error);
        }
    }

    private record Candidate(Path data, Path cache) {
    }

    private record RootObservation(Path logical, Path real, Object key) {
    }

    private static final class OwnedProbe {
        private final CacheDirectoryGuard guard;
        private final DirectoryAccess parent;
        private final String name;
        private Object key;

        private OwnedProbe(CacheDirectoryGuard guard, DirectoryAccess parent, String name, Object key) {
            this.guard = guard;
            this.parent = parent;
            this.name = name;
            this.key = key;
        }

        private Path path() {
            return parent.label().resolve(name);
        }

        private void verify() throws IOException {
            guard.verify();
            if (!key.equals(parent.fileIdentity(name)))
                throw new IOException("Probe identity changed: " + path());
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    private static final class ProbeFailure extends Exception {
        private final Operation operation;
        private final Path path;

        ProbeFailure(Operation operation, Path path, Throwable cause) {
            super(cause);
            this.operation = operation;
            this.path = path;
        }
    }
}
