package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.storage.ManagedContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Server query projection over the side-local authoritative Catalog snapshot. */
public final class ServerCatalog {
    private final CatalogSnapshot catalog;

    public ServerCatalog(CatalogSnapshot catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Map<ModelId, ManagedContainer> models() {
        var result = new LinkedHashMap<ModelId, ManagedContainer>();
        catalog.byModelId()
            .forEach((modelId, record) -> result.put(modelId, (ManagedContainer)record.binding().content()));
        return Map.copyOf(result);
    }

    public List<ModelPackDescriptor> packs() {
        return catalog.packs();
    }

    public ModelScanReport report() {
        return catalog.report();
    }

    public Optional<ManagedContainer> find(ModelId modelId) {
        return catalog.binding(modelId).map(ContentBinding::content).map(ManagedContainer.class::cast);
    }

    public Optional<ManagedContainer> find(PublishedModelId modelId) {
        return catalog.binding(modelId).map(ContentBinding::content).map(ManagedContainer.class::cast);
    }

    public Optional<ManagedContainer> find(CatalogLocation location) {
        return catalog.resolve(location).flatMap(this::find);
    }

    public Optional<ManagedContainer> findPath(String path) {
        return resolvePath(path).found().flatMap(this::find);
    }

    public com.elfmcys.ysm.model.catalog.source.ModelSelector.Result resolvePath(String path) {
        return com.elfmcys.ysm.model.catalog.source.ModelSelector.resolve(path, catalog.byLocation());
    }

    public Optional<ManagedContainer> defaultModel() {
        return find(PublishedModelId.INTRINSIC_DEFAULT);
    }

    public CatalogSnapshot catalog() {
        return catalog;
    }
}
