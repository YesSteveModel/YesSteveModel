package com.elfmcys.ysm.model.storage;

import java.io.IOException;
import java.util.Base64;

public final class TestPreviews {
    private static final byte[] BLANK_PNG = Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private TestPreviews() {}

    public static PreviewStore.PendingPreview pending() throws IOException {
        return PreviewStore.pending(com.elfmcys.ysm.buffer.ArrayBuffer.borrow(BLANK_PNG));
    }

    public static PreviewStore.EncodedPreview blank() throws IOException {
        return PreviewStore.decode(BLANK_PNG);
    }

    /** Publishes an independent fixture file, including deliberately invalid or missing media. */
    public static java.nio.file.Path index(java.nio.file.Path cacheRoot, com.elfmcys.ysm.model.domain.Hash256 id,
        java.nio.file.Path file) throws IOException {
        var target = TestCacheFiles.path(cacheRoot,
            com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind.PREVIEW, id.toString(), false);
        if (java.nio.file.Files.exists(file))
            java.nio.file.Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
