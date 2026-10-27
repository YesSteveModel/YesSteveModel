package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.domain.ModelScanReport;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fully built worker result; it exposes no publication method. */
public record CatalogCandidate(CatalogIndexSnapshot index, CatalogSnapshot snapshot) {
    public CatalogCandidate {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!index.packs().equals(snapshot.packs()) || !index.report().equals(snapshot.report())) {
            throw new IllegalArgumentException("Catalog index and content snapshot metadata do not match");
        }
    }

    public static CatalogCandidate empty() {
        var report = ModelScanReport.empty();
        return new CatalogCandidate(new CatalogIndexSnapshot(List.of(), List.of(), report),
            new CatalogSnapshot(Map.of(), List.of(), report));
    }
}
