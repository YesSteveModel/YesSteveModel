package com.elfmcys.ysm.model.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Owns JVM serialization before opening a channel for the same lock file. */
final class SharedCacheLocks {
    private static final Map<Path, Entry> KEYS = new HashMap<>();
    private static final Set<Path> CONSUMERS = new HashSet<>();

    private SharedCacheLocks() {}

    static <T> T withLock(CacheDirectoryGuard.Observation observation,
        AtomicSharedCache.LockedOperation<T> operation) throws IOException {
        var path = observation.target();
        var entry = retain(path);
        boolean acquired = false;
        try {
            try {
                entry.lock.lockInterruptibly();
                acquired = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FileLockInterruptionException();
            }
            observation.verify();
            if (entry.lock.getHoldCount() > 1) return operation.run();
            try (var channel = observation.open(Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE)); var ignored = lock(channel)) {
                observation.verify();
                return operation.run();
            }
        } finally {
            if (acquired) entry.lock.unlock();
            synchronized (KEYS) {
                if (entry.users <= 0)
                    throw new IllegalStateException("Unbalanced cache lock");
                if (--entry.users == 0) KEYS.values().removeIf(value -> value == entry);
            }
        }
    }

    private static Entry retain(Path path) throws IOException {
        synchronized (KEYS) {
            var entry = KEYS.get(path);
            if (entry == null) {
                for (var candidate : KEYS.entrySet()) {
                    if (sameLockFile(candidate.getKey(), path)) {
                        entry = candidate.getValue();
                        break;
                    }
                }
            }
            if (entry == null) {
                entry = new Entry();
                KEYS.put(path, entry);
            }
            entry.users++;
            return entry;
        }
    }

    static boolean sameLockFile(Path first, Path second) throws IOException {
        // Directory aliases can survive real-path normalization (for example bind mounts).
        // Inspecting the parents avoids opening and closing an already locked consumer file.
        return first.equals(second) || first.getFileName().equals(second.getFileName())
            && Files.isSameFile(first.getParent(), second.getParent());
    }

    private static FileLock lock(FileChannel channel) throws IOException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new FileLockInterruptionException();
            try {
                var lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException busyInThisJvm) {
                // A lock obtained outside this facade still participates in the JDK lock table.
            }
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FileLockInterruptionException();
            }
        }
    }

    static void registerConsumer(Path path) throws IOException {
        synchronized (CONSUMERS) {
            if (isLocalConsumer(path)) throw new IllegalStateException("Duplicate cache consumer: " + path);
            CONSUMERS.add(path);
        }
    }

    static boolean isLocalConsumer(Path path) throws IOException {
        synchronized (CONSUMERS) {
            for (var consumer : CONSUMERS) if (sameLockFile(consumer, path)) return true;
            return false;
        }
    }

    static void unregisterConsumer(Path path) {
        synchronized (CONSUMERS) { CONSUMERS.remove(path); }
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
