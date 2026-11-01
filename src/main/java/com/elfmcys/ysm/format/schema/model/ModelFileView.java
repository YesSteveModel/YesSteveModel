package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ChunkDecoding;
import com.elfmcys.ysm.format.container.InlineChunkReader;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.CommonAssetView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.format.schema.model.views.RenderTargetView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Validated model metadata and derived lookup views; backing storage remains owned by the content source.
 *
 * <p>Ensures: construction establishes container/model identity agreement and the supported Manifest
 * reference profile. Consumers reuse those guarantees for this immutable representation. Unread payloads,
 * actual-source admission, media decoding, and host readiness remain separate checks.
 *
 * <p>Ownership: metadata access does not retain a source handle. Payload reads borrow the supplied
 * ChunkDataSource under its execution and lifetime contract; missing optional media is distinct from failure.
 *
 * <p>Failure: container parsing retains its checked and unchecked rejection channels. Invalid model
 * identity and Manifest reference rules are reported as IOException; no partial view is published.
 *
 * <p>Seam: implements [model metadata validation](docs/architecture/asset-pipeline/overview.md#分层验证与发布).
 */
public class ModelFileView {
    private final boolean supported;

    private final AssetFileView fileView;
    private final ModelInfoView metadataView;
    private final List<RenderTargetView> renderTargets;
    private final Map<String, RenderTargetView> renderTargetsById;
    private final CommonAssetView commonView;
    private final ManifestProto manifest;
    private final Hash256 modelId;
    private final PreviewSource thumbnailPreviewSource;
    private final PreviewSource iconPreviewSource;

    /** Borrows an exclusively available channel for synchronous, position-changing metadata I/O; does not close it. */
    public ModelFileView(SeekableByteChannel file) throws IOException {
        this(ModelFileIdentityReader.readContainer(file), ModelFileIdentityReader.classifyAccess(file));
    }

    private ModelFileView(AssetContainerView assetView, SeekableByteChannel file) throws IOException {
        this(assetView, ModelFileIdentityReader.read(assetView), readManifest(assetView, file));
    }

    private ModelFileView(AssetContainerView assetView, ModelFileIdentity identity, ManifestData manifestData)
        throws IOException {
        supported = true;

        this.manifest = manifestData.manifest();
        this.modelId = manifestModelId(manifest);
        if (!modelId.equals(identity.modelId())) {
            throw new IOException("Manifest model identity does not match container property");
        }
        this.thumbnailPreviewSource = thumbnailSource(manifest.info());
        this.iconPreviewSource = iconSource(manifest.info());

        this.fileView = new AssetFileView(assetView);
        validateBlobRoles(manifest);
        commonView = new CommonAssetView(manifest.commonAssets(), this.fileView);
        validatePreviewSource("Thumbnail", thumbnailPreviewSource,
            assetView.getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME) != null);
        validatePreviewSource("Icon", iconPreviewSource,
            assetView.getChunkInfo(ModelFileConstant.THUMB_ICON_CHUNK_NAME) != null);
        validatePlayerRenderTarget(manifest);
        metadataView = new ModelInfoView(manifest.info(), ModelManifestLookup.target(manifest, RenderTargetIds.PLAYER),
            this.fileView);
        var targets = new ArrayList<RenderTargetView>(manifest.renderTargets().size());
        var byId = new LinkedHashMap<String, RenderTargetView>();
        for (var target : manifest.renderTargets()) {
            var view = new RenderTargetView(this.fileView, target);
            if (view.id().isBlank() || byId.putIfAbsent(view.id(), view) != null) {
                throw new IOException("Duplicate or empty render target id: " + view.id());
            }
            if (view.kind() == RenderTargetKind.UNSPECIFIED) {
                throw new IOException("Render target has no kind: " + view.id());
            }
            targets.add(view);
        }
        renderTargets = List.copyOf(targets);
        renderTargetsById = Map.copyOf(byId);
    }

    private static void validateBlobRoles(ManifestProto manifest) throws IOException {
        var roles = new HashMap<Integer, String>();
        var common = manifest.commonAssets();
        requireBlobRole(roles, common.stringsBlobId(), "strings");
        for (var sound : common.sounds()) {
            for (var id : sound.chunkIds()) {
                requireBlobRole(roles, id, "sound");
            }
        }
        for (var target : manifest.renderTargets()) {
            requireBlobRole(roles, target.blobId(), "definition");
            for (var texture : target.textures().values()) {
                requireBlobRole(roles, texture.uv().blobId(), "image");
                if (texture.hasNormal()) {
                    requireBlobRole(roles, texture.normalUnsafe().blobId(), "image");
                }
                if (texture.hasSpecular()) {
                    requireBlobRole(roles, texture.specularUnsafe().blobId(), "image");
                }
            }
        }
        var settings = manifest.info().settings();
        if (settings.hasGuiForeground()) {
            requireBlobRole(roles, settings.guiForegroundUnsafe().blobId(), "image");
        }
        if (settings.hasGuiBackground()) {
            requireBlobRole(roles, settings.guiBackgroundUnsafe().blobId(), "image");
        }
        if (manifest.info().hasMetadata()) {
            for (var author : manifest.info().metadataUnsafe().authors()) {
                if (author.hasAvatar()) {
                    requireBlobRole(roles, author.avatarUnsafe().blobId(), "image");
                }
            }
        }
    }

    private static void requireBlobRole(Map<Integer, String> roles, int id, String role) throws IOException {
        if (id == 0) {
            return;
        }
        var previous = roles.putIfAbsent(id, role);
        if (previous != null && !previous.equals(role)) {
            throw new IOException(
                "Conflicting roles for blob-" + Integer.toUnsignedLong(id) + ": " + previous + " and " + role);
        }
    }

    static void validatePlayerRenderTarget(ManifestProto manifest) throws IOException {
        if (manifest.renderTargets().isEmpty()) {
            throw new IOException("Model contains no player render target");
        }
        var found = false;
        for (var target : manifest.renderTargets()) {
            var targetId = target.targetId();
            var kind = target.kind();
            if (!target.textures().isEmpty()) {
                for (var texture : target.textures().object2ObjectEntrySet()) {
                    if (texture.getKey().isEmpty()) {
                        throw new IOException("Render target has an empty texture key: " + targetId);
                    }
                }
            }
            if (targetId.equals(RenderTargetIds.PLAYER)) {
                if (kind != RenderTargetKind.PLAYER) {
                    throw new IOException("Player render target has invalid kind: " + kind);
                }
                if (target.textures().isEmpty()) {
                    throw new IOException("Player render target has no texture");
                }
                found = true;
            } else if (kind == RenderTargetKind.PLAYER) {
                throw new IOException("Player render target has invalid id: " + targetId);
            }
        }
        if (!found) {
            throw new IOException("Model contains no player render target");
        }
    }

    static void validatePreviewSource(String name, PreviewSource source, boolean hasImage) throws IOException {
        if (source == null) {
            throw new IOException(name + " has an unknown preview source");
        }
        if (source == PreviewSource.UNSPECIFIED) {
            if (hasImage) {
                throw new IOException(name + " chunk has no preview source");
            }
        } else if (!hasImage) {
            throw new IOException(name + " preview source has no image chunk: " + source);
        }
    }

    static PreviewSource thumbnailSource(InfoProto info) {
        return info.thumbnailSource().orElse(PreviewSource.UNSPECIFIED);
    }

    static PreviewSource iconSource(InfoProto info) {
        return info.iconSource().orElse(PreviewSource.UNSPECIFIED);
    }

    public static ModelFileView readMetadata(UniBuffer metadataPrefix) throws IOException {
        return readMetadata(metadataPrefix, null);
    }

    /**
     * Validates exactly the preamble and Manifest prefix without fetching remaining chunks.
     *
     * <p>Requires: a non-null, readable prefix whose bytes remain unchanged for this call. The input
     * buffer remains caller-owned and may be released after return.
     *
     * <p>Ensures: reusable is only a sharing hint. It never skips input verification; equal metadata
     * may return that same view. Failure publishes no partial view and does not invalidate reusable.
     *
     * @throws IOException if layout, identity, or the supported Manifest profile is invalid
     */
    public static ModelFileView readMetadata(UniBuffer metadataPrefix, @Nullable ModelFileView reusable)
        throws IOException {
        if (!(metadataPrefix instanceof ArrayBuffer)) {
            try (var bytes = metadataPrefix.copyArray()) {
                return readMetadata(bytes, reusable);
            }
        }
        var bytes = (ArrayBuffer)metadataPrefix;
        try {
            var assetView = AssetContainerReader.readPreamble(bytes.array(), bytes.arrayOffset(), bytes.size());
            var manifestChunk = requireMetadataLayout(assetView);
            var prefixSize = Math.addExact(manifestChunk.offset(), manifestChunk.size());
            if (bytes.size() != prefixSize) {
                throw new IOException("Metadata prefix has an invalid byte range");
            }
            var stored =
                ArrayBuffer.borrow(bytes.array(), bytes.arrayOffset() + manifestChunk.offset(), manifestChunk.size());
            ChunkDecoding.validateDirectPayload(stored, manifestChunk);
            var manifest = ManifestProto.parseFrom(ProtoUtil.source(stored));
            if (reusable != null) {
                if (manifest.equals(reusable.manifest)) {
                    manifest = reusable.manifest;
                }
                if (assetView.sameContents(reusable.fileView.getAssetView())) {
                    assetView = reusable.fileView.getAssetView();
                    if (manifest == reusable.manifest) {
                        return reusable;
                    }
                }
            }
            return new ModelFileView(assetView, ModelFileIdentityReader.read(assetView), new ManifestData(manifest));
        } finally {
            java.lang.ref.Reference.reachabilityFence(metadataPrefix);
        }
    }

    public ModelFileView reuse(ModelFileView previous) throws IOException {
        var asset = fileView.getAssetView();
        var oldAsset = previous.fileView.getAssetView();
        var normalizedAsset = asset.sameContents(oldAsset) ? oldAsset : asset;
        var normalizedManifest = manifest.equals(previous.manifest) ? previous.manifest : manifest;
        if (normalizedAsset == oldAsset && normalizedManifest == previous.manifest) {
            return previous;
        }
        if (normalizedAsset == asset && normalizedManifest == manifest) {
            return this;
        }
        return new ModelFileView(normalizedAsset, ModelFileIdentityReader.read(normalizedAsset),
            new ManifestData(normalizedManifest));
    }

    private static ManifestData readManifest(AssetContainerView assetView, SeekableByteChannel file)
        throws IOException {
        var chunk = requireMetadataLayout(assetView);
        try (var data = InlineChunkReader.readPayload(file, chunk, BufferType.ARRAY)) {
            if (data.size() == 0) {
                throw new IOException("No manifest data found");
            }
            if (!(data instanceof ArrayBuffer arrayBuffer)) {
                throw new IOException("Model manifest is not array-backed");
            }
            var manifest = ManifestProto.parseFrom(ProtoUtil.source(arrayBuffer));
            return new ManifestData(manifest);
        }
    }

    public static AssetContainerView.ChunkInfo requireMetadataLayout(AssetContainerView assetView) throws IOException {
        var manifest = assetView.getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME);
        if (manifest == null || manifest.size() <= 0) {
            throw new IOException("No manifest data found");
        }
        if (!manifest.encoding().isEmpty() || manifest.decodeSize() != 0) {
            throw new IOException("Manifest must use direct storage");
        }
        var verification = assetView.getChunkInfo(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        if (verification == null || manifest.offset() != Math
            .addExact(Math.addExact(verification.offset(), verification.size()), manifest.alignSize())) {
            throw new IOException("Manifest must be the first ordinary chunk");
        }
        for (var chunk : assetView.getChunkTable().values()) {
            if (!chunk.type().equals(AssetContainerConstant.VERIFICATION_CHUNK_TYPE)
                && !chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME) && chunk.offset() < manifest.offset()) {
                throw new IOException("Manifest must be the first ordinary chunk");
            }
        }
        return manifest;
    }

    public boolean supported() {
        return supported;
    }

    public AssetFileView getFileView() {
        return fileView;
    }

    public @Nullable Image readThumbnail(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        return fileView.readImageChunk(cancelled, source, ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
    }

    public @Nullable ImageSource thumbnailSource(ChunkDataSource source) throws IOException {
        return fileView.imageChunkSource(source, ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
    }

    public @Nullable ImageSource thumbnailSource(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        return fileView.imageChunkSource(cancelled, source, ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
    }

    public PreviewSource getThumbnailPreviewSource() {
        return thumbnailPreviewSource;
    }

    public PreviewSource getIconPreviewSource() {
        return iconPreviewSource;
    }

    public @Nullable Image readIcon(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        return fileView.readImageChunk(cancelled, source, ModelFileConstant.THUMB_ICON_CHUNK_NAME);
    }

    public @Nullable ImageSource iconSource(ChunkDataSource source) throws IOException {
        return fileView.imageChunkSource(source, ModelFileConstant.THUMB_ICON_CHUNK_NAME);
    }

    public @Nullable ImageSource iconSource(BooleanSupplier cancelled, ChunkDataSource source) throws IOException {
        return fileView.imageChunkSource(cancelled, source, ModelFileConstant.THUMB_ICON_CHUNK_NAME);
    }

    public ModelInfoView getMetadata() {
        return metadataView;
    }

    public List<RenderTargetView> getRenderTargets() {
        return renderTargets;
    }

    /** Returns the immutable target descriptor, or null for an absent id; performs no acquisition. */
    public @Nullable RenderTargetView getRenderTarget(String targetId) {
        return renderTargetsById.get(targetId);
    }

    /**
     * Returns a target whose presence the caller requires.
     *
     * @throws IllegalArgumentException if targetId is absent; use getRenderTarget for an expected miss
     */
    public RenderTargetView requireRenderTarget(String targetId) {
        var target = getRenderTarget(targetId);
        if (target == null) {
            throw new IllegalArgumentException("Model contains no render target: " + targetId);
        }
        return target;
    }

    public RenderTargetView getPlayer() {
        return requireRenderTarget(RenderTargetIds.PLAYER);
    }

    public CommonAssetView getCommon() {
        return commonView;
    }

    public ManifestProto getManifest() {
        return manifest;
    }

    public Hash256 getModelHash() throws IOException {
        return modelId;
    }

    private static Hash256 manifestModelId(ManifestProto manifest) throws IOException {
        var properties = manifest.info().properties();
        if (properties.modelId().remaining() != Hash256.SIZE) {
            throw new IOException("Manifest contains no valid full model hash");
        }
        return new Hash256(ProtoBytes.copy(properties.modelId()));
    }

    private record ManifestData(ManifestProto manifest) {
    }
}
