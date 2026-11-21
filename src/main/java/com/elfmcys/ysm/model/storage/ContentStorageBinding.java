package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Storage provenance captured when content is admitted, independent of its publication. */
public record ContentStorageBinding(CacheRegion region, Kind kind, Optional<SourceObservation> localSource,
    Optional<Converted> converted) {
    public ContentStorageBinding(CacheRegion region, Kind kind, Optional<SourceObservation> localSource) {
        this(region, kind, localSource, Optional.empty());
    }

    public ContentStorageBinding withConverted(ConvertedSourceIndex value, @Nullable ConvertedSourceIndexStore owner) {
        return new ContentStorageBinding(region, kind, localSource,
            Optional.of(new Converted(value.location(), owner)));
    }

    public void requireReadable() throws java.io.IOException {
        region.cache().coordinator().register();
    }

    /** The admitted source fixes the region; all source kinds share that region's bake authority. */
    public BakeIndexStore artifacts() {
        return region.cache().bakeIndex();
    }

    public ExpressionArtifactStore expressions() {
        return region.expressions(kind);
    }

    public com.elfmcys.ysm.model.resource.client.render.AnimationInputStore animations() {
        return region.animations();
    }

    public PreviewStore previews() {
        return region.previews(kind);
    }

    public <T> T acquire(CacheRootCoordinator.FileOperation<T> operation) throws java.io.IOException {
        return region.cache().coordinator().acquire(operation);
    }

    public void reportCorruption(java.util.function.Consumer<java.io.IOException> reporter) {
        if (converted.isEmpty() || converted.get().owner() == null)
            return;
        var value = converted.get();
        value.owner().revokeAsync(value.location()).whenComplete((result, failure) -> {
            if (failure != null)
                reporter.accept(new java.io.IOException("Converted revocation failed", failure));
            else if (!result.succeeded()
                && !(result.outcome() == com.elfmcys.ysm.natives.storage.StorageResult.Outcome.REJECTED
                    && result.failure() == com.elfmcys.ysm.natives.storage.StorageResult.Failure.NONE))
                reporter.accept(new java.io.IOException("Converted revocation failed: " + result));
        });
    }

    public record Converted(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location location,
        @Nullable ConvertedSourceIndexStore owner) {
    }

    public ContentStorageBinding {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(converted, "converted");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(localSource, "localSource");
        if ((kind == Kind.LOCAL) != localSource.isPresent())
            throw new IllegalArgumentException("Only local content carries a source observation");
    }

    public static ContentStorageBinding local(CacheRegion region, SourceObservation source) {
        return new ContentStorageBinding(region, Kind.LOCAL, Optional.of(source));
    }

    public static ContentStorageBinding builtin(CacheRegion region) {
        return new ContentStorageBinding(region, Kind.BUILTIN, Optional.empty());
    }

    public static ContentStorageBinding remote(CacheRegion region) {
        return new ContentStorageBinding(region, Kind.REMOTE, Optional.empty());
    }

    public enum Kind {
        LOCAL, BUILTIN, REMOTE
    }
}
