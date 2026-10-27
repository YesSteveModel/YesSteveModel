package com.elfmcys.ysm.model.domain;

import java.time.Instant;
import java.util.List;

public record ModelScanReport(Instant startedAt, Instant completedAt, List<ModelScanError> errors,
    List<ModelScanWarning> warnings) {
    public ModelScanReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public ModelScanReport(Instant startedAt, Instant completedAt, List<ModelScanError> errors) {
        this(startedAt, completedAt, errors, List.of());
    }

    public static ModelScanReport empty() {
        var now = Instant.now();
        return new ModelScanReport(now, now, List.of(), List.of());
    }

    public int errorCount() {
        return errors.size();
    }

    public int warningCount() {
        return warnings.stream().mapToInt(ModelScanWarning::occurrences).sum();
    }
}
