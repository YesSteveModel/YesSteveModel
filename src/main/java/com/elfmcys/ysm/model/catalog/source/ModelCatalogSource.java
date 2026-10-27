package com.elfmcys.ysm.model.catalog.source;

import java.nio.file.Path;
import java.util.Objects;

public record ModelCatalogSource(CatalogRoot publicRoot, Path path, boolean createIfMissing) {
    public ModelCatalogSource {
        Objects.requireNonNull(publicRoot, "publicRoot");
        Objects.requireNonNull(path, "path");
    }

    public ModelCatalogSource(CatalogRootKind kind, Path path, boolean createIfMissing) {
        this(CatalogRoot.local(kind), path, createIfMissing);
    }

    public CatalogRootKind rootKind() {
        return publicRoot.kind();
    }

    public CatalogRootIdentity identity() {
        return new CatalogRootIdentity(publicRoot, path);
    }
}
