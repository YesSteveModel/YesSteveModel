package com.elfmcys.ysm.format.schema.file;

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

/** Immutable encoded lazy chunks. Every read returns caller-owned storage. */
public final class ResidentChunkDataSource implements ChunkDataSource {
    private final Map<String, ArrayBuffer> chunks;

    private ResidentChunkDataSource(Map<String, ArrayBuffer> chunks) {
        this.chunks = Map.copyOf(chunks);
    }

    public static ResidentChunkDataSource copyOf(ChunkDataSource source, AssetContainerView view,
        Set<String> retainedTypes) throws IOException {
        var result = new LinkedHashMap<String, ArrayBuffer>();
        try {
            for (var type : retainedTypes.stream().sorted().toList()) {
                var chunk = view.getChunkInfo(type);
                if (chunk == null) {
                    throw AssetLoadException.content("Resident chunk is missing: " + type);
                }
                try (var stored = source.readStoredVerified(chunk, BufferType.ARRAY)) {
                    result.put(type, stored.acquireArray());
                }
            }
            return new ResidentChunkDataSource(result);
        } catch (IOException | RuntimeException | Error failure) {
            result.values().forEach(ArrayBuffer::close);
            throw failure;
        }
    }

    /** Copies every stored chunk from a container whose envelope was already verified. */
    public static ResidentChunkDataSource fromContainer(byte[] container, AssetContainerView view) throws IOException {
        return fromContainer(ArrayBuffer.borrow(container), view);
    }

    /** Copies every stored chunk from a container whose envelope was already verified. */
    public static ResidentChunkDataSource fromContainer(UniBuffer container, AssetContainerView view)
        throws IOException {
        var result = new LinkedHashMap<String, ArrayBuffer>();
        try {
            for (var chunk : view.getChunkTable().values()) {
                var end = Math.addExact(chunk.offset(), chunk.size());
                if (chunk.offset() < 0 || end > container.size()) {
                    throw AssetLoadException.content("Resident chunk range exceeds the container: " + chunk.type());
                }
                var stored = ArrayBuffer.copyOf(container.slice(chunk.offset(), chunk.size()).nio());
                try {
                    if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                        ChunkDecoding.validateStored(stored, chunk);
                    }
                    result.put(chunk.type(), stored);
                } catch (IOException | RuntimeException | Error failure) {
                    stored.close();
                    throw failure;
                }
            }
            return new ResidentChunkDataSource(result);
        } catch (IOException | RuntimeException | Error failure) {
            result.values().forEach(ArrayBuffer::close);
            throw failure;
        }
    }

    @Override
    public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        if (!ChunkDecoding.isZstd(chunk)) {
            return readStoredVerified(chunk, bufferType);
        }
        try (var stored = copyStored(chunk, BufferType.NATIVE)) {
            return ChunkDecoding.decodeZstd(stored, chunk, bufferType);
        }
    }

    @Override
    public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        var readType = ChunkDecoding.isZstd(chunk) ? BufferType.NATIVE : bufferType;
        try (var stored = copyStored(chunk, readType)) {
            if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)) {
                ChunkDecoding.validateStored(stored, chunk);
            }
            return bufferType == BufferType.NATIVE ? stored.acquireNative() : stored.acquireArray();
        }
    }

    public Set<String> retainedTypes() {
        return chunks.keySet();
    }

    private ArrayBuffer require(AssetContainerView.ChunkInfo chunk) throws AssetLoadException {
        var bytes = chunks.get(chunk.type());
        if (bytes == null) {
            throw AssetLoadException
                .content("Chunk was preprocessed and its raw payload was discarded: " + chunk.type());
        }
        return bytes;
    }

    private UniBuffer copyStored(AssetContainerView.ChunkInfo chunk, BufferType bufferType) throws IOException {
        var bytes = require(chunk);
        if (bytes.size() != chunk.size()) {
            throw AssetLoadException.content("Resident chunk has an unexpected stored size: " + chunk.type());
        }
        var array = bytes.copy();
        if (bufferType == BufferType.ARRAY) {
            return array;
        }
        try (array) {
            return array.acquireNative();
        }
    }
}
