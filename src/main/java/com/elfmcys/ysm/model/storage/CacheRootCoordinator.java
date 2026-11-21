package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool;
import com.elfmcys.ysm.natives.storage.NativeCoordination;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shares one native registration and mutation gate per actual CACHE root in this JVM.
 *
 * <p>
 * Ownership: close seals this handle, cancels startup, and waits for its synchronous borrows. The last handle releases
 * registration. A delayed capability must acquire a fresh borrow when used.
 *
 * <p>
 * Execution: root gates never nest. Database callbacks must not acquire this gate; a caller may hold it while waiting
 * for finite database work. Cleanup callbacks borrow the continuous exclusive window until they return.
 *
 * <p>
 * Seam: implements root registration and local protection in [shared
 * storage](docs/architecture/model-management/storage/overview.md#共享存储与清理).
 */
public final class CacheRootCoordinator implements AutoCloseable {
    private static final Map<Path, Root> ROOTS = new HashMap<>();
    private final Path configuredRoot;
    private final Object cancellationGate = new Object();
    private NativeCancellation cancellation;
    private volatile boolean closing;
    private Root root;
    private int borrows;
    private boolean closed;

    public CacheRootCoordinator(Path root) {
        configuredRoot = root.toAbsolutePath().normalize();
    }

    /** Normal admission contention waits until released or cancelled, without a capability-failure timeout. */
    public void register() throws IOException {
        synchronized (this) {
            requireOpen();
            borrows++;
        }
        try {
            final NativeCancellation token;
            synchronized (cancellationGate) {
                if (closing)
                    throw new java.nio.channels.ClosedChannelException();
                if (cancellation == null)
                    cancellation = NativeCancellation.create();
                token = cancellation;
            }
            final Root state;
            synchronized (this) {
                if (root == null) {
                    var guard = CacheDirectoryGuard.open(configuredRoot);
                    var actual = guard.root().toRealPath();
                    synchronized (ROOTS) {
                        root = ROOTS.computeIfAbsent(actual, ignored -> new Root(guard));
                        root.handles++;
                    }
                }
                state = root;
            }
            state.register(token);
            if (closing || token.isRequested() || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Cache startup cancelled");
        } finally {
            release();
        }
    }

    public synchronized boolean registered() {
        return !closing && !closed && root != null && root.active();
    }

    public synchronized void requireActive() throws IOException {
        requireOpen();
        if (root == null || !root.active())
            throw new IOException("Cache root registration is unavailable");
    }

    private void requireOpen() throws java.nio.channels.ClosedChannelException {
        if (closing || closed)
            throw new java.nio.channels.ClosedChannelException();
    }

    public Use borrow() throws IOException {
        // Maintenance transactions must not wait for the file-mutation gate held by cleanup.
        if (!registered())
            register();
        synchronized (this) {
            requireActive();
            borrows++;
            return new Use();
        }
    }

    /** Serializes acquisition, retention, source updates, publication, and deletion within this root. */
    public <T> T acquire(FileOperation<T> operation) throws IOException {
        try (var ignored = borrow()) {
            synchronized (root) {
                requireActive();
                return operation.run();
            }
        }
    }

    public void retain(Pool pool, Location location) throws IOException {
        acquire(() -> {
            root.retained.add(new ObjectKey(pool, location));
            return null;
        });
    }

    /** Only valid inside acquire or cleanup; the root gate orders this snapshot with new retention. */
    public boolean retained(Pool pool, Location location) {
        if (root == null || !Thread.holdsLock(root))
            throw new IllegalStateException("Cache root mutation gate is not held");
        return root.retained.contains(new ObjectKey(pool, location));
    }

    public void requireCleanup() {
        if (root == null || !Thread.holdsLock(root) || !root.cleaning)
            throw new IllegalStateException("Continuous cache cleanup window is not held");
    }

    /**
     * Tries the unique-process window once. Admission contention or peer consumers skip the callback with protection
     * intact. The callback owns the complete converted/bake/maintenance sequence; this method restores SHARED before
     * releasing admission.
     */
    public <T> T cleanup(FileOperation<T> operation, T peerPresent) throws IOException {
        try (var ignored = borrow()) {
            synchronized (root) {
                requireActive();
                if (root.cleaning)
                    throw new IllegalStateException("Cache cleanup windows cannot nest");
                final StorageResult<Void> result;
                try {
                    result = root.registration.beginCleanup(cancellation);
                } catch (RuntimeException | Error failure) {
                    root.failed = true;
                    throw failure;
                }
                if (result.outcome() == StorageResult.Outcome.REJECTED)
                    return peerPresent;
                if (result.outcome() != StorageResult.Outcome.FOUND) {
                    if (result.failure() == StorageResult.Failure.CANCELLED)
                        throw new InterruptedIOException("Cache cleanup admission cancelled");
                    root.failed = true;
                    StorageIndex.require(result);
                    throw new IOException("Cache cleanup admission was not acquired");
                }
                Throwable failure = null;
                root.cleaning = true;
                try {
                    return operation.run();
                } catch (IOException | RuntimeException | Error next) {
                    failure = next;
                    throw next;
                } finally {
                    try {
                        var restored = root.registration.endCleanup();
                        if (!restored.succeeded() || !restored.committed()) {
                            root.failed = true;
                            throw new IOException("Cache consumer protection restoration failed: " + restored);
                        }
                        root.cleaning = false;
                    } catch (IOException | RuntimeException | Error next) {
                        root.failed = true;
                        if (failure != null)
                            failure.addSuppressed(next);
                        else
                            throw next;
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface FileOperation<T> {
        T run() throws IOException;
    }

    public enum PruneResult {
        COMPLETED, SKIPPED_ACTIVE_CONSUMER, SKIPPED_INCOMPLETE_SCOPE, FAILED
    }

    public final class Use implements AutoCloseable {
        private boolean released;

        private Use() {}

        @Override
        public void close() {
            synchronized (CacheRootCoordinator.this) {
                if (!released) {
                    released = true;
                    release();
                }
            }
        }
    }

    private synchronized void release() {
        borrows--;
        notifyAll();
    }

    /** Requests cancellation without waiting for a thread blocked in native startup or a root gate. */
    public void seal() {
        synchronized (cancellationGate) {
            closing = true;
            if (cancellation != null)
                cancellation.request();
        }
    }

    @Override
    public void close() {
        seal();
        boolean interrupted = false;
        synchronized (this) {
            while (borrows != 0) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (!closed) {
                closed = true;
                if (root != null) {
                    synchronized (ROOTS) {
                        if (--root.handles == 0) {
                            if (root.registration != null)
                                root.registration.close();
                            root.registration = null;
                        }
                    }
                }
                synchronized (cancellationGate) {
                    if (cancellation != null) {
                        cancellation.close();
                        cancellation = null;
                    }
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    private record ObjectKey(Pool pool, Location location) {
    }

    private static final class Root {
        private final CacheDirectoryGuard guard;
        private final Set<ObjectKey> retained = new HashSet<>();
        private volatile NativeCoordination registration;
        private int handles;
        private boolean opening;
        private boolean cleaning;
        private volatile boolean failed;

        private Root(CacheDirectoryGuard guard) {
            this.guard = guard;
        }

        private boolean active() {
            return registration != null && !failed;
        }

        private void register(NativeCancellation token) throws IOException {
            synchronized (this) {
                while (opening) {
                    if (token.isRequested() || Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException("Cache startup cancelled");
                    try {
                        wait(10);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Cache startup interrupted");
                    }
                }
                if (failed)
                    throw new IOException("Cache root is sealed after a coordination failure");
                if (registration != null)
                    return;
                opening = true;
            }
            NativeCoordination opened = null;
            try {
                guard.observe(guard.root().resolve("f/.guard"), true).verify();
                while (true) {
                    if (token.isRequested() || Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException("Cache startup cancelled");
                    try {
                        opened = NativeCoordination.open(guard.root().resolve("f"), token);
                        break;
                    } catch (StorageResult.OpenException busy) {
                        if (busy.result().failure() != StorageResult.Failure.RESOURCE_BUSY)
                            throw busy;
                    }
                }
                guard.verify();
                synchronized (this) {
                    registration = opened;
                    opened = null;
                }
            } catch (StorageResult.OpenException failure) {
                if (failure.result().failure() == StorageResult.Failure.CANCELLED)
                    throw new InterruptedIOException("Cache startup cancelled");
                throw new IOException("Cache root registration failed: " + failure.result(), failure);
            } finally {
                if (opened != null)
                    opened.close();
                synchronized (this) {
                    opening = false;
                    notifyAll();
                }
            }
        }
    }
}
