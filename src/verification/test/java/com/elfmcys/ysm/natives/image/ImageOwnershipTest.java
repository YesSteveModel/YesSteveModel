package com.elfmcys.ysm.natives.image;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageOwnershipTest {
    @Test
    void imageViewsShareGcBytesAndCloseOnlyTheirOwnAccessGate() {
        var storage = ArrayBuffer.move(new byte[] {1, 2, 3, 4});
        var first = Image.adopt(Image.Format.RGBA, 1, 1, storage);
        var bytes = first.data();
        var shared = first.share();
        try {
            assertSame(bytes, shared.data());
            assertEquals(Image.Format.RGBA, shared.format());
            assertEquals(1, shared.width());
            assertEquals(1, shared.height());
            first.close();
            first.close();
            assertThrows(IllegalStateException.class, first::data);
            assertThrows(IllegalStateException.class, first::share);
            assertSame(bytes, shared.data());
            assertEquals(4, shared.data().size());
            shared.close();
            assertThrows(IllegalStateException.class, shared::data);
            var actual = new byte[bytes.size()];
            bytes.nio().get(actual);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
        } finally {
            first.close();
            shared.close();
        }
    }
}
