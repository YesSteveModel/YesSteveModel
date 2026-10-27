package com.elfmcys.ysm.natives.image;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.buffer.BufferArgument;
import com.elfmcys.ysm.mixin.client.NativeImageAccessor;
import com.elfmcys.ysm.util.ScopeGuard;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;

import java.io.UnsupportedEncodingException;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record Image(Format format, int width, int height, UniBuffer data) implements AutoCloseable {
    private Image(long packedInfo, UniBuffer buffer) {
        this(Format.VALUES.get((int)(packedInfo >>> 32) & 0xFF), (int)(packedInfo >>> 16) & 0xFFFF,
            (int)(packedInfo & 0xFFFF), buffer);
    }

    public Image share() {
        return new Image(format, width, height, data.acquire());
    }

    @SuppressWarnings("DataFlowIssue")
    public NativeImage decode() throws UnsupportedEncodingException {
        var args = BufferArgument.packInput(data);
        try (var dstScope = ScopeGuard.create(new NativeImage(NativeImage.Format.RGBA, width, height, false))) {
            var dst = dstScope.get();
            var dstAccessor = (NativeImageAccessor)(Object)dst;
            final boolean result;
            try {
                result = Native.nDecode(args.obj(), args.flags(), format.id, width, height, dstAccessor.ysm$pixels(),
                    dstAccessor.ysm$size());
            } finally {
                Reference.reachabilityFence(data);
                Reference.reachabilityFence(dst);
            }
            if (result) {
                return dstScope.release();
            }
        }
        throw new UnsupportedEncodingException("Failed to decode image");
    }

    public NativeBuffer decodeToBuffer() throws UnsupportedEncodingException {
        var args = BufferArgument.packInput(data);
        try (var dstScope = NativeBuffer.allocateWithScope(width * height * 4)) {
            var dst = dstScope.get();
            final boolean result;
            try {
                result = Native.nDecode(args.obj(), args.flags(), format.id, width, height, dst.ptr(), dst.size());
            } finally {
                Reference.reachabilityFence(data);
                Reference.reachabilityFence(dst);
            }
            if (result) {
                return dstScope.release();
            }
        }
        throw new UnsupportedEncodingException("Failed to decode image");
    }

    public static Image probe(UniBuffer buffer) throws UnsupportedEncodingException {
        var args = BufferArgument.packInput(buffer);
        final long result;
        try {
            result = Native.nProbe(args.obj(), args.flags());
        } finally {
            Reference.reachabilityFence(buffer);
        }
        if (result == 0) {
            throw new UnsupportedEncodingException("Failed to read image");
        }
        return new Image(result, buffer.acquire());
    }

    @Override
    public void close() {
        data.close();
    }

    private static class Native {
        private static native long nProbe(Object input, long input_flags);

        private static native boolean nDecode(Object input, long input_flags, int format, int width, int height,
            long dst, long dst_size);
    }

    public enum Format {
        RGBA(0), PNG(1), JPEG(2), WEBP(3), AVIF(4), ZTX(5);

        private final int id;

        Format(int value) {
            id = value;
        }

        public int id() {
            return id;
        }

        public static final List<Format> VALUES = ReferenceLists.unmodifiable(ReferenceArrayList
            .wrap(Arrays.stream(Format.values()).sorted(Comparator.comparingInt(f -> f.id)).toArray(Format[]::new)));
    }
}
