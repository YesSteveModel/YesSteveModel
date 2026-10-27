package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import java.util.Objects;
import java.util.Optional;

/** Storage provenance captured when content is admitted, independent of its publication. */
public record ContentStorageBinding(CacheRegion region, Kind kind, Optional<SourceObservation> localSource) {
    public ContentStorageBinding {
        Objects.requireNonNull(region, "region");
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
