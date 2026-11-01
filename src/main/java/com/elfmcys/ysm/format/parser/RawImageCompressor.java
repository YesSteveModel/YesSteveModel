package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageEncoder;

import java.io.IOException;

final class RawImageCompressor {
    static final Policy LOSSLESS_TEXTURE = new Policy(Mode.LOSSLESS, 0, 0);
    static final Policy GUI_IMAGE = new Policy(Mode.LOSSY, 260, 450);
    static final Policy AUTHOR_AVATAR = new Policy(Mode.LOSSY, 320, 320);
    static final Policy MODEL_ICON = new Policy(Mode.LOSSY, 192, 192);
    static final Policy MODEL_THUMBNAIL = new Policy(Mode.LOSSY, 156, 270);

    private final Codec codec;

    RawImageCompressor() {
        this(new NativeCodec());
    }

    RawImageCompressor(Codec codec) {
        this.codec = codec;
    }

    Image compress(Image original, Policy policy, String path) {
        if (!policy.supports(original.format())) {
            return original.share();
        }

        try (var pixels = codec.decode(original)) {
            return codec.encode(pixels, original.width(), original.height(), policy);
        } catch (IOException error) {
            YesSteveModel.LOGGER.warn("Failed to recompress image {}; keeping the original", path, error);
            return original.share();
        }
    }

    enum Mode {
        LOSSLESS, LOSSY
    }

    record Policy(Mode mode, int maxWidth, int maxHeight) {
        boolean supports(Image.Format format) {
            return format == Image.Format.PNG;
        }
    }

    interface Codec {
        NativeStorage decode(Image image) throws IOException;

        Image encode(NativeBuffer pixels, int width, int height, Policy policy) throws IOException;
    }

    private static final class NativeCodec implements Codec {
        @Override
        public NativeStorage decode(Image image) throws IOException {
            return image.decodeToBuffer();
        }

        @Override
        public Image encode(NativeBuffer pixels, int width, int height, Policy policy) throws IOException {
            if (policy.mode() == Mode.LOSSLESS) {
                return ImageEncoder.encodeLossless(pixels, width, height);
            }
            return ImageEncoder.encodeLossy(pixels, width, height, policy.maxWidth(), policy.maxHeight());
        }
    }
}
