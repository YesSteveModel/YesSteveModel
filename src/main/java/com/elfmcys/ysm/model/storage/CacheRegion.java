package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import java.nio.file.Path;
import java.util.Objects;

/** Stores belonging to one cache region whose I/O pins a physical root. */
public final class CacheRegion {
    private final Path root;
    private final AtomicSharedCache cache;
    private final ExpressionArtifactStore expressions;
    private final PreviewStore previews;

    public CacheRegion(Path root) {
        this(root, null);
    }

    public CacheRegion(Path root, CacheDirectoryGuard guard) {
        this(root, guard, new DerivedStorageWork());
    }

    CacheRegion(Path root, CacheDirectoryGuard guard, DerivedStorageWork work) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        cache = guard == null ? new AtomicSharedCache(this.root, work) : new AtomicSharedCache(guard, work);
        expressions = new ExpressionArtifactStore(this.root, cache);
        previews = new PreviewStore(this.root, cache);
    }

    public Path root() {
        return root;
    }

    public AtomicSharedCache cache() {
        return cache;
    }

    public ExpressionArtifactStore expressions() {
        return expressions;
    }

    public PreviewStore previews() {
        return previews;
    }
}
