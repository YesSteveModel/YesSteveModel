package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.natives.image.ImageSource;

import java.util.concurrent.CompletableFuture;

/** Explicitly collects asset loads that may share one network distribution session. */
public final class ClientAssetBatch implements AutoCloseable {
    private final ClientAssetRepository.Batch delegate;

    public ClientAssetBatch(ClientAssetRepository.Batch delegate) {
        this.delegate = delegate;
    }

    public CompletableFuture<PreviewResult> preview(Hash256 hash) {
        return delegate.preview(hash);
    }

    public boolean previewAbsent(ModelFileIdentity identity) {
        return delegate.previewAbsent(identity);
    }

    public CompletableFuture<ImageSource> packCover(ModelPackDescriptor pack) {
        return delegate.packCover(pack);
    }

    public CompletableFuture<ImageSource> presentation(Hash256 hash, ModelAssetSelector.PresentationAsset asset,
        int index) {
        return delegate.presentation(hash, asset, index);
    }

    public void submit() {
        delegate.submit();
    }

    public boolean hasRemoteRequests() {
        return delegate.hasRemoteRequests();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
