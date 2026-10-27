package com.elfmcys.ysm.model.catalog.content;

import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelRepresentation;

/** Immutable content projection exposed to resource loading. */
public interface ModelContent {
    ModelRepresentation representation();

    default ContentStorageBinding storageBinding() {
        return null;
    }

    default Hash256 modelId() {
        return representation().identity().modelId();
    }

    default ModelFileView modelFile() {
        return representation().view();
    }

    ChunkDataSource chunks();
}
