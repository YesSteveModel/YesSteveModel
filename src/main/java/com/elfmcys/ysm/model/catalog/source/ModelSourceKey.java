package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.ModelPath;

import java.util.Objects;

public record ModelSourceKey(CatalogRootIdentity root, ModelPath relativePath,
    ModelSourceKind sourceKind) implements Comparable<ModelSourceKey> {
    public ModelSourceKey {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(sourceKind, "sourceKind");
    }

    @Override
    public int compareTo(ModelSourceKey other) {
        var rootOrder = root.compareTo(other.root);
        if (rootOrder != 0) {
            return rootOrder;
        }
        var pathOrder = relativePath.compareTo(other.relativePath);
        return pathOrder != 0 ? pathOrder : sourceKind.compareTo(other.sourceKind);
    }
}
