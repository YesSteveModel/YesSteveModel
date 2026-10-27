package com.elfmcys.ysm.model.catalog.client.entry;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;

import java.util.Objects;

/** Path-only disabled presentation; exact representation identity remains private. */
public record ClientFailedCatalogEntry(Hash256 modelId, HierarchyPath path, CatalogAccess access, String error) {
    public ClientFailedCatalogEntry {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
        error = Objects.requireNonNull(error, "error");
    }

    public String displayPath() {
        return path.value();
    }
}
