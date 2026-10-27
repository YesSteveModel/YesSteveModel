package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.model.catalog.source.CatalogLocation;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.nio.file.Path;
import java.util.Objects;

/** Reopenable exact identity discovered without materializing model content. */
public record CatalogIndexEntry(ModelFileIdentity identity, CatalogLocation location, Path backingFile,
    ContentStorageBinding storageBinding) {
    public CatalogIndexEntry(ModelFileIdentity identity, CatalogLocation location, Path backingFile) {
        this(identity, location, backingFile, null);
    }

    public CatalogIndexEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        backingFile = Objects.requireNonNull(backingFile, "backingFile").toAbsolutePath().normalize();
    }

    public Hash256 modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }
}
