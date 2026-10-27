package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import java.util.Objects;
import java.util.Optional;

public record CatalogRecord(CatalogEntry entry, CatalogLocation location, ContentBinding binding) {
    public CatalogRecord {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(binding, "binding");
        if (!entry.modelId().equals(binding.modelId())) {
            throw new IllegalArgumentException("Catalog entry does not match its binding");
        }
        if (!entry.path().value().equals(location.value()))
            throw new IllegalArgumentException("Catalog entry does not match its publication location");
    }

    public CatalogRecord(CatalogLocation location, ContentBinding binding) {
        this(localEntry(location, binding), location, binding);
    }

    private static CatalogEntry localEntry(CatalogLocation location, ContentBinding binding) {
        var path = new HierarchyPath(location.value());
        var access = location.rootKind() == CatalogRootKind.AUTH ? CatalogAccess.AUTHORIZED : CatalogAccess.PUBLIC;
        var content = binding.content();
        var view = content.modelFile();
        var metadata = view.getMetadata().getMetadata();
        return new CatalogEntry(binding.modelId(), path, access,
            new CatalogPresentation(metadata == null ? "" : metadata.name(),
                metadata == null ? "" : metadata.tips().orElse(""),
                asset(view, ModelFileConstant.THUMB_ICON_CHUNK_NAME, AssetRef.Kind.ICON),
                asset(view, ModelFileConstant.THUMB_BUTTON_CHUNK_NAME, AssetRef.Kind.PREVIEW)));
    }

    private static Optional<AssetRef> asset(ModelFileView view, String name, AssetRef.Kind kind) {
        var chunk = view.getFileView().getAssetView().getChunkInfo(name);
        if (chunk == null || chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            return Optional.empty();
        }
        return Optional.of(
            new AssetRef(kind, name, new Hash256(chunk.hash()), chunk.size(), chunk.decodeSize(), chunk.encoding()));
    }
}
