package com.elfmcys.ysm.model.catalog.builtin;

import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.util.ProtoUtil;
import us.hebi.quickbuf.ProtoSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Build-safe semantic materialization. It never registers textures or writes bake caches. */
public final class BuiltinModelMaterializer {
    private static final int CURRENT_RAW_UV_VERSION = 29;

    private BuiltinModelMaterializer() {}

    public static Result materialize(ManagedContainer handle) throws IOException {
        return materialize(handle, (targetId, inventory) -> {
        });
    }

    public static Result materialize(ManagedContainer handle, TargetProgramVerifier verifier) throws IOException {
        Objects.requireNonNull(verifier, "verifier");
        var view = handle.view();
        var chunks = handle.chunks();
        decodeNamedImage(view, chunks, ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        decodeNamedImage(view, chunks, ModelFileConstant.THUMB_ICON_CHUNK_NAME);
        decodePresentationImages(view, chunks);
        var strings = readCommonStrings(view, chunks);

        var animationHashes = new LinkedHashMap<DefaultAnimationKey, Hash256>();
        var programManifest = new ArrayList<ModelProgramInventory.ManifestEntry>();
        var candidateCount = 0;
        var executableCount = 0;
        var literalCount = 0;
        var programCount = 0;
        var fallbackCount = 0;
        var failureCount = 0;
        var callableEntryCount = 0;
        var eventEntryCount = 0;
        var rejectedSuffixCount = 0;
        for (var target : view.getRenderTargets()) {
            var definition = readDefinition(view, chunks, target.descriptor().blobId());
            try {
                var info = view.getManifest().info();
                var sets =
                    target.descriptor().kind() == com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.PLAYER
                        ? ExpressionSet.values() : new ExpressionSet[] {ExpressionSet.MAIN};
                for (var set : sets) {
                    var inventory = ModelProgramInventory.collect(target.id(), set, set.select(definition), strings,
                        info.settings(), MolangEventCatalog.production());
                    verifier.verify(target.id(), inventory);
                    programManifest.addAll(inventory.manifest());
                    candidateCount += inventory.counts().candidates();
                    executableCount += inventory.counts().executables();
                    literalCount += inventory.counts().literals();
                    programCount += inventory.counts().programs();
                    fallbackCount += inventory.counts().fallbacks();
                    failureCount += inventory.counts().failures();
                    callableEntryCount += inventory.counts().callableEntries();
                    eventEntryCount += inventory.counts().eventEntries();
                    rejectedSuffixCount += inventory.counts().rejectedSuffixes();
                }
            } catch (ModelProgramInventory.InventoryException error) {
                throw new IOException("Invalid persistent-program inventory for target " + target.id(), error);
            } catch (Exception error) {
                throw new IOException("AOT compilation rejected builtin target " + target.id(), error);
            }
            bindAndHashAnimations(target.descriptor(), definition, animationHashes);
            for (var textureName : target.getTextureNames()) {
                var texture = target.textureDescriptor(textureName);
                var sources = target.textureSources(chunks, textureName);
                decodeAdditionalTextureImages(sources);
                try (var uv = sources.uv().open(); var pixels = uv.decodeToBuffer()) {
                    for (var geoEntry : definition.geoModels().object2ObjectEntrySet()) {
                        var geo = GeoModelProto.parseFrom(ProtoSource.newInstance(geoEntry.getValue()));
                        if (!NativeBakedModel.tryBake(geo, pixels, uv.width(), uv.height(), CURRENT_RAW_UV_VERSION,
                            target.descriptor().settings().forceCulling(), false, hasPbr(texture))) {
                            throw new IOException("Native tryBake rejected model target=" + target.id() + " texture="
                                + textureName + " geometry=" + geoEntry.getKey());
                        }
                    }
                }
            }
        }
        validateSounds(view, chunks);
        programManifest.sort(java.util.Comparator.comparing(ModelProgramInventory.ManifestEntry::location)
            .thenComparing(value -> value.role().name()).thenComparing(ModelProgramInventory.ManifestEntry::keyHex));
        return new Result(handle.representation().modelId(), Map.copyOf(animationHashes), List.copyOf(programManifest),
            new ModelProgramInventory.Counts(candidateCount, executableCount, literalCount, programCount, fallbackCount,
                failureCount, callableEntryCount, eventEntryCount, rejectedSuffixCount));
    }

    private static ModelDataProto readDefinition(ModelFileView view, ChunkDataSource chunks, int blobId)
        throws IOException {
        var chunk = view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + blobId);
        if (chunk == null) {
            throw new FileNotFoundException("Missing render target definition blob " + blobId);
        }
        try (var data = chunks.readPayload(chunk, BufferType.ARRAY)) {
            return ModelDataProto.parseFrom(ProtoUtil.source((ArrayBuffer)data));
        }
    }

    private static void bindAndHashAnimations(RenderTargetProto target, ModelDataProto definition,
        Map<DefaultAnimationKey, Hash256> output) throws IOException {
        if (definition.animationFiles().isEmpty()) {
            return;
        }
        for (var file : definition.animationFiles().object2ObjectEntrySet()) {
            var domain = DefaultAnimationKey.domain(target, file.getKey());
            for (var animation : file.getValue().animations()) {
                var key = new DefaultAnimationKey(domain, animation.name());
                var hash = payloadHash(animation);
                var previous = output.putIfAbsent(key, hash);
                if (previous != null && !previous.equals(hash)) {
                    throw new IOException("Conflicting default animation payloads for " + key);
                }
            }
        }
    }

    public static Hash256 payloadHash(AnimationProto animation) throws IOException {
        return ModelHashing.blake3(ProtoUtil.serializeToArray(animation));
    }

    private static void decodeAdditionalTextureImages(com.elfmcys.ysm.format.schema.file.PBRImageSources sources)
        throws IOException {
        if (sources.normal() != null) {
            decode(sources.normal());
        }
        if (sources.specular() != null) {
            decode(sources.specular());
        }
    }

    private static void decode(com.elfmcys.ysm.natives.image.ImageSource source) throws IOException {
        try (var image = source.open(); var ignored = image.decodeToBuffer()) {
        }
    }

    private static void decodeNamedImage(ModelFileView view, ChunkDataSource chunks, String type) throws IOException {
        var source = view.getFileView().imageChunkSource(chunks, type);
        if (source != null) {
            decode(source);
        }
    }

    private static void decodePresentationImages(ModelFileView view, ChunkDataSource chunks) throws IOException {
        var info = view.getManifest().info();
        var settings = info.settings();
        if (settings.hasGuiForeground()) {
            decode(view.getFileView().imageBlobSource(chunks, settings.guiForegroundUnsafe()));
        }
        if (settings.hasGuiBackground()) {
            decode(view.getFileView().imageBlobSource(chunks, settings.guiBackgroundUnsafe()));
        }
        if (info.hasMetadata() && !info.metadataUnsafe().authors().isEmpty()) {
            for (var author : info.metadataUnsafe().authors()) {
                if (author.hasAvatar()) {
                    decode(view.getFileView().imageBlobSource(chunks, author.avatarUnsafe()));
                }
            }
        }
    }

    private static StringDataProto readCommonStrings(ModelFileView view, ChunkDataSource chunks) throws IOException {
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() == 0) {
            return null;
        }
        var chunk = view.getFileView().getAssetView()
            .getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + common.stringsBlobId());
        if (chunk == null) {
            throw new FileNotFoundException("Missing common strings blob");
        }
        try (var data = chunks.readPayload(chunk, BufferType.ARRAY)) {
            return StringDataProto.parseFrom(ProtoUtil.source((ArrayBuffer)data));
        }
    }

    private static void validateSounds(ModelFileView view, ChunkDataSource chunks) throws IOException {
        var decoded = new java.util.HashSet<com.elfmcys.ysm.model.domain.Hash256>();
        for (var sound : view.getCommon().sounds().values()) {
            if (!decoded.add(sound.mediaIdentity())) {
                continue;
            }
            sound.validateComplete(() -> false, chunks);
        }
    }

    private static boolean hasPbr(PBRTextureSetProto texture) {
        return texture.hasNormal() || texture.hasSpecular();
    }

    public record Result(Hash256 modelHash, Map<DefaultAnimationKey, Hash256> animationHashes,
        List<ModelProgramInventory.ManifestEntry> programManifest, ModelProgramInventory.Counts programCounts) {
    }

    @FunctionalInterface
    public interface TargetProgramVerifier {
        void verify(String targetId, ModelProgramInventory.Result inventory) throws Exception;
    }
}
