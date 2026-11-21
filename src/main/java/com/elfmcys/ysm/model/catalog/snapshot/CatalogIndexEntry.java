package com.elfmcys.ysm.model.catalog.snapshot;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.model.catalog.source.CatalogLocation;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.nio.file.Path;
import java.util.Objects;

/** Reopenable exact identity discovered without materializing model content. */
public record CatalogIndexEntry(ModelFileIdentity identity, CatalogLocation location, Path backingFile,
    ContentStorageBinding storageBinding, com.elfmcys.ysm.model.storage.ConvertedSourceIndex convertedIndex) {
    public CatalogIndexEntry(ModelFileIdentity identity, CatalogLocation location, Path backingFile) {
        this(identity, location, backingFile, null, null);
    }

    public CatalogIndexEntry(ModelFileIdentity identity, CatalogLocation location, Path backingFile,
        ContentStorageBinding storageBinding) {
        this(identity, location, backingFile, storageBinding, null);
    }

    public CatalogIndexEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        backingFile = Objects.requireNonNull(backingFile, "backingFile").toAbsolutePath().normalize();
    }

    public ModelId modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }
}
