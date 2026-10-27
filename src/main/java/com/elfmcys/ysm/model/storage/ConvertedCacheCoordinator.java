package com.elfmcys.ysm.model.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Process-lifetime converted consumer registration and one-shot prune admission. */
public final class ConvertedCacheCoordinator implements AutoCloseable {
    private static final String GATE_FILE = "admission.lck";
    private static final String CONSUMER_SUFFIX = ".consumer";

    /** Cross-process registration directory for live converted consumers. */
    private static final String CONSUMER_DIRECTORY = "converted-consumers";

    private final Path objectsRoot;
    private final AtomicSharedCache cache;
    private final Path coordinationRoot;
    private final Path gateFile;
    private FileChannel registrationChannel;
    private FileLock registrationLock;
    private Path registrationFile;
    private CacheDirectoryGuard.Observation registrationObservation;
    private Object registrationIdentity;
    private boolean pruneAttempted;

    public ConvertedCacheCoordinator(Path gameCacheRoot) {
        this.objectsRoot =
            Objects.requireNonNull(gameCacheRoot).resolve("converted/objects").toAbsolutePath().normalize();
        cache = new AtomicSharedCache(gameCacheRoot);
        coordinationRoot = AtomicSharedCache.locksRoot(gameCacheRoot).resolve(CONSUMER_DIRECTORY);
        gateFile = coordinationRoot.resolve(GATE_FILE);
    }

    public ConvertedCacheCoordinator(ConvertedScope scope, AtomicSharedCache cache) {
        objectsRoot = scope.root().resolve("objects");
        this.cache = Objects.requireNonNull(cache);
        coordinationRoot = scope.root().resolve("locks").resolve(CONSUMER_DIRECTORY);
        gateFile = coordinationRoot.resolve(GATE_FILE);
    }

    /** Waits only for a finite prune window, then registers until process shutdown. */
    public synchronized void register() throws IOException {
        if (registrationLock != null) {
            return;
        }
        var gateObservation = cache.observe(gateFile, true);
        SharedCacheLocks.withLock(gateObservation, () -> {
            gateObservation.verify();
            var file = gateObservation.target().getParent().resolve(UUID.randomUUID() + CONSUMER_SUFFIX);
            var observation = cache.observe(file, true);
            var channel = observation
                .open(Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE));
            Object identity = null;
            try {
                identity = observation.fileIdentity();
                var lock = channel.lock();
                SharedCacheLocks.registerConsumer(observation.target());
                registrationObservation = observation;
                registrationIdentity = identity;
                registrationFile = observation.target();
                registrationChannel = channel;
                registrationLock = lock;
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    channel.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    gateObservation.verify();
                    if (identity != null)
                        observation.deleteIfExists(identity);
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            return null;
        });
    }

    public synchronized boolean registered() {
        return registrationLock != null && registrationLock.isValid();
    }

    /**
     * Tries the only legal prune opportunity. Active peer consumers make the attempt a permanent skip; stale
     * registrations are removed while admission is closed.
     */
    public synchronized PruneResult pruneOnce(Set<Path> retainedObjects) {
        Objects.requireNonNull(retainedObjects, "retainedObjects");
        if (!registered()) {
            throw new IllegalStateException("Converted consumer is not registered");
        }
        if (pruneAttempted) {
            throw new IllegalStateException("Converted prune opportunity was already consumed");
        }
        pruneAttempted = true;
        try {
            var retained = new java.util.HashSet<Path>();
            for (var path : retainedObjects)
                retained.add(cache.observe(path, false).target());
            var gateObservation = cache.observe(gateFile, true);
            return SharedCacheLocks.withLock(gateObservation, () -> {
                gateObservation.verify();
                if (hasActivePeer()) {
                    return PruneResult.SKIPPED_ACTIVE_CONSUMER;
                }
                pruneObjects(retained);
                return PruneResult.COMPLETED;
            });
        } catch (IOException | RuntimeException failure) {
            return PruneResult.FAILED;
        }
    }

    private boolean hasActivePeer() throws IOException {
        var observation = cache.observe(coordinationRoot.resolve(".guard"), false);
        try (var directory = observation.acquire()) {
            for (var name : directory.names().stream().filter(n -> n.endsWith(CONSUMER_SUFFIX)).sorted().toList()) {
                if (directory.label().resolve(name).equals(registrationFile))
                    continue;
                observation.verify();
                // POSIX may release all process locks when any channel for the file is closed.
                if (SharedCacheLocks.isLocalConsumer(directory.label().resolve(name))) return true;
                var identity = directory.fileIdentity(name);
                try (var channel = directory.file(name, true, false, false)) {
                    FileLock lock;
                    try {
                        lock = channel.tryLock();
                    } catch (OverlappingFileLockException activeInThisJvm) {
                        return true;
                    }
                    if (lock == null)
                        return true;
                    lock.close();
                }
                observation.verify();
                directory.delete(name, identity, false);
            }
        }
        return false;
    }

    private void pruneObjects(Set<Path> retained) throws IOException {
        final CacheDirectoryGuard.Observation observation;
        try {
            observation = cache.observe(objectsRoot.resolve(".guard"), false);
        } catch (java.nio.file.NoSuchFileException absent) {
            return;
        }
        try (var root = observation.acquire()) {
            for (var name : root.names().stream().sorted().toList()) {
                final com.elfmcys.ysm.util.DirectoryAccess directory;
                try {
                    directory = root.directory(name, false);
                } catch (IOException inaccessibleOrLink) {
                    continue;
                }
                try (directory) {
                    var identity = directory.identity();
                    for (var file : directory.names().stream()
                        .filter(n -> n.endsWith(ConvertedObjectStore.CONTAINER_SUFFIX)).sorted().toList()) {
                        var path = directory.label().resolve(file);
                        if (retained.contains(path))
                            continue;
                        observation.verify();
                        final Object fileIdentity;
                        try {
                            fileIdentity = directory.fileIdentity(file);
                        } catch (IOException inaccessibleOrLink) {
                            continue;
                        }
                        directory.delete(file, fileIdentity, false);
                    }
                    if (directory.names().isEmpty()) {
                        observation.verify();
                        root.delete(name, identity, true);
                    }
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        var lock = registrationLock;
        var channel = registrationChannel;
        var file = registrationFile;
        var observation = registrationObservation;
        var identity = registrationIdentity;
        registrationObservation = null;
        registrationIdentity = null;
        registrationLock = null;
        registrationChannel = null;
        registrationFile = null;
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
        if (file != null) SharedCacheLocks.unregisterConsumer(file);
        try {
            if (file != null) {
                observation.deleteIfExists(identity);
            }
        } catch (IOException ignored) {
        }
    }

    public enum PruneResult {
        COMPLETED, SKIPPED_ACTIVE_CONSUMER, SKIPPED_INCOMPLETE_SCOPE, FAILED
    }
}
