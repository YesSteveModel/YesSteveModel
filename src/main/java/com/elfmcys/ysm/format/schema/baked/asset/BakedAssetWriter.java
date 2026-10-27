package com.elfmcys.ysm.format.schema.baked.asset;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.schema.file.AssetFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationEntryProto;
import com.elfmcys.ysm.proto.baked.asset.BakedAnimationProto;
import com.elfmcys.ysm.proto.baked.asset.BakedAssetManifestProto;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ProtoUtil;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes one immutable baked target asset with one zstd chunk per animation. */
public final class BakedAssetWriter extends AssetFileWriter {
    private static final int PAYLOAD_COMPRESSION_LEVEL = 9;

    public BakedAssetWriter() {
        setSchemaId(BakedAssetConstant.SCHEMA_ID);
        setProperty(BakedAssetConstant.PROP_VERSION, BakedAssetConstant.CURRENT_VERSION.toString());
    }

    public void setData(Hash256 containerId, String renderTargetId, Hash256 inputHash,
        Map<String, List<BakedAnimationProto>> animations, byte[] artifact) throws IOException {
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(inputHash, "inputHash");
        Objects.requireNonNull(animations, "animations");
        if (renderTargetId == null || renderTargetId.isBlank()) {
            throw new IOException("Baked asset render target id is empty");
        }
        var manifest = BakedAssetManifestProto.newBuilder().setRenderTargetId(renderTargetId)
            .setContainerId(ProtoBytes.wrap(containerId)).setInputHash(ProtoBytes.wrap(inputHash));
        var names = new HashSet<String>();
        var index = 0;
        for (var file : animations.entrySet()) {
            if (file.getKey().isBlank())
                throw new IOException("Baked animation source file is empty");
            for (var animation : file.getValue()) {
                if (animation == null || animation.name().isBlank()
                    || !names.add(BakedAnimationSet.key(file.getKey(), animation.name()))) {
                    throw new IOException("Duplicate or empty animation name in render target: "
                        + (animation == null ? "null" : animation.name()));
                }
                var chunkName = BakedAssetConstant.ANIM_CHUNK_PREFIX + "%06d".formatted(index++);
                var sourceHash = ModelHashing.blake3(ProtoUtil.serializeToArray(animation));
                var entry = BakedAnimationEntryProto.newBuilder().setName(animation.name()).setSourceFile(file.getKey())
                    .setChunkName(chunkName).setSourceHash(ProtoBytes.wrap(sourceHash)).build();
                manifest.addAnimations(entry);
                addProtoChunk(chunkName, animation, PAYLOAD_COMPRESSION_LEVEL);
            }
        }
        try (var bytes = ArrayBuffer.move(artifact)) {
            addRawChunk(BakedAssetConstant.EXPRESSION_CHUNK_NAME, bytes, PAYLOAD_COMPRESSION_LEVEL);
        }
        addProtoChunk(BakedAssetConstant.MANIFEST_CHUNK_NAME, manifest.build(), 0);
    }
}
