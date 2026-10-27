package com.elfmcys.ysm.model.domain;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.UniBufferIO;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.util.ProtoBytes;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Owns the encoded metadata prefix associated with one parsed model file. */
public final class ModelRepresentation implements AutoCloseable {
    private final ModelFileIdentity identity;
    private final @Nullable NativeBuffer metadataPrefix;
    private final ModelFileView view;

    public ModelRepresentation(ModelFileIdentity identity, @Nullable UniBuffer metadataPrefix, ModelFileView view)
        throws IOException {
        this(identity, metadataPrefix, view, false);
    }

    public static ModelRepresentation fromMetadataPrefix(ModelFileIdentity identity, UniBuffer metadataPrefix)
        throws IOException {
        return new ModelRepresentation(identity, metadataPrefix, ModelFileView.readMetadata(metadataPrefix), true);
    }

    public static ModelRepresentation fromMetadataPrefix(UniBuffer metadataPrefix,
        @Nullable ModelRepresentation reusable) throws IOException {
        var view = ModelFileView.readMetadata(metadataPrefix, reusable == null ? null : reusable.view);
        return new ModelRepresentation(
            new ModelFileIdentity(view.getModelHash(), view.getFileView().getAssetView().getContainerId()),
            metadataPrefix, view, true);
    }

    /** Acquires an independent prefix capability while sharing the immutable parsed view. */
    public ModelRepresentation acquire() throws IOException {
        return new ModelRepresentation(identity, metadataPrefix, view, true);
    }

    public ModelRepresentation reuseView(ModelRepresentation previous) throws IOException {
        var normalized = view.reuse(previous.view);
        return normalized == view ? this : new ModelRepresentation(identity, metadataPrefix, normalized, true);
    }

    public boolean sameContents(ModelRepresentation other) {
        if (!identity.equals(other.identity) || view != other.view
            || hasMetadataPrefix() != other.hasMetadataPrefix()) {
            return false;
        }
        return metadataPrefix == null || metadataPrefix.size() == other.metadataPrefix.size()
            && UniBufferIO.equals(metadataPrefix, 0, other.metadataPrefix, 0, metadataPrefix.size());
    }

    private ModelRepresentation(ModelFileIdentity identity, @Nullable UniBuffer metadataPrefix, ModelFileView view,
        boolean prefixValidated) throws IOException {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.view = Objects.requireNonNull(view, "view");
        if (!ProtoBytes.equals(identity.modelId(), view.getManifest().info().properties().modelId())) {
            throw new IllegalArgumentException("Model representation model id mismatch");
        }
        if (!identity.containerId().equals(view.getFileView().getAssetView().getContainerId())) {
            throw new IllegalArgumentException("Model representation container id mismatch");
        }
        if (metadataPrefix == null) {
            this.metadataPrefix = null;
            return;
        }

        if (!prefixValidated) {
            var prefixView = ModelFileView.readMetadata(metadataPrefix);
            if (!identity.modelId().equals(prefixView.getModelHash())
                || !identity.containerId().equals(prefixView.getFileView().getAssetView().getContainerId())) {
                throw new IOException("Model metadata prefix identity mismatch");
            }
        }
        this.metadataPrefix = metadataPrefix.acquireNative();
    }

    private ModelRepresentation(ModelFileIdentity identity, ModelFileView view) {
        this.identity = identity;
        this.metadataPrefix = null;
        this.view = view;
    }

    public ModelFileIdentity identity() {
        return identity;
    }

    public Hash256 modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }

    /**
     * Returns an independent owned reference. Shared metadata bytes must not be modified. The caller must close it.
     */
    public Optional<UniBuffer> metadataPrefix() {
        return metadataPrefix == null ? Optional.empty() : Optional.of(metadataPrefix.acquire());
    }

    public ModelFileView view() {
        return view;
    }

    public boolean hasMetadataPrefix() {
        return metadataPrefix != null;
    }

    public int metadataPrefixSize() {
        return metadataPrefix == null ? 0 : metadataPrefix.size();
    }

    public ModelRepresentation withoutMetadataPrefix() {
        return new ModelRepresentation(identity, view);
    }

    @Override
    public void close() {
        if (metadataPrefix != null) {
            metadataPrefix.close();
        }
    }

}
