package com.elfmcys.ysm.model.catalog.snapshot;

import java.util.Objects;

public record CatalogTransition(CatalogIndexSnapshot previousIndex, CatalogSnapshot previous,
    CatalogIndexSnapshot currentIndex, CatalogSnapshot current) {
    public CatalogTransition {
        Objects.requireNonNull(previousIndex, "previousIndex");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(currentIndex, "currentIndex");
        Objects.requireNonNull(current, "current");
        if (!previousIndex.packs().equals(previous.packs()) || !previousIndex.report().equals(previous.report())
            || !currentIndex.packs().equals(current.packs()) || !currentIndex.report().equals(current.report())) {
            throw new IllegalArgumentException("Catalog transition contains an unmatched index/content pair");
        }
    }
}
