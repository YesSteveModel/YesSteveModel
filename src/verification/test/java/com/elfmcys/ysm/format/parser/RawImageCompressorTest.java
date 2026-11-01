package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.image.Image;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawImageCompressorTest {
    @Test
    void limitsSupportedInputFormatsByCompressionMode() {
        assertTrue(RawImageCompressor.LOSSLESS_TEXTURE.supports(Image.Format.PNG));
        assertFalse(RawImageCompressor.LOSSLESS_TEXTURE.supports(Image.Format.JPEG));
        assertFalse(RawImageCompressor.LOSSLESS_TEXTURE.supports(Image.Format.WEBP));

        assertTrue(RawImageCompressor.MODEL_ICON.supports(Image.Format.PNG));
        assertFalse(RawImageCompressor.MODEL_ICON.supports(Image.Format.JPEG));
        assertFalse(RawImageCompressor.MODEL_ICON.supports(Image.Format.WEBP));
        assertFalse(RawImageCompressor.MODEL_ICON.supports(Image.Format.AVIF));
        assertFalse(RawImageCompressor.MODEL_ICON.supports(Image.Format.ZTX));
    }

    @Test
    void skipsUnsupportedFormatsWithoutCallingTheCodec() {
        var codec = new FakeCodec(10);
        var compressor = new RawImageCompressor(codec);
        try (var original = image(Image.Format.WEBP, 100);
            var result = compressor.compress(original, RawImageCompressor.MODEL_ICON, "icon.webp")) {
            assertEquals(100, result.data().size());
            assertEquals(0, codec.encodeCalls);
        }
    }

    @Test
    void replacesPngEvenWhenEncodedRepresentationIsLargerAndForwardsLimits() {
        var codec = new FakeCodec(99);
        var compressor = new RawImageCompressor(codec);
        try (var original = image(Image.Format.PNG, 100);
            var result = compressor.compress(original, RawImageCompressor.MODEL_ICON, "icon.png")) {
            assertEquals(99, result.data().size());
            assertEquals(192, codec.policy.maxWidth());
            assertEquals(192, codec.policy.maxHeight());
        }

        codec.encodedSize = 101;
        try (var original = image(Image.Format.PNG, 100);
            var result = compressor.compress(original, RawImageCompressor.MODEL_ICON, "icon.png")) {
            assertEquals(101, result.data().size());
        }
    }

    @Test
    void keepsTheOriginalWhenRecompressionFails() {
        var codec = new FakeCodec(10);
        codec.fail = true;
        var compressor = new RawImageCompressor(codec);
        try (var original = image(Image.Format.PNG, 100);
            var result = compressor.compress(original, RawImageCompressor.MODEL_THUMBNAIL, "thumbnail.png")) {
            assertEquals(100, result.data().size());
        }
    }

    @Test
    void definesMigratedResourceLimits() {
        assertEquals(260, RawImageCompressor.GUI_IMAGE.maxWidth());
        assertEquals(450, RawImageCompressor.GUI_IMAGE.maxHeight());
        assertEquals(320, RawImageCompressor.AUTHOR_AVATAR.maxWidth());
        assertEquals(320, RawImageCompressor.AUTHOR_AVATAR.maxHeight());
        assertEquals(156, RawImageCompressor.MODEL_THUMBNAIL.maxWidth());
        assertEquals(270, RawImageCompressor.MODEL_THUMBNAIL.maxHeight());
    }

    private static Image image(Image.Format format, int size) {
        return Image.adopt(format, 32, 32, ArrayBuffer.move(new byte[size]));
    }

    private static final class FakeCodec implements RawImageCompressor.Codec {
        private int encodedSize;
        private int encodeCalls;
        private boolean fail;
        private RawImageCompressor.Policy policy;

        private FakeCodec(int encodedSize) {
            this.encodedSize = encodedSize;
        }

        @Override
        public NativeStorage decode(Image image) {
            return NativeBuffer.move(ByteBuffer.allocateDirect(image.width() * image.height() * 4));
        }

        @Override
        public Image encode(NativeBuffer pixels, int width, int height, RawImageCompressor.Policy policy)
            throws IOException {
            encodeCalls++;
            this.policy = policy;
            if (fail) {
                throw new IOException("test failure");
            }
            return Image.adopt(Image.Format.WEBP, width, height, ArrayBuffer.move(new byte[encodedSize]));
        }
    }
}
