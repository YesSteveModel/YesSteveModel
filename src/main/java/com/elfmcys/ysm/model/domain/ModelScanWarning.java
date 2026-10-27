package com.elfmcys.ysm.model.domain;

import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import java.util.Objects;

/** Non-fatal source-quality fact attached to one catalog build. */
public record ModelScanWarning(CatalogRootKind rootKind, String source, Kind kind, int occurrences) {
    public ModelScanWarning {
        Objects.requireNonNull(rootKind, "rootKind");
        source = Objects.requireNonNullElse(source, "");
        Objects.requireNonNull(kind, "kind");
        if (occurrences <= 0) {
            throw new IllegalArgumentException("Warning occurrences must be positive");
        }
    }

    public enum Kind {
        UNKNOWN_AUDIO, INVALID_AUDIO
    }
}
