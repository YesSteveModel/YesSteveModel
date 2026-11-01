package com.elfmcys.ysm.format.schema.file;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.ChunkDecoding;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Retains encoded chunks. Consumers must not modify shared read results. */
public final class ResidentChunkDataSource implements ChunkDataSource {
    private final Map<String, SharedArrayBuffer> chunks;

    private ResidentChunkDataSource(Map<String, SharedArrayBuffer> chunks) {
        this.chunks = Map.copyOf(chunks);
    }

    public static ResidentChunkDataSource copyOf(ChunkDataSource source, AssetContainerView view,
        Set<String> retainedTypes) throws IOException {
        var result = new LinkedHashMap<String, SharedArrayBuffer>();
        for (var type : retainedTypes.stream().sorted().toList()) {
            var chunk = view.getChunkInfo(type);
            if (chunk == null) {
                throw AssetLoadException.content("Resident chunk is missing: " + type);
            }
            result.put(type, source.readStoredVerified(chunk, BufferType.ARRAY).shareArray());
        }
        return new ResidentChunkDataSource(result);
    }

    /** Retains stored chunks from a verified container. Borrowed input is copied. */
    public static ResidentChunkDataSource fromContainer(byte[] container, AssetContainerView view) throws IOException {
        return fromContainer(ArrayBuffer.borrow(container), view);
    }

    /** Retains stored chunks from a verified container. Borrowed input is copied. */
    public static ResidentChunkDataSource fromContainer(UniBuffer container, AssetContainerView view)
        throws IOException {
        var shared = container.shareArray();
        var result = new LinkedHashMap<String, SharedArrayBuffer>();
        for (var chunk : view.getChunkTable().values()) {
            var end = Math.addExact(chunk.offset(), chunk.size());
            if (chunk.offset() < 0 || end > shared.size()) {
                throw AssetLoadException.content("Resident chunk range exceeds the container: " + chunk.type());
            }
            var stored = shared.ownedSlice(chunk.offset(), chunk.size());
            if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                ChunkDecoding.validateStored(stored, chunk);
            }
            result.put(chunk.type(), stored);
        }
        return new ResidentChunkDataSource(result);
    }

    @Override
    public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        if (!ChunkDecoding.isZstd(chunk)) {
            return readStoredVerified(chunk, bufferType);
        }
        var stored = require(chunk);
        return SharedBuffer.adopt(ChunkDecoding.decodeZstd(stored, chunk, bufferType));
    }

    @Override
    public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
        throws IOException {
        var stored = sharedStored(chunk, bufferType);
        if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
            ChunkDecoding.validateStored(stored, chunk);
        }
        return stored;
    }

    public Set<String> retainedTypes() {
        return chunks.keySet();
    }

    private SharedArrayBuffer require(AssetContainerView.ChunkInfo chunk) throws AssetLoadException {
        var bytes = chunks.get(chunk.type());
        if (bytes == null) {
            throw AssetLoadException
                .content("Chunk was preprocessed and its raw payload was discarded: " + chunk.type());
        }
        return bytes;
    }

    private SharedBuffer sharedStored(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        var bytes = require(chunk);
        if (bytes.size() != chunk.size()) {
            throw AssetLoadException.content("Resident chunk has an unexpected stored size: " + chunk.type());
        }
        return bufferType == BufferType.ARRAY ? bytes.shareArray() : bytes.shareNative();
    }
}
