package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.util.Objects;

/** The only persisted converted-source mapping. */
public record ConvertedSourceIndex(ModelFileIdentity identity, String rawRelativePath, String cacheVersion,
    com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location location, java.nio.file.Path file,
    com.elfmcys.ysm.buffer.SharedArrayBuffer observation, com.elfmcys.ysm.buffer.SharedArrayBuffer metadata) {
    public ConvertedSourceIndex {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(metadata, "metadata");
        rawRelativePath = normalizeRawRelativePath(rawRelativePath);
        cacheVersion = Objects.requireNonNull(cacheVersion, "cacheVersion");
        if (cacheVersion.isBlank()) {
            throw new IllegalArgumentException("Cache schema version must not be blank");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConvertedSourceIndex value && identity.equals(value.identity)
            && rawRelativePath.equals(value.rawRelativePath) && cacheVersion.equals(value.cacheVersion)
            && location.equals(value.location) && file.equals(value.file)
            && observation.nio().equals(value.observation.nio()) && metadata.nio().equals(value.metadata.nio());
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, rawRelativePath, cacheVersion, location, file, observation.nio(), metadata.nio());
    }

    public ModelId modelId() {
        return identity.modelId();
    }

    public java.util.UUID instance() {
        return location.instance();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }

    static String normalizeRawRelativePath(String value) {
        Objects.requireNonNull(value, "rawRelativePath");
        if (value.isEmpty() || value.startsWith("/") || value.endsWith("/") || value.indexOf('\\') >= 0
            || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid raw relative path: " + value);
        }
        for (var segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid raw relative path: " + value);
            }
        }
        return value;
    }
}
