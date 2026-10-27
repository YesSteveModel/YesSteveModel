package com.elfmcys.ysm.model.catalog.source;

import java.nio.file.Path;
import java.util.Objects;

public record CatalogRootIdentity(CatalogRoot publicRoot,
    Path configuredAbsoluteRoot) implements Comparable<CatalogRootIdentity> {
    public CatalogRootIdentity {
        Objects.requireNonNull(publicRoot, "publicRoot");
        configuredAbsoluteRoot =
            Objects.requireNonNull(configuredAbsoluteRoot, "configuredAbsoluteRoot").toAbsolutePath().normalize();
    }

    public CatalogRootIdentity(CatalogRootKind kind, Path path) {
        this(CatalogRoot.local(kind), path);
    }

    public CatalogRootKind rootKind() {
        return publicRoot.kind();
    }

    public CatalogLocation location(com.elfmcys.ysm.model.domain.ModelPath path) {
        return new CatalogLocation(publicRoot, path);
    }

    @Override
    public int compareTo(CatalogRootIdentity other) {
        var kind = publicRoot.compareTo(other.publicRoot);
        if (kind != 0) {
            return kind;
        }
        return configuredAbsoluteRoot.toString().compareTo(other.configuredAbsoluteRoot.toString());
    }
}
