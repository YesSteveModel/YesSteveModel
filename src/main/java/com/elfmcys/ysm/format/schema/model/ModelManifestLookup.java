package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;

public final class ModelManifestLookup {
    private ModelManifestLookup() {}

    public static String chooseTexture(ManifestProto manifest, String targetId, String requested) {
        var target = target(manifest, targetId);
        if (requested != null && !requested.isBlank()) {
            if (!containsTexture(target, requested)) {
                throw new IllegalArgumentException("Unknown texture " + requested + " for render target " + targetId);
            }
            return requested;
        }
        var settings = manifest.info().settings();
        if (settings.hasDefaultTexture() && containsTexture(target, settings.defaultTextureUnsafe())) {
            return settings.defaultTextureUnsafe();
        }
        for (var entry : target.textures().object2ObjectEntrySet()) {
            return entry.getKey();
        }
        throw new IllegalArgumentException("Render target has no texture: " + targetId);
    }

    public static RenderTargetProto target(ManifestProto manifest, String targetId) {
        for (var target : manifest.renderTargets()) {
            if (target.targetId().equals(targetId)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Unknown render target: " + targetId);
    }

    private static boolean containsTexture(RenderTargetProto target, String name) {
        for (var entry : target.textures().object2ObjectEntrySet()) {
            if (entry.getKey().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
