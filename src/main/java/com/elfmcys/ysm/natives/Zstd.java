package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Owned;
import com.elfmcys.ysm.natives.buffer.BufferArgument;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;

public final class Zstd {
    private static final int OP_COMPRESS = 1;
    private static final int OP_DECOMPRESS = 2;

    private Zstd() {}

    @Owned
    public static BufferStorage compressAndHash(UniBuffer source, byte @Nullable [] hash, BufferType outputType,
        int level) {
        var args = BufferArgument.packInput(source);
        final Object result;
        try {
            result = nZstd(args.obj(), args.flags(), hash, level, outputType.id(), OP_COMPRESS);
        } finally {
            Reference.reachabilityFence(source);
        }
        if (result == null) {
            throw new IllegalStateException("Native zstd compression returned no result");
        }
        return BufferArgument.unpackOutput(result);
    }

    @Owned
    public static BufferStorage decompressAndValidate(UniBuffer source, int outputSize, byte @Nullable [] hash,
        BufferType outputType) {
        var args = BufferArgument.packInput(source);
        final Object result;
        try {
            result = nZstd(args.obj(), args.flags(), hash, outputSize, outputType.id(), OP_DECOMPRESS);
        } finally {
            Reference.reachabilityFence(source);
        }
        if (result == null) {
            throw new IllegalStateException("Native zstd decompression returned no result");
        }
        return BufferArgument.unpackOutput(result);
    }

    private static native Object nZstd(Object source, long sourceFlags, byte @Nullable [] hashResult, int outputParam,
        int outputType, int op);
}
