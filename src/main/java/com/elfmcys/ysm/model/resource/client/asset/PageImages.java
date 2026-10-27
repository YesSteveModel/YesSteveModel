package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.natives.image.ImageSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Host adapter owns image publication and release; its views cannot start work or take ownership. */
public final class PageImages implements ClientDemandCoordinator.PageAction {
    private final ClientAssetBatch batch;
    private final ArrayList<Image> images = new ArrayList<>();
    private boolean submitted;
    private boolean closed;

    public PageImages(ClientAssetBatch batch) {
        this.batch = batch;
    }

    public Image preview(Hash256 model) {
        return add(() -> batch.preview(model));
    }

    public Image presentation(Hash256 model, ModelAssetSelector.PresentationAsset asset, int index) {
        return add(() -> batch.presentation(model, asset, index));
    }

    public Image packCover(ModelPackDescriptor pack) {
        return add(() -> batch.packCover(pack));
    }

    private Image add(java.util.function.Supplier<CompletableFuture<?>> future) {
        if (closed)
            throw new IllegalStateException("Page image owner is closed");
        if (submitted)
            throw new IllegalStateException("Page image batch is already submitted");
        var image = new Image(future);
        images.add(image);
        return image;
    }

    @Override
    public void advance(ModelResourceDemand.Mode allowed) {
        if (closed)
            return;
        if (allowed != ModelResourceDemand.Mode.READY)
            images.forEach(Image::prepare);
        if (allowed == ModelResourceDemand.Mode.ONLINE && !submitted) {
            submitted = true;
            batch.submit();
        }
        images.forEach(Image::publish);
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        batch.close();
        images.forEach(Image::close);
        images.clear();
    }

    public static final class Image {
        private java.util.function.Supplier<CompletableFuture<?>> request;
        private CompletableFuture<?> source;
        private CustomTexture texture;
        private TextureHolder holder;
        private Throwable failure;
        private boolean loading = true;
        private boolean absent;
        private boolean closed;

        private Image(java.util.function.Supplier<CompletableFuture<?>> request) {
            this.request = request;
        }

        private void prepare() {
            if (closed || source != null)
                return;
            try {
                source = java.util.Objects.requireNonNull(request.get(), "Image operation returned no future");
            } catch (RuntimeException failure) {
                source = CompletableFuture.failedFuture(failure);
            } finally {
                request = null;
            }
        }

        public @Nullable TextureHolder texture() {
            return closed ? null : holder;
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

        private void publish() {
            if (closed || !loading || source == null || !source.isDone())
                return;
            loading = false;
            try {
                var outcome = source.getNow(null);
                if (outcome == PreviewResult.Absent.INSTANCE) {
                    absent = true;
                    return;
                }
                var image = outcome instanceof PreviewResult.Data data ? data.image()
                    : outcome instanceof ImageSource supplied ? supplied : null;
                if (image == null)
                    throw new IllegalStateException("Image operation returned no result");
                texture = new CustomTexture(image);
                holder = CustomTextureManager.register(texture);
                failure = texture.failure().orElse(null);
                if (failure != null) {
                    CustomTextureManager.release(texture);
                    texture = null;
                    holder = null;
                }
            } catch (RuntimeException error) {
                if (texture != null) {
                    texture.close();
                    texture = null;
                    holder = null;
                }
                while (error instanceof CompletionException && error.getCause() instanceof RuntimeException cause)
                    error = cause;
                failure = error;
            }
        }

        private void close() {
            closed = true;
            loading = false;
            if (texture != null)
                CustomTextureManager.release(texture);
            texture = null;
            holder = null;
            source = null;
            request = null;
        }
    }
}
