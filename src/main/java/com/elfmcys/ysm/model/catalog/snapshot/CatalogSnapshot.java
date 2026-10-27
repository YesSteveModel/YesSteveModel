package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.catalog.content.ContentBinding;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable authoritative current-content map. */
public record CatalogSnapshot(Map<Hash256, CatalogRecord> byModelId, Map<HierarchyPath, Hash256> byPath,
    Map<CatalogLocation, Hash256> byLocation, List<ModelPackDescriptor> packs, ModelScanReport report) {
    public CatalogSnapshot(Map<Hash256, CatalogRecord> byModelId, List<ModelPackDescriptor> packs,
        ModelScanReport report) {
        this(validate(byModelId), paths(byModelId), locations(byModelId), List.copyOf(packs),
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
        return new CatalogSnapshot(intrinsicDefaultRecords(), packs, report);
    }

    /** Adds only the process-resident default from another side-local snapshot. */
    public CatalogSnapshot withIntrinsicDefaultFrom(CatalogSnapshot source) {
        Objects.requireNonNull(source, "source");
        var records = new LinkedHashMap<Hash256, CatalogRecord>(source.intrinsicDefaultRecords());
        records.putAll(byModelId);
        return new CatalogSnapshot(records, packs, report);
    }

    public Optional<ContentBinding> binding(Hash256 modelId) {
        var record = byModelId.get(modelId);
        return record == null ? Optional.empty() : Optional.of(record.binding());
    }

    public Optional<Hash256> resolve(CatalogLocation location) {
        return Optional.ofNullable(byLocation.get(location));
    }

    public Optional<Hash256> resolve(HierarchyPath path) {
        return Optional.ofNullable(byPath.get(path));
    }

    private Map<Hash256, CatalogRecord> intrinsicDefaultRecords() {
        var records = new LinkedHashMap<Hash256, CatalogRecord>();
        byModelId.forEach((modelId, record) -> {
            if (record.location().rootKind() == CatalogRootKind.BUILTIN
                && record.location().path().value().equals("default")) {
                records.put(modelId, record);
            }
        });
        return records;
    }

    private static Map<Hash256, CatalogRecord> validate(Map<Hash256, CatalogRecord> records) {
        Objects.requireNonNull(records, "byModelId");
        var result = new LinkedHashMap<Hash256, CatalogRecord>();
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

    private static Map<HierarchyPath, Hash256> paths(Map<Hash256, CatalogRecord> records) {
        var result = new LinkedHashMap<HierarchyPath, Hash256>();
        records.forEach((modelId, record) -> {
            if (result.putIfAbsent(record.entry().path(), modelId) != null) {
                throw new IllegalArgumentException("Duplicate catalog hierarchy path: " + record.entry().path());
            }
        });
        return result;
    }

    private static Map<CatalogLocation, Hash256> locations(Map<Hash256, CatalogRecord> records) {
        var result = new LinkedHashMap<CatalogLocation, Hash256>();
        records.forEach((modelId, record) -> {
            if (result.putIfAbsent(record.location(), modelId) != null) {
                throw new IllegalArgumentException("Duplicate catalog location: " + record.location());
            }
        });
        return result;
    }
}
