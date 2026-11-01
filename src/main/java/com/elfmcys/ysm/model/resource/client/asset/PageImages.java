package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.natives.image.ImageSource;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Slots own uses; acquisition and physical release keep their original owners.
 *
 * <p>Execution: page/slot access and host publication are render-owner confined. Only a claimed
 * Preparation may cross to its designated worker; its result must return to the render owner.
 *
 * <p>Ownership: construction takes the batch's close responsibility. Slots borrow acquired inputs
 * from that batch and hold independent resident uses. Claimed preparation has its own completion
 * obligation and must be published or closed even after this page ends.
 *
 * <p>Seam: implements [page result handoff](docs/architecture/client-presentation/overview.md#页面需求与展示消费) and [page retirement](docs/architecture/client-presentation/overview.md#生命周期与失败).
 */
public final class PageImages implements ClientDemandCoordinator.PageAction {
    private final ClientAssetBatch batch;
    private final ResidentImages owner;
    private final ArrayList<Image> images = new ArrayList<>();
    private boolean submitted;
    private boolean closed;

    public PageImages(ClientAssetBatch batch) {
        this(batch, batch.images());
    }

    PageImages(ClientAssetBatch batch, ResidentImages owner) {
        this.batch = batch;
        this.owner = owner;
    }

    public Image preview(Hash256 model) {
        return add(() -> batch.imageRequest(model, ModelAssetSelector.preview()));
    }

    public Image presentation(Hash256 model, ModelAssetSelector.PresentationAsset asset, int index) {
        return add(() -> batch.imageRequest(model, ModelAssetSelector.presentation(asset, index)));
    }

    public Image packCover(ModelPackDescriptor pack) {
        return add(() -> batch.coverRequest(pack));
    }

    private Image add(Supplier<ImageRequest> request) {
        if (closed || submitted)
            throw new IllegalStateException("Page image batch is closed or submitted");
        var image = new Image(request);
        images.add(image);
        return image;
    }

    /**
     * Advances acquisition without decoding or host publication. READY only checks resident use;
     * ONLINE also submits this batch once. A closed page is a no-op. CPU work requires a separate claim.
     */
    public void advanceAcquisition(ModelResourceDemand.Mode allowed) {
        if (closed)
            return;
        images.forEach(image -> image.advance(allowed != ModelResourceDemand.Mode.READY));
        if (allowed == ModelResourceDemand.Mode.ONLINE && !submitted) {
            submitted = true;
            batch.submit();
        }
    }

    /** Synchronously prepares and publishes claimed CPU work on the caller, for non-frame consumers. */
    @Override
    public void advance(ModelResourceDemand.Mode allowed) {
        advanceAcquisition(allowed);
        for (var image : images) {
            var preparation = image.claimPreparation();
            if (preparation == null)
                continue;
            try (preparation) {
                image.publish(preparation.prepare());
            } catch (Exception failure) {
                image.failed(failure);
            }
        }
        refreshResidents();
    }

    public void refreshResidents() {
        if (!closed)
            images.forEach(image -> image.advance(false));
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        Throwable failure = null;
        try { batch.close(); } catch (Throwable error) { failure = error; }
        for (var image : images) {
            try { image.close(); } catch (Throwable error) {
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
        }
        images.clear();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }

    /** A page-owned projection; loading, absence, and failure are distinct from host-texture readiness. */
    public final class Image {
        private Supplier<ImageRequest> requestFactory;
        private ImageRequest request;
        private CompletableFuture<PreviewResult> source;
        private ImageSource input;
        private ResidentImages.Use use;
        private Throwable failure;
        private boolean loading = true;
        private boolean absent;
        private boolean closed;

        private Image(Supplier<ImageRequest> request) {
            requestFactory = request;
        }

        private void advance(boolean admitted) {
            if (closed || failure != null || absent)
                return;
            try {
                if (request == null) {
                    request = requestFactory.get();
                    requestFactory = null;
                }
                if (!request.allowed())
                    return;
                if (use != null && use.texture() != null)
                    return;
                if (use != null) {
                    use.close();
                    use = null;
                }
                use = owner.lookup(effectiveRequest());
                if (use != null) {
                    loading = false;
                    return;
                }
                loading = true;
                if (source == null && admitted)
                    source = java.util.Objects.requireNonNull(request.acquire(), "Image operation returned no future");
                if (source == null || !source.isDone())
                    return;
                var outcome = source.getNow(null);
                if (outcome == PreviewResult.Absent.INSTANCE) {
                    loading = false;
                    absent = true;
                } else if (outcome instanceof PreviewResult.Data data) {
                    input = data.image();
                } else {
                    throw new IllegalStateException("Image operation returned no result");
                }
            } catch (RuntimeException error) {
                failed(error);
            }
        }

        private ImageRequest effectiveRequest() {
            if (input == null)
                return request;
            return new ImageRequest(ignored -> request.identity(input), request::allowed,
                ignored -> request.current(input), () -> source);
        }

        /** Returns a borrowed live host texture or null; does not start work or extend the slot's lifetime. */
        public @Nullable TextureHolder texture() {
            return closed || use == null ? null : use.texture();
        }

        public boolean loading() {
            return !closed && loading;
        }

        public boolean absent() {
            return !closed && absent;
        }

        public @Nullable Throwable failure() {
            return failure;
        }

        public @Nullable ImageIdentity identity() {
            return request == null ? null : request.identity(input);
        }

        public boolean cpuReady() {
            return !closed && use == null && failure == null && input != null && request.current(input);
        }

        /**
         * Claims at most one preparation for this content identity. A null result means no claim was
         * obtained; it is not proof of absence. The caller owns a returned claim and must execute or close it.
         */
        public @Nullable ResidentImages.Preparation claimPreparation() {
            try {
                return cpuReady() ? owner.claim(request, input) : null;
            } catch (RuntimeException failure) {
                failed(failure);
                return null;
            }
        }

        /**
         * Consumes this slot's prepared result on the render owner, including closed-page rejection.
         * Publication failure is recorded and propagated; the caller must not retry a consumed result.
         */
        public void publish(ResidentImages.Prepared image) {
            if (closed) {
                image.close();
                return;
            }
            try {
                owner.publish(image);
                if (closed)
                    return;
                use = owner.lookup(effectiveRequest());
                if (use != null)
                    loading = false;
            } catch (RuntimeException | Error error) {
                if (!closed)
                    failed(error);
                throw error;
            }
        }

        public void failed(Throwable error) {
            while (error instanceof CompletionException && error.getCause() != null)
                error = error.getCause();
            failure = error;
            loading = false;
        }

        private void close() {
            closed = true;
            loading = false;
            if (use != null)
                use.close();
            use = null;
            input = null;
            request = null;
            source = null;
            requestFactory = null;
        }
    }
}
