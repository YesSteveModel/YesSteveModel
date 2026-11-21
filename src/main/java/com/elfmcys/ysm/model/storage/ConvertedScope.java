package com.elfmcys.ysm.model.storage;

import java.nio.file.Path;
import java.util.Objects;

/** Logical DATA and schema identity, independent of the physical cache layout. */
public record ConvertedScope(String dataIdentity, String version) {
    public ConvertedScope {
        Objects.requireNonNull(dataIdentity);
        Objects.requireNonNull(version);
        if (version.isBlank())
            throw new IllegalArgumentException("Conversion schema must not be blank");
    }

    public static ConvertedScope of(Path dataRoot, String version) {
        var data = dataRoot.toAbsolutePath().normalize();
        return new ConvertedScope(data.getFileSystem().provider().getScheme() + "\0" + data, version);
    }
}
