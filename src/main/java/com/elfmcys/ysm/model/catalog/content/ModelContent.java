package com.elfmcys.ysm.model.catalog.content;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.util.Closeable;

import java.io.IOException;

/**
 * Immutable content projection exposed to resource loading.
 */
public interface ModelContent {
    ModelRepresentation representation();

    default ContentStorageBinding storageBinding() {
        return null;
    }

    default ModelId modelId() {
        return representation().identity().modelId();
    }

    default ModelFileView modelFile() {
        return representation().view();
    }

    ChunkDataSource chunks();

    /**
     * Retains input capability independently of the requesting use. File-backed inputs retain their read capability,
     * not a snapshot of externally mutable files. The preparation owner closes this retention after its actual work
     * ends; cancelling an observable result does not end an active read.
     *
     * @return an independent retention, established before asynchronous work is admitted
     * @throws IOException if the input capability cannot be retained
     */
    default PreparationInputs retainPreparationInputs() throws IOException {
        return PreparationInputs
            .adopt(new RetainedModelContent(representation().acquire(), chunks(), storageBinding()));
    }

    /**
     * A fixed preparation owner's retention; derived immutable read capabilities can retain shared bytes.
     */
    final class PreparationInputs implements Closeable {
        private ModelContent content;

        private PreparationInputs(ModelContent content) {
            this.content = content;
        }

        /**
         * Adopts independently retained metadata and a read capability with no shared close authority.
         */
        public static PreparationInputs adopt(ModelContent retained) {
            return new PreparationInputs(retained);
        }

        public ModelContent content() {
            return content;
        }

        @Override
        public void close() {
            var ended = content;
            content = null;
            if (ended != null)
                ended.representation().close();
        }
    }
}

record RetainedModelContent(ModelRepresentation representation, ChunkDataSource chunks,
    ContentStorageBinding storageBinding) implements ModelContent {
}
