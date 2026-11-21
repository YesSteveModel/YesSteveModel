package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;
import java.nio.file.Path;
import java.util.Objects;

/** Stores belonging to one cache region whose I/O pins a physical root. */
public final class CacheRegion implements AutoCloseable {
    private final Path root;
    private final AtomicSharedCache cache;
    private final ExpressionArtifactStore expressions;
    private final AnimationInputStore animations;
    private final PreviewStore previews;
    private final PreviewStore remotePreviews;
    private final DirectContainerIndex directories;
    private final CachePruner pruner;
    private final boolean ownsWork;
    private final DatabaseWork databases;
    private java.util.function.BiConsumer<com.elfmcys.ysm.model.domain.Hash256, Long> usage = (id, time) -> {
    };

    public CacheRegion(Path root) {
        this(root, null);
    }

    public CacheRegion(Path root, CacheDirectoryGuard guard) {
        this(root, guard, new DerivedStorageWork(), new DatabaseWork(), true);
    }

    public CacheRootCoordinator consumers() {
        return cache.coordinator();
    }

    public CachePruner pruner() {
        return pruner;
    }

    CacheRegion(Path root, CacheDirectoryGuard guard, DerivedStorageWork work) {
        this(root, guard, work, new DatabaseWork(), true);
    }

    CacheRegion(Path root, CacheDirectoryGuard guard, DerivedStorageWork work, DatabaseWork databases) {
        this(root, guard, work, databases, false);
    }

    private CacheRegion(Path root, CacheDirectoryGuard guard, DerivedStorageWork work, DatabaseWork databases,
        boolean ownsWork) {
        this.ownsWork = ownsWork;
        this.databases = databases;
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        cache = guard == null ? new AtomicSharedCache(this.root, work) : new AtomicSharedCache(guard, work);
        cache.databaseWork(databases);
        pruner = new CachePruner(cache);
        expressions = new ExpressionArtifactStore(this.root, cache);
        animations = new AnimationInputStore(this.root, cache);
        previews = new PreviewStore(this.root, cache);
        remotePreviews = new PreviewStore(this.root, cache, cache.remoteIndex());
        directories = new DirectContainerIndex(cache);
    }

    public void usageObserver(java.util.function.BiConsumer<com.elfmcys.ysm.model.domain.Hash256, Long> observer) {
        usage = Objects.requireNonNull(observer);
    }

    public void delivered(com.elfmcys.ysm.model.domain.Hash256 container, long utcSeconds) {
        usage.accept(container, utcSeconds);
    }

    public Path root() {
        return root;
    }

    Path cacheRootIdentity() {
        try {
            return cache.guard().root();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    public AtomicSharedCache cache() {
        return cache;
    }

    public ExpressionArtifactStore expressions() {
        return expressions;
    }

    public AnimationInputStore animations() {
        return animations;
    }

    ExpressionArtifactStore expressions(ContentStorageBinding.Kind kind) {
        return expressions;
    }

    public DirectContainerIndex directories() {
        return directories;
    }

    public PreviewStore previews() {
        return previews;
    }

    PreviewStore previews(ContentStorageBinding.Kind kind) {
        return kind == ContentStorageBinding.Kind.REMOTE ? remotePreviews : previews;
    }

    public java.util.List<PreviewStore> previewStores() {
        return java.util.List.of(previews, remotePreviews);
    }

    public void clearMemory() {
        expressions.clearMemory();
        animations.clearMemory();
    }

    @Override
    public void close() {
        if (ownsWork) {
            cache.derivedWork().close();
            databases.close();
            clearMemory();
        }
        cache.close();
    }
}
