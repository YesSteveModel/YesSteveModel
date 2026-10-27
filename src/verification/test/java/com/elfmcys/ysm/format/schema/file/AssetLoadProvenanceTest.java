package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ChunkDecoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetLoadProvenanceTest {
    @TempDir
    Path temp;

    @Test
    void missingFileIsAccessFailure() {
        var source = new FileChunkDataSource(temp.resolve("missing.ysm"));
        var failure = assertThrows(AssetLoadException.class, () -> source.readPayload(chunk(1), BufferType.ARRAY));

        assertEquals(AssetLoadException.Reason.ACCESS, failure.reason());
    }

    @Test
    void invalidChunkSizeIsContentFailure() {
        try (var bytes = ArrayBuffer.move(new byte[] {1})) {
            var failure =
                assertThrows(AssetLoadException.class, () -> ChunkDecoding.validateDirectPayload(bytes, chunk(2)));

            assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        }
    }

    private static AssetContainerView.ChunkInfo chunk(int size) {
        return new AssetContainerView.ChunkInfo("test", "", 0, size, size, 0, 0, 0, null);
    }
}
