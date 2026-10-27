package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trusted metadata defines the smallest complete target preparation input. */
public final class RenderTargetChunkPlan {
    private RenderTargetChunkPlan() {}

    public static List<AssetContainerView.ChunkInfo> chunks(ModelFileView view, String targetId, String textureName)
        throws IOException {
        var target = view.requireRenderTarget(targetId);
        var selected = ModelManifestLookup.chooseTexture(view.getManifest(), targetId, textureName);
        var descriptors = new LinkedHashMap<String, AssetContainerView.ChunkInfo>();
        addBlob(descriptors, view, target.descriptor().blobId());
        var texture = target.textureDescriptor(selected);
        addBlob(descriptors, view, texture.uv().blobId());
        if (texture.hasNormal())
            addBlob(descriptors, view, texture.normalUnsafe().blobId());
        if (texture.hasSpecular())
            addBlob(descriptors, view, texture.specularUnsafe().blobId());
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() > 0)
            addBlob(descriptors, view, common.stringsBlobId());
        for (var sound : view.getCommon().sounds().values())
            add(descriptors, sound.chunkInfos().get(0));
        return descriptors.values().stream().sorted(Comparator.comparing(AssetContainerView.ChunkInfo::type)).toList();
    }

    private static void addBlob(Map<String, AssetContainerView.ChunkInfo> descriptors, ModelFileView view, int blobId)
        throws IOException {
        var name = AssetFileConstant.BLOB_CHUNK_PREFIX + blobId;
        var chunk = view.getFileView().getAssetView().getChunkInfo(name);
        if (chunk == null)
            throw AssetLoadException.content("Target chunk is missing: " + name);
        add(descriptors, chunk);
    }

    private static void add(Map<String, AssetContainerView.ChunkInfo> descriptors, AssetContainerView.ChunkInfo chunk)
        throws IOException {
        var previous = descriptors.putIfAbsent(chunk.type(), chunk);
        if (previous != null && (previous.offset() != chunk.offset() || previous.size() != chunk.size()
            || previous.decodeSize() != chunk.decodeSize() || !previous.encoding().equals(chunk.encoding())
            || !Arrays.equals(previous.hash(), chunk.hash()))) {
            throw AssetLoadException.content("Conflicting target chunk: " + chunk.type());
        }
    }
}
