package com.elfmcys.ysm.model.catalog.source;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

public record PackObservation(ModelPackSourceKey key, Path directory, File manifest, @Nullable File cover) {
    public PackObservation {
        Objects.requireNonNull(key, "key");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
    }

    public record File(Path target, SourceStamp.File stamp) {
        public File {
            target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
            Objects.requireNonNull(stamp, "stamp");
        }
    }
}
