package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.asset.ModelAssetSelector;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Keeps image policy independent of the card's mutable entity lifecycle. */
public final class CatalogCardImages {
    private final Hash256 model;
    private final PageImages.Image background;
    private final PageImages.Image foreground;
    private PageImages.Image preview;
    private boolean eager;

    public CatalogCardImages(PageImages decorations, Hash256 model, boolean hasBackground, boolean hasForeground,
        boolean eager) {
        this.model = model;
        this.eager = eager;
        background = hasBackground
            ? decorations.presentation(model, ModelAssetSelector.PresentationAsset.GUI_BACKGROUND, 0) : null;
        foreground = hasForeground
            ? decorations.presentation(model, ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0) : null;
    }

    public void advancePolicy(boolean eager, @Nullable PageImages previews) {
        this.eager = eager;
        if (!eager && preview == null && previews != null)
            preview = previews.preview(model);
    }

    public @Nullable TextureHolder preview() {
        return eager || preview == null ? null : preview.texture();
    }

    public @Nullable TextureHolder background() {
        return background == null ? null : background.texture();
    }

    public @Nullable TextureHolder foreground() {
        return foreground == null ? null : foreground.texture();
    }

    public boolean previewLoading() {
        return !eager && preview != null && preview.loading();
    }

    public boolean previewAbsent() {
        return !eager && preview != null && preview.absent();
    }

    public @Nullable Throwable failure() {
        var failure = !eager && preview != null ? preview.failure() : null;
        if (failure == null && background != null)
            failure = background.failure();
        if (failure == null && foreground != null)
            failure = foreground.failure();
        return failure;
    }

    public List<PageImages.Image> preparations() {
        var result = new ArrayList<PageImages.Image>(3);
        if (!eager && preview != null)
            result.add(preview);
        if (background != null)
            result.add(background);
        if (foreground != null)
            result.add(foreground);
        return result;
    }
}
