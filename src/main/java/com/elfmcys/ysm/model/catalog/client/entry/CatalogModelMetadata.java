package com.elfmcys.ysm.model.catalog.client.entry;

import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.format.schema.model.ModelManifestLookup;

import java.util.Objects;

/** Lightweight GUI metadata derived entirely from the catalog schema manifest. */
public record CatalogModelMetadata(Hash256 modelHash, String path, ModelRepresentation representation,
    ModelInfoView info) {
    public CatalogModelMetadata {
        Objects.requireNonNull(modelHash, "modelHash");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(info, "info");
    }

    public static CatalogModelMetadata from(ClientCatalogEntry entry) {
        var representation = entry.displayRepresentation();
        return new CatalogModelMetadata(entry.modelHash(), entry.displayPath(), representation,
            representation.view().getMetadata());
    }

    public String localized(String locale, String key, String fallback) {
        return info.translateOr(key, locale, fallback);
    }

    public String defaultTexture() {
        return ModelManifestLookup.chooseTexture(representation.view().getManifest(), RenderTargetIds.PLAYER, "");
    }
}
