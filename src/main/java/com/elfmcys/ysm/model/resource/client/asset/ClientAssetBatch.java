package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.natives.image.ImageSource;

import java.util.concurrent.CompletableFuture;

/**
 * Collects asset loads against one catalog/session snapshot for one consumer lifetime.
 *
 * <p>Execution: register requests before one submit. Local reads or probes may start and complete
 * during registration; submit enables remaining remote work. Future callbacks may run inline or on
 * the completing worker and do not grant render-thread access.
 *
 * <p>Ownership: this batch owns delivered encoded inputs until close. A consumer that outlives the
 * batch must acquire independent retention before closing it. Future cancellation alone does not
 * discharge the batch's close obligation or prove producer termination.
 *
 * <p>Seam: implements [page image acquisition](docs/architecture/client-presentation/overview.md#页面需求与展示消费); bytes and CPU completion do not imply a registered host texture.
 */
public final class ClientAssetBatch implements AutoCloseable {
    private final ClientAssetRepository.Batch delegate;

    public ClientAssetBatch(ClientAssetRepository.Batch delegate) {
        this.delegate = delegate;
    }

    public ResidentImages images() {
        return delegate.images();
    }

    public ImageRequest imageRequest(Hash256 model, ModelAssetSelector selector) {
        return delegate.imageRequest(model, selector);
    }

    public ImageRequest coverRequest(ModelPackDescriptor pack) {
        return delegate.coverRequest(pack);
    }

    /**
     * Registers preview acquisition. Normal completion is Data or explicit Absent, never null;
     * access/content failure uses exceptional completion. Returned Data is borrowed from this batch.
     *
     * @throws IllegalStateException if registration has been sealed by submit or close
     */
    public CompletableFuture<PreviewResult> preview(Hash256 hash) {
        return delegate.preview(hash);
    }

    public boolean previewAbsent(ModelFileIdentity identity) {
        return delegate.previewAbsent(identity);
    }

    /** Registers a cover; missing cover is an exceptional result, not preview Absent or a null image. */
    public CompletableFuture<ImageSource> packCover(ModelPackDescriptor pack) {
        return delegate.packCover(pack);
    }

    /** Registers a declared presentation item; normal completion supplies a batch-borrowed, non-null image. */
    public CompletableFuture<ImageSource> presentation(Hash256 hash, ModelAssetSelector.PresentationAsset asset,
        int index) {
        return delegate.presentation(hash, asset, index);
    }

    /**
     * Seals registration and starts or chains remote dispatch without waiting for all results.
     *
     * @throws IllegalStateException on repeated submit or after close
     */
    public void submit() {
        delegate.submit();
    }

    public boolean hasRemoteRequests() {
        return delegate.hasRemoteRequests();
    }

    /**
     * Ends this consumer's use, requests cancellation, and releases delivered encoded inputs.
     * Does not join producers or retire other batches, model leases, or verified disk content.
     */
    @Override
    public void close() {
        delegate.close();
    }
}
