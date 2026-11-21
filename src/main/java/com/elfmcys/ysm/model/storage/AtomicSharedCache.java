package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.NativeCacheRemote;
import com.elfmcys.ysm.format.AssetLoadException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location;
import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.NativeFileIdAllocator;

/**
 * Staging writer for one physical cache region. Complete files publish atomically; SQLite owns index transactions.
 * Concurrent generation may duplicate work and never holds an index transaction while writing payload.
 */
public final class AtomicSharedCache implements AutoCloseable {

    private final Path cacheRoot;
    private final AtomicMove atomicMove;
    private final StagingFlush stagingFlush;
    private volatile CacheDirectoryGuard guard;
    private final DerivedStorageWork derivedWork;
    private DatabaseWork databaseWork;
    private boolean ownsDatabaseWork;
    private final boolean ownsDerivedWork;
    private StorageIndex<NativeCacheCatalog> catalogIndex;
    private StorageIndex<NativeCacheRemote> remoteIndex;
    private BakeIndexStore bakeIndex;
    private final CacheRootCoordinator coordinator;
    private CacheDatabase<NativeCacheCatalog> catalog;
    private CacheDatabase<NativeFileIdAllocator> ids;

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
        if (!coordinator.registered())
            coordinator.register();
        var owner = guard();
        var absolute = target.toAbsolutePath().normalize();
        if (absolute.startsWith(cacheRoot))
            absolute = owner.root().resolve(cacheRoot.relativize(absolute));
        return owner.observe(absolute, createParents);
    }

    public FileChannel openRead(Path target) throws IOException {
        var use = coordinator.borrow();
        FileChannel channel = null;
        try {
            var owner = guard();
            var absolute = target.toAbsolutePath().normalize();
            if (absolute.startsWith(cacheRoot))
                absolute = owner.root().resolve(cacheRoot.relativize(absolute));
            channel = owner.openRead(absolute);
            return new CacheReadChannel(channel, use);
        } catch (java.nio.file.NoSuchFileException absent) {
            use.close();
            throw absent;
        } catch (IOException | SecurityException failure) {
            use.close();
            throw AssetLoadException.access("Failed to open guarded cache data: " + target, failure);
        } catch (RuntimeException | Error failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            use.close();
            throw failure;
        }
    }

    public AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove) {
        this(cacheRoot, atomicMove, new DerivedStorageWork(), CacheFile::force, true);
    }

    public AtomicSharedCache(Path cacheRoot, DerivedStorageWork derivedWork) {
        this(cacheRoot, AtomicReplacement::run, derivedWork);
    }

    public AtomicSharedCache(CacheDirectoryGuard guard, DerivedStorageWork derivedWork) {
        this(guard.root(), AtomicReplacement::run, derivedWork);
        this.guard = guard;
    }

    AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove, StagingFlush stagingFlush) {
        this(cacheRoot, atomicMove, new DerivedStorageWork(), stagingFlush, true);
    }

    private AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove, DerivedStorageWork derivedWork) {
        this(cacheRoot, atomicMove, derivedWork, CacheFile::force, false);
    }

    private AtomicSharedCache(Path cacheRoot, AtomicMove atomicMove, DerivedStorageWork derivedWork,
        StagingFlush stagingFlush, boolean ownsDerivedWork) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        this.atomicMove = Objects.requireNonNull(atomicMove, "atomicMove");
        this.stagingFlush = Objects.requireNonNull(stagingFlush, "stagingFlush");
        this.derivedWork = Objects.requireNonNull(derivedWork, "derivedWork");
        this.ownsDerivedWork = ownsDerivedWork;
        coordinator = new CacheRootCoordinator(this.cacheRoot);
    }

    public DerivedStorageWork derivedWork() {
        return derivedWork;
    }

    synchronized void databaseWork(DatabaseWork work) {
        if (databaseWork != null)
            throw new IllegalStateException("Cache database owner is already fixed");
        databaseWork = Objects.requireNonNull(work);
    }

    synchronized DatabaseWork databaseWork() {
        if (databaseWork == null) {
            databaseWork = new DatabaseWork();
            ownsDatabaseWork = true;
        }
        return databaseWork;
    }

    public synchronized StorageIndex<NativeCacheCatalog> catalogIndex() {
        if (catalogIndex == null)
            catalogIndex = new StorageIndex<>(this, NativeCacheCatalog::open);
        return catalogIndex;
    }

    public synchronized StorageIndex<NativeCacheRemote> remoteIndex() {
        if (remoteIndex == null)
            remoteIndex = new StorageIndex<>(this, NativeCacheRemote::open);
        return remoteIndex;
    }

    public synchronized BakeIndexStore bakeIndex() {
        if (bakeIndex == null)
            bakeIndex = new BakeIndexStore(this);
        return bakeIndex;
    }

    public Path root() {
        return cacheRoot;
    }

    public Path layoutRoot() {
        return cacheRoot.resolve("f");
    }

    public Path path(Pool pool, long id) {
        return layoutRoot().resolve(pool.directory()).resolve(CacheStorageTypes.formatFileId(id));
    }

    public CacheRootCoordinator coordinator() {
        return coordinator;
    }

    public synchronized CacheDatabase<NativeCacheCatalog> catalog() {
        if (catalog == null)
            catalog = new CacheDatabase<>(this, NativeCacheCatalog::open);
        return catalog;
    }

    private synchronized CacheDatabase<NativeFileIdAllocator> ids() {
        if (ids == null)
            ids = new CacheDatabase<>(this, NativeFileIdAllocator::open);
        return ids;
    }

    /** Reserves both names against allocator loss. Only the created staging entry belongs to this operation. */
    public Staging reserve(Pool pool) throws IOException {
        var use = coordinator.borrow();
        try {
            return coordinator.acquire(() -> {
                while (true) {
                    if (Thread.currentThread().isInterrupted())
                        throw new java.io.InterruptedIOException("Cache allocation interrupted");
                    var allocated = StorageIndex.require(ids().execute(NativeFileIdAllocator::allocate));
                    var location = new Location(allocated.rows().get(0), UUID.randomUUID());
                    // Physical deletion can precede ownership cleanup, including across allocator reconstruction.
                    if (pool == Pool.CONVERTED && StorageIndex
                        .require(catalog().read((db, token) -> db.ownsFile(location.fileId(), token))).rows().get(0)
                        || pool == Pool.BAKE && bakeIndex().ownsFile(location.fileId()))
                        continue;
                    var target = path(pool, location.fileId());
                    var observation = observe(target, true);
                    var parent = observation.acquire();
                    com.elfmcys.ysm.util.DirectoryAccess stagingParent = null;
                    CacheFile file = null;
                    try {
                        stagingParent = parent.directory("s", true);
                        file = new CacheFile(stagingParent, target.getFileName().toString());
                        file.ownedChannel();
                        try {
                            java.nio.file.Files.readAttributes(target,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            file.release();
                            file.delete();
                            stagingParent.close();
                            parent.close();
                            continue;
                        } catch (java.nio.file.NoSuchFileException free) {
                            coordinator.retain(pool, location);
                            return new Staging(pool, location, parent, stagingParent, file, use);
                        }
                    } catch (java.nio.file.FileAlreadyExistsException collision) {
                        if (stagingParent != null)
                            stagingParent.close();
                        parent.close();
                    } catch (IOException | RuntimeException | Error failure) {
                        if (file != null) {
                            try {
                                file.release();
                                file.delete();
                            } catch (IOException cleanup) {
                                failure.addSuppressed(cleanup);
                            }
                        }
                        if (stagingParent != null)
                            stagingParent.close();
                        parent.close();
                        throw failure;
                    }
                }
            });
        } catch (IOException | RuntimeException | Error failure) {
            use.close();
            throw failure;
        }
    }

    /** Caller owns this capability through compilation, validation and publication, including failed production. */
    public final class Staging implements AutoCloseable {
        private final Pool pool;
        private final Location location;
        private final com.elfmcys.ysm.util.DirectoryAccess parent;
        private final com.elfmcys.ysm.util.DirectoryAccess stagingParent;
        private final CacheFile file;
        private final CacheRootCoordinator.Use use;
        private boolean published;
        private boolean closed;

        private Staging(Pool pool, Location location, com.elfmcys.ysm.util.DirectoryAccess parent,
            com.elfmcys.ysm.util.DirectoryAccess stagingParent, CacheFile file, CacheRootCoordinator.Use use) {
            this.pool = pool;
            this.location = location;
            this.parent = parent;
            this.stagingParent = stagingParent;
            this.file = file;
            this.use = use;
        }

        public Location location() {
            return location;
        }

        public Path path() {
            return stagingParent.label().resolve(file.name());
        }

        public CacheFile file() {
            if (closed || published)
                throw new IllegalStateException("Cache staging is no longer writable");
            return file;
        }

        public Path publish(CacheValidator validator, CommitGate gate) throws IOException {
            if (closed || published)
                throw new IllegalStateException("Cache staging has already terminated");
            file.position(0);
            if (!validator.validate(file))
                throw AssetLoadException.content("Cache writer produced an invalid object: " + path());
            stagingFlush.force(file);
            coordinator.acquire(() -> {
                gate.commit(() -> {
                    parent.verify();
                    atomicMove.move(() -> stagingParent.move(file.name(), file.identity(), parent, file.name()));
                    published = true;
                });
                return null;
            });
            return AtomicSharedCache.this.path(pool, location.fileId());
        }

        @Override
        public void close() throws IOException {
            if (closed)
                return;
            closed = true;
            try (parent; stagingParent) {
                file.release();
                if (!published)
                    file.delete();
            } finally {
                use.close();
            }
        }
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

    @Override
    public void close() {
        coordinator.seal();
        final DatabaseWork owned;
        synchronized (this) {
            owned = ownsDatabaseWork ? databaseWork : null;
        }
        try {
            if (ownsDerivedWork)
                derivedWork.close();
        } finally {
            try {
                if (owned != null)
                    owned.close();
            } finally {
                coordinator.close();
            }
        }
    }

}
