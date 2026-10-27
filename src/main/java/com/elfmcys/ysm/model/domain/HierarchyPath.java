package com.elfmcys.ysm.model.domain;

import java.util.Objects;

/** Canonical, case-sensitive catalog path. */
public record HierarchyPath(String value) implements Comparable<HierarchyPath> {
    public HierarchyPath {
        com.elfmcys.ysm.model.catalog.source.CatalogLocation.parse(value);
    }

    @Override
    public int compareTo(HierarchyPath other) {
        return com.elfmcys.ysm.model.catalog.source.CatalogLocation.compareUtf8(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
