package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.catalog.content.AssetRef;

import java.util.Objects;
import java.util.Optional;

public record CatalogPresentation(String displayName, String description, Optional<AssetRef> icon,
    Optional<AssetRef> preview) {
    public CatalogPresentation {
        displayName = Objects.requireNonNullElse(displayName, "");
        description = Objects.requireNonNullElse(description, "");
        icon = icon == null ? Optional.empty() : icon;
        preview = preview == null ? Optional.empty() : preview;
    }

    public static CatalogPresentation empty(String displayName) {
        return new CatalogPresentation(displayName, "", Optional.empty(), Optional.empty());
    }
}
