package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable ordered index of reopenable local container identities. */
public final class CatalogIndexSnapshot {
    private final List<CatalogIndexEntry> entries;
    private final Map<Hash256, List<CatalogIndexEntry>> byModelId;
    private final List<ModelPackDescriptor> packs;
    private final ModelScanReport report;

    public CatalogIndexSnapshot(List<CatalogIndexEntry> entries, List<ModelPackDescriptor> packs,
        ModelScanReport report) {
        this.entries = List.copyOf(entries);
        this.packs = List.copyOf(packs);
        this.report = Objects.requireNonNull(report, "report");
        var candidates = new LinkedHashMap<Hash256, ArrayList<CatalogIndexEntry>>();
        for (var entry : this.entries) {
            Objects.requireNonNull(entry, "entry");
            candidates.computeIfAbsent(entry.modelId(), ignored -> new ArrayList<>()).add(entry);
        }
        var immutable = new LinkedHashMap<Hash256, List<CatalogIndexEntry>>();
        candidates.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        byModelId = Map.copyOf(immutable);
    }

    public static CatalogIndexSnapshot empty() {
        return new CatalogIndexSnapshot(List.of(), List.of(), ModelScanReport.empty());
    }

    public List<CatalogIndexEntry> entries() {
        return entries;
    }

    /** Returns stable same-model candidates with exact file identities first. */
    public List<CatalogIndexEntry> findCandidates(ModelFileIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        var candidates = byModelId.getOrDefault(identity.modelId(), List.of());
        if (candidates.size() < 2) {
            return candidates;
        }
        var ordered = new ArrayList<CatalogIndexEntry>(candidates.size());
        for (var candidate : candidates) {
            if (candidate.identity().equals(identity)) {
                ordered.add(candidate);
            }
        }
        for (var candidate : candidates) {
            if (!candidate.identity().equals(identity)) {
                ordered.add(candidate);
            }
        }
        return List.copyOf(ordered);
    }

    public List<ModelPackDescriptor> packs() {
        return packs;
    }

    public ModelScanReport report() {
        return report;
    }
}
