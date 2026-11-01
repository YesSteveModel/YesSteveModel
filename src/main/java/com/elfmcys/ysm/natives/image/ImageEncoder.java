package com.elfmcys.ysm.natives.image;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.SharedNativeBuffer;
import com.elfmcys.ysm.mixin.client.NativeImageAccessor;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.lang.ref.Reference;

public class ImageEncoder {
    public static Image encodeLossy(NativeBuffer pixels, int width, int height, int maxWidth, int maxHeight)
        throws IOException {
        validatePixelBuffer(pixels.size(), width, height);
        return encode(pixels.ptr(), pixels, width, height, false, maxWidth, maxHeight);
    }

    public static Image encodeLossless(NativeBuffer pixels, int width, int height) throws IOException {
        validatePixelBuffer(pixels.size(), width, height);
        return encode(pixels.ptr(), pixels, width, height, true, 0, 0);
    }

    public static Image encodeLossy(NativeImage image, int maxWidth, int maxHeight) throws IOException {
        if (image.format() != NativeImage.Format.RGBA) {
            throw new UnsupportedOperationException("Image format not supported");
        }
        var accessor = (NativeImageAccessor)(Object)image;
        validatePixelBuffer(accessor.ysm$size(), image.getWidth(), image.getHeight());
        return encode(accessor.ysm$pixels(), image, image.getWidth(), image.getHeight(), false, maxWidth, maxHeight);
    }

    public static Image encodeLossless(NativeImage image) throws IOException {
        if (image.format() != NativeImage.Format.RGBA) {
            throw new UnsupportedOperationException("Image format not supported");
        }
        var accessor = (NativeImageAccessor)(Object)image;
        validatePixelBuffer(accessor.ysm$size(), image.getWidth(), image.getHeight());
        return encode(accessor.ysm$pixels(), image, image.getWidth(), image.getHeight(), true, 0, 0);
    }

    private static Image encode(long pixels, Object pixelsOwner, int width, int height, boolean lossless, int maxWidth,
        int maxHeight) throws IOException {
        var capacity = Math.toIntExact(requiredPixelBytes(width, height));
        try (var dstScope = NativeBuffer.allocateWithScope(capacity)) {
            var dst = dstScope.get();
            final long result;
            try {
                result = nEncode(pixels, width, height, dst.ptr(), dst.size(), lossless, maxWidth, maxHeight);
            } finally {
                Reference.reachabilityFence(pixelsOwner);
                Reference.reachabilityFence(dst);
            }
            if (result == 0) {
                throw new IOException("Failed to encode lossy image");
            }
            width = (int)((result >>> 48) & 0xFFFFL);
            height = (int)((result >>> 32) & 0xFFFFL);
            var format = (int)((result >>> 28) & 0xFL);
            var size = (int)(result & 0x0FFFFFFFL);
            return new Image(Image.Format.VALUES.get(format), width, height,
                SharedNativeBuffer.adopt(dstScope.release()).ownedSlice(0, size));
        }
    }

    private static void validatePixelBuffer(long size, int width, int height) {
        if (size < requiredPixelBytes(width, height)) {
            throw new IllegalArgumentException("Illegal pixels buffer size");
        }
    }

    private static long requiredPixelBytes(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        return Math.multiplyExact(Math.multiplyExact((long)width, height), 4L);
    }

    private static native long nEncode(long pixels, int width, int height, long dst, long dst_size, boolean lossless,
        int maxWidth, int maxHeight);
}
