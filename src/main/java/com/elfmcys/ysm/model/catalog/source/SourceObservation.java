package com.elfmcys.ysm.model.catalog.source;

import java.nio.file.Path;
import java.util.Objects;

public record SourceObservation(ModelSourceKey key, Path logicalPath, Path absolutePath, SourceStamp stamp,
    long estimatedBytes) {
    public SourceObservation {
        Objects.requireNonNull(key, "key");
        logicalPath = Objects.requireNonNull(logicalPath, "logicalPath").toAbsolutePath().normalize();
        absolutePath = Objects.requireNonNull(absolutePath, "absolutePath").toAbsolutePath().normalize();
        Objects.requireNonNull(stamp, "stamp");
        if (estimatedBytes < 0) {
            throw new IllegalArgumentException("estimatedBytes must not be negative");
        }
    }
}
