package com.elfmcys.ysm.format.schema.baked.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.version.VersionCompatibility;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

public class BakedModelView {
    private final AssetContainerView assetView;
    private final GeoModelProto model;
    private final Hash256 bakeHash;

    public BakedModelView(SeekableByteChannel file) throws IOException {
        assetView = AssetContainerReader.read(file);
        if (!BakedModelConstant.SCHEMA_ID.equals(assetView.getSchema())) {
            throw new IOException("Schema ID mismatch: " + assetView.getSchema());
        }

        var version = Objects.requireNonNull(assetView.getSchemaProperty(BakedModelConstant.PROP_VERSION),
            "Version property not found");
        if (!VersionCompatibility.isCompatible(BakedModelConstant.CURRENT_VERSION.toString(), version, (current,
            candidate) -> new DefaultArtifactVersion(current).equals(new DefaultArtifactVersion(candidate)))) {
            throw new UnsupportedEncodingException(String.format("Unsupported baked model version: \"%s\".", version));
        }

        var manifestChunk = assetView.getChunkInfo(BakedModelConstant.MANIFEST_CHUNK_NAME);
        if (manifestChunk == null) {
            throw new IOException("No model index found");
        }
        try (var manifestData = InlineChunkReader.readPayload(file, manifestChunk, BufferType.ARRAY)) {
            if (manifestData.size() == 0) {
                throw new IOException("No model index found");
            }
            if (!(manifestData instanceof ArrayBuffer arrayBuffer)) {
                throw new IOException("Baked model manifest is not array-backed");
            }
            model = GeoModelProto.parseFrom(ProtoUtil.source(arrayBuffer));
        }
        var hashChunk = assetView.getChunkInfo(BakedModelConstant.BAKE_HASH_CHUNK_NAME);
        if (hashChunk == null) {
            throw new IOException("Invalid bake hash");
        }
        try (var hashData = InlineChunkReader.readPayload(file, hashChunk, BufferType.ARRAY)) {
            if (hashData.size() != Blake3.HASH_SIZE) {
                throw new IOException("Invalid bake hash");
            }
            if (!(hashData instanceof ArrayBuffer arrayBuffer)) {
                throw new IOException("Baked model bake hash is not array-backed");
            }
            bakeHash = new Hash256(arrayBuffer.array(), arrayBuffer.arrayOffset(), arrayBuffer.size());
        }
    }

    public GeoModelProto model() {
        return model;
    }

    public Hash256 bakeHash() {
        return bakeHash;
    }

    @Nullable
    public UniBuffer readModelData(SeekableByteChannel file) throws IOException {
        var chunk = assetView.getChunkInfo(BakedModelConstant.MODEL_CHUNK_NAME);
        return chunk == null ? null : InlineChunkReader.readPayload(file, chunk, BufferType.NATIVE);
    }
}
