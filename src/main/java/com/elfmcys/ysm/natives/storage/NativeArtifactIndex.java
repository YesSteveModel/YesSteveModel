package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.File;

/** Integer-file exact index for preview or remote payloads. Bake has an independent authority. */
public interface NativeArtifactIndex extends AutoCloseable {
    enum ExactKind {
        METADATA(2), CHUNK(3), PREVIEW(4);

        private final int nativeId;

        ExactKind(int nativeId) {
            this.nativeId = nativeId;
        }

        int nativeId() {
            return nativeId;
        }
    }

    StorageResult<File> lookupExact(ExactKind kind, String exactKey, NativeCancellation cancelled);

    StorageResult<Void> publishExact(ExactKind kind, String exactKey, File file, NativeCancellation cancelled);

    @Override
    void close();
}
