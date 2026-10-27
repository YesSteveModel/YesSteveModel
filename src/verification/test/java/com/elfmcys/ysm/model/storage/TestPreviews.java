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
}
