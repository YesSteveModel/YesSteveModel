package com.elfmcys.ysm.format.container;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.natives.Zstd;

import java.io.IOException;

/** Applies and verifies the storage encoding declared by a chunk descriptor. */
public final class ChunkDecoding {
    private ChunkDecoding() {}

    public static BufferStorage decodeZstd(UniBuffer source, AssetContainerView.ChunkInfo chunk, BufferType outputType)
        throws IOException {
        validateStoredSize(source, chunk);
        try {
            return Zstd.decompressAndValidate(source, chunk.decodeSize(), chunk.hash(), outputType);
        } catch (RuntimeException error) {
            throw AssetLoadException.content(
                "Failed to decode chunk type=" + chunk.type() + " encoding=" + chunk.encoding() + " encodedSize="
                    + chunk.size() + " decodedSize=" + chunk.decodeSize() + " nativeStatus=" + error.getMessage(),
                error);
        }
    }

    public static boolean isZstd(AssetContainerView.ChunkInfo chunk) {
        return "zstd".equals(chunk.encoding());
    }

    /** Verifies a stored representation without changing or retaining it. */
    public static void validateStored(UniBuffer source, AssetContainerView.ChunkInfo chunk) throws IOException {
        validateStoredSize(source, chunk);
        if (isZstd(chunk)) {
            try (var ignored = decodeZstd(source, chunk, BufferType.NATIVE)) {
                return;
            }
        }
        validateDirectPayload(source, chunk);
    }

    /** Verifies an uncompressed or media-encoded payload whose stored bytes are its logical content. */
    public static void validateDirectPayload(UniBuffer source, AssetContainerView.ChunkInfo chunk) throws IOException {
        validateStoredSize(source, chunk);
        if (chunk.hash() != null && !Blake3.validateHash(source, chunk.hash())) {
            throw AssetLoadException.content("Incorrect chunk data hash: " + chunk.type());
        }
    }

    private static void validateStoredSize(UniBuffer source, AssetContainerView.ChunkInfo chunk) throws IOException {
        if (source.size() != chunk.size()) {
            throw AssetLoadException.content("Incorrect stored chunk size: " + chunk.type());
        }
    }
}
