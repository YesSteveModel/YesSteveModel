package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.format.AssetLoadException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-process, immutable-object writer used by the preview, converted and baked caches. One instance guards one
 * physical store root: targets must stay inside it and its key locks live under {@code <root>/locks}, so caches in
 * different roots never share a lock directory.
 */
public final class AtomicSharedCache {
    private static final String LOCK_SUFFIX = ".lck";

    /** Key-lock directory inside one shared-cache root. */
    static final String LOCK_DIRECTORY = "locks";

    private final Path cacheRoot;
    private final AtomicMove atomicMove;
    private final StagingFlush stagingFlush;
    private volatile CacheDirectoryGuard guard;
    private final DerivedStorageWork derivedWork;

    public AtomicSharedCache(Path cacheRoot) {
        this(cacheRoot, AtomicReplacement::run);
    }

    public AtomicSharedCache(CacheDirectoryGuard guard) {
        this(guard, AtomicReplacement::run);
    }

    public AtomicSharedCache(CacheDirectoryGuard guard, AtomicMove atomicMove) {
        this(guard.root(), atomicMove);
        this.guard = guard;
    }

    public CacheDirectoryGuard guard() throws IOException {
        var current = guard;
        if (current != null)
            return current;
        synchronized (this) {
            if (guard == null)
                guard = CacheDirectoryGuard.open(cacheRoot);
            return guard;
        }
    }

    public CacheDirectoryGuard.Observation observe(Path target, boolean createParents) throws IOException {
        var owner = guard();
        var absolute = target.toAbsolutePath().normalize();
        if (absolute.startsWith(cacheRoot))
            absolute = owner.root().resolve(cacheRoot.relativize(absolute));
        return owner.observe(absolute, createParents);
    }

    public FileChannel openRead(Path target) throws IOException {
        try {
            return observe(target, false).open(java.util.Set.of(StandardOpenOption.READ));
        } catch (java.nio.file.NoSuchFileException absent) {
            throw absent;
        } catch (IOException | SecurityException failure) {
            throw AssetLoadException.access("Failed to open guarded cache data: " + target, failure);
        }
    }

    public AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove) {
        this(cacheRoot, atomicMove, new DerivedStorageWork());
    }

    public AtomicSharedCache(Path cacheRoot, DerivedStorageWork derivedWork) {
        this(cacheRoot, AtomicReplacement::run, derivedWork);
    }

    public AtomicSharedCache(CacheDirectoryGuard guard, DerivedStorageWork derivedWork) {
        this(guard.root(), AtomicReplacement::run, derivedWork);
        this.guard = guard;
    }

    AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove, StagingFlush stagingFlush) {
        this(cacheRoot, atomicMove, new DerivedStorageWork(), stagingFlush);
    }

    private AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove, DerivedStorageWork derivedWork) {
        this(cacheRoot, atomicMove, derivedWork, CacheFile::force);
    }

    private AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove, DerivedStorageWork derivedWork,
        StagingFlush stagingFlush) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        this.atomicMove = Objects.requireNonNull(atomicMove, "atomicMove");
        this.stagingFlush = Objects.requireNonNull(stagingFlush, "stagingFlush");
        this.derivedWork = Objects.requireNonNull(derivedWork, "derivedWork");
    }

    public DerivedStorageWork derivedWork() {
        return derivedWork;
    }

    /** Key-lock directory of one shared-cache root. */
    static Path locksRoot(Path cacheRoot) {
        return cacheRoot.resolve(LOCK_DIRECTORY);
    }

    public void materialize(String namespace, String key, Path target, CacheValidator validator, CacheWriter writer)
        throws IOException {
        materialize(namespace, key, target, validator, writer, true);
    }

    /** Keeps an old exact object addressable until its verified replacement can commit. */
    public void materializeReplacing(String namespace, String key, Path target, CacheValidator validator,
        CacheWriter writer) throws IOException {
        materialize(namespace, key, target, validator, writer, false);
    }

    /** The caller linearizes the final replacement with retirement of its effect capability. */
    public void replaceValidated(String namespace, String key, Path target, CacheValidator validator,
        CacheWriter writer, CommitGate gate) throws IOException {
        materialize(namespace, key, target, validator, writer, false, Objects.requireNonNull(gate, "gate"), true);
    }

    private void materialize(String namespace, String key, Path target, CacheValidator validator, CacheWriter writer,
        boolean quarantineInvalid) throws IOException {
        materialize(namespace, key, target, validator, writer, quarantineInvalid, AtomicReplacement::run, false);
    }

    private void materialize(String namespace, String key, Path target, CacheValidator validator, CacheWriter writer,
        boolean quarantineInvalid, CommitGate gate, boolean replace) throws IOException {
        var observation = observe(target, true);
        withKeyLock(namespace, key, () -> {
            try (var parent = observation.acquire()) {
                var name = observation.target().getFileName().toString();
                if (!replace && isValid(parent, name, validator))
                    return null;
                if (quarantineInvalid) {
                    try {
                        parent.move(name, parent.fileIdentity(name), parent, name + ".corrupt-" + UUID.randomUUID());
                    } catch (java.nio.file.NoSuchFileException absent) {
                    }
                }
                var staging = new CacheFile(parent, name + ".tmp-" + UUID.randomUUID());
                boolean committed = false;
                Throwable failure = null;
                try {
                    writer.write(staging);
                    observation.verify();
                    staging.position(0);
                    if (!validator.validate(staging))
                        throw AssetLoadException
                            .content("Cache writer produced an invalid object: " + observation.target());
                    stagingFlush.force(staging);
                    gate.commit(() -> {
                        observation.verify();
                        atomicMove.move(() -> parent.move(staging.name(), staging.identity(), parent, name));
                    });
                    committed = true;
                    if ("baked".equals(namespace) || "baked-animation".equals(namespace))
                        ResourceDiagnostics.log("bake cache commit: {} namespace={} key={}", observation.target(),
                            namespace, key);
                } catch (IOException | RuntimeException | Error error) {
                    failure = error;
                    throw error;
                } finally {
                    try {
                        staging.release();
                        if (!committed)
                            staging.delete();
                    } catch (IOException cleanup) {
                        if (failure != null)
                            failure.addSuppressed(cleanup);
                        else
                            throw cleanup;
                    }
                }
            }
            return null;
        });
    }

    public <T> T withKeyLock(String namespace, String key, LockedOperation<T> operation) throws IOException {
        var lockFile = lockPath(namespace, key);
        var observation = observe(lockFile, true);
        return SharedCacheLocks.withLock(observation, operation);
    }

    private Path lockPath(String namespace, String key) {
        var digest = securityDigest((namespace + "\0" + key).getBytes(StandardCharsets.UTF_8));
        return locksRoot(cacheRoot).resolve(sanitize(namespace))
            .resolve(HexFormat.of().formatHex(digest) + LOCK_SUFFIX);
    }

    private static boolean isValid(com.elfmcys.ysm.util.DirectoryAccess parent, String name, CacheValidator validator)
        throws IOException {
        try (var channel = parent.file(name, false, false, false)) {
            return validator.validate(new CacheFile(channel));
        } catch (java.nio.file.NoSuchFileException absent) {
            return false;
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @FunctionalInterface
    public interface CacheValidator {
        boolean validate(CacheFile file) throws IOException;
    }

    @FunctionalInterface
    public interface CacheWriter {
        void write(CacheFile file) throws IOException;
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    public interface CommitGate {
        void commit(AtomicReplacement replacement) throws IOException;
    }

    @FunctionalInterface
    public interface AtomicReplacement {
        void run() throws IOException;
    }

    @FunctionalInterface
    public interface AtomicMove {
        void move(AtomicReplacement replacement) throws IOException;
    }

    @FunctionalInterface
    interface StagingFlush {
        void force(CacheFile file) throws IOException;
    }

    private static byte[] securityDigest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

}
