package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable authoritative current-content map. */
public record CatalogSnapshot(Map<ModelId, CatalogRecord> byModelId, Map<HierarchyPath, ModelId> byPath,
    Map<CatalogLocation, ModelId> byLocation, CatalogRecord intrinsicDefault, List<ModelPackDescriptor> packs,
    ModelScanReport report) {
    public CatalogSnapshot(Map<ModelId, CatalogRecord> byModelId, List<ModelPackDescriptor> packs,
        ModelScanReport report) {
        this(byModelId, null, packs, report);
    }

    public CatalogSnapshot(Map<ModelId, CatalogRecord> byModelId, CatalogRecord intrinsicDefault,
        List<ModelPackDescriptor> packs, ModelScanReport report) {
        this(validate(byModelId), paths(byModelId), locations(byModelId), intrinsicDefault, List.copyOf(packs),
            Objects.requireNonNull(report, "report"));
    }

    public CatalogSnapshot {
        byModelId = validate(byModelId);
        var expectedPaths = paths(byModelId);
        if (!expectedPaths.equals(byPath)) {
            throw new IllegalArgumentException("Catalog path index does not match its content map");
        }
        var expectedLocations = locations(byModelId);
        if (!expectedLocations.equals(byLocation)) {
            throw new IllegalArgumentException("Catalog location index does not match its content map");
        }
        byModelId = Map.copyOf(byModelId);
        byPath = Map.copyOf(expectedPaths);
        byLocation = Map.copyOf(expectedLocations);
        packs = List.copyOf(packs);
        Objects.requireNonNull(report, "report");
    }

    public static CatalogSnapshot empty() {
        return new CatalogSnapshot(Map.of(), List.of(), ModelScanReport.empty());
    }

    /** Retains the one process-resident model while dropping every optional view. */
    public CatalogSnapshot intrinsicDefaultOnly() {
        return new CatalogSnapshot(Map.of(), intrinsicDefault, List.of(), report);
    }

    /** Adds only the process-resident default from another side-local snapshot. */
    public CatalogSnapshot withIntrinsicDefaultFrom(CatalogSnapshot source) {
        Objects.requireNonNull(source, "source");
        return new CatalogSnapshot(byModelId, source.intrinsicDefault, packs, report);
    }

    public Optional<ContentBinding> binding(PublishedModelId modelId) {
        return modelId instanceof PublishedModelId.Model model ? binding(model.hash())
            : Optional.ofNullable(intrinsicDefault).map(CatalogRecord::binding);
    }

    public Optional<ContentBinding> binding(ModelId modelId) {
        var record = byModelId.get(modelId);
        return record == null ? Optional.empty() : Optional.of(record.binding());
    }

    public Optional<ModelId> resolve(CatalogLocation location) {
        return Optional.ofNullable(byLocation.get(location));
    }

    public Optional<ModelId> resolve(HierarchyPath path) {
        return Optional.ofNullable(byPath.get(path));
    }

    private static Map<ModelId, CatalogRecord> validate(Map<ModelId, CatalogRecord> records) {
        Objects.requireNonNull(records, "byModelId");
        var result = new LinkedHashMap<ModelId, CatalogRecord>();
        records.forEach((modelId, record) -> {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(record, "record");
            if (!modelId.equals(record.binding().modelId())) {
                throw new IllegalArgumentException("Catalog key does not match its binding");
            }
            if (!modelId.equals(record.entry().modelId())) {
                throw new IllegalArgumentException("Catalog key does not match its entry");
            }
            result.put(modelId, record);
        });
        return result;
    }

    private static Map<HierarchyPath, ModelId> paths(Map<ModelId, CatalogRecord> records) {
        var result = new LinkedHashMap<HierarchyPath, ModelId>();
        records.forEach((modelId, record) -> {
            if (result.putIfAbsent(record.entry().path(), modelId) != null) {
                throw new IllegalArgumentException("Duplicate catalog hierarchy path: " + record.entry().path());
            }
        });
        return result;
    }

    private static Map<CatalogLocation, ModelId> locations(Map<ModelId, CatalogRecord> records) {
        var result = new LinkedHashMap<CatalogLocation, ModelId>();
        records.forEach((modelId, record) -> {
            if (result.putIfAbsent(record.location(), modelId) != null) {
                throw new IllegalArgumentException("Duplicate catalog location: " + record.location());
            }
        });
        return result;
    }
}
