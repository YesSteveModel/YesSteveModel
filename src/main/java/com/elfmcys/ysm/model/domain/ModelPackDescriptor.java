package com.elfmcys.ysm.model.domain;

import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import java.util.Map;
import java.util.Objects;

public record ModelPackDescriptor(CatalogRootKind rootKind, String hierarchy, String name, String description,
    Map<String, LocalizedText> translations, Hash256 coverHash, String coverFormat,
    int coverSize) implements Comparable<ModelPackDescriptor> {

    public ModelPackDescriptor {
        Objects.requireNonNull(rootKind, "rootKind");
        hierarchy = normalizeHierarchy(hierarchy);
        name = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        translations = Map.copyOf(translations);
        coverFormat = Objects.requireNonNullElse(coverFormat, "");
        if (coverSize < 0) {
            throw new IllegalArgumentException("Pack cover size cannot be negative");
        }
    }

    @Override
    public int compareTo(ModelPackDescriptor other) {
        return com.elfmcys.ysm.model.catalog.source.CatalogLocation.compareUtf8(hierarchy, other.hierarchy);
    }

    private static String normalizeHierarchy(String hierarchy) {
        return com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(hierarchy).value();
    }

    public record LocalizedText(String name, String description) {
        public LocalizedText {
            name = Objects.requireNonNullElse(name, "");
            description = Objects.requireNonNullElse(description, "");
        }
    }
}
