package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class AssetFileWriterSharingTest {
    @Test
    void clonedWriterKeepsSharedInputsAfterSourceWriterCloses() throws Exception {
        var input = SharedArrayBuffer.adopt(ArrayBuffer.move(new byte[] {1, 2, 3, 4}));
        var source = new AssetFileWriter();
        source.setSchemaId("test/shared");
        source.addRawChunk("raw", input, 0);
        source.addBlob(input, 0);
        var expected = write(source);
        try (var clone = new AssetFileWriter(source)) {
            source.close();
            assertEquals(3, input.nio().get(2));
            assertArrayEquals(expected, write(clone));
        }
        assertEquals(4, input.size());
    }

    private static byte[] write(AssetFileWriter writer) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var channel = Channels.newChannel(output)) {
            writer.write(channel);
        }
        return output.toByteArray();
    }
}
