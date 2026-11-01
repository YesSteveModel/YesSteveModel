package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.asset.ModelAssetSelector;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import org.jetbrains.annotations.Nullable;

/** Keeps presentation animation inputs; demand and image state belong to their owners. */
final class CatalogModelCardState implements AutoCloseable, ClientDemandCoordinator.CardDisplay {
    private final CustomGuiPlayerEntity entity;
    private final ClientDemandCoordinator.PageScope page;
    private final ClientDemandCoordinator.Target target;
    private @Nullable ClientDemandCoordinator.EntityScope scope;
    private boolean active;
    private final PageImages.Image preview;
    private final PageImages.Image background;
    private final PageImages.Image foreground;
    private final CatalogModelPreviewAnimationState previewAnimations = new CatalogModelPreviewAnimationState();
    private ModelRenderTarget configuredTarget;

    CatalogModelCardState(PageImages images, ClientDemandCoordinator.PageScope page, ClientCatalogEntry entry,
        CatalogModelMetadata metadata, CustomGuiPlayerEntity entity) {
        this.entity = entity;
        this.page = page;
        this.target = new ClientDemandCoordinator.Target(entry.modelHash(), metadata.defaultTexture());
        page.card(this, target);
        preview = images.preview(entry.modelHash());
        var settings = entry.displayRepresentation().view().getManifest().info().settings();
        background = settings.hasGuiBackground()
            ? images.presentation(entry.modelHash(), ModelAssetSelector.PresentationAsset.GUI_BACKGROUND, 0) : null;
        foreground = settings.hasGuiForeground()
            ? images.presentation(entry.modelHash(), ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, 0) : null;
    }

    @Nullable
    ModelRenderTarget renderTarget() {
        return active ? entity.displayedModel() : null;
    }

    ClientDemandCoordinator.Target target() {
        return target;
    }

    @Nullable
    TextureHolder preview() {
        return preview.texture();
    }

    boolean loading() {
        return preview.loading() || active && scope != null && scope.failure() == null
            && (scope.hasUnfinishedWork() || page.preparing(target));
    }

    boolean missingPreview() {
        return preview.absent() && renderTarget() == null && !loading() && loadError() == null;
    }

    @Nullable
    TextureHolder background() {
        return background == null ? null : background.texture();
    }

    @Nullable
    TextureHolder foreground() {
        return foreground == null ? null : foreground.texture();
    }

    @Nullable
    Throwable loadError() {
        var failure = page.feedback(target).failure();
        if (failure == null && scope != null)
            failure = scope.failure();
        if (failure == null)
            failure = entity.modelLoadFailure();
        if (failure == null)
            failure = preview.failure();
        if (failure == null && background != null)
            failure = background.failure();
        if (failure == null && foreground != null)
            failure = foreground.failure();
        return failure;
    }

    void updatePreviewAnimations(boolean hovered, boolean focused, long now) {
        var installed = renderTarget();
        if (installed == null)
            return;
        if (configuredTarget != installed) {
            var animations = java.util.Objects.requireNonNull(installed.playerResources()).animations();
            previewAnimations.configure(installed.info().getSettings().previewAnimation().orElse(""),
                animations.containsKey(AnimationRegister.HOVER),
                animations.containsKey(AnimationRegister.HOVER_FADEOUT), () -> {
                    var fadeout = animations.get(AnimationRegister.HOVER_FADEOUT);
                    return fadeout == null ? 0 : fadeout.animationLength * 50;
                }, animations.containsKey(AnimationRegister.FOCUS));
            configuredTarget = installed;
        }
        previewAnimations.apply(entity.getPreviewInfo(), hovered, focused, now);
    }

    @Override
    public void activate(ModelResourceDemand.Mode mode) {
        entity.setResourceDemandMode(mode);
        if (!active) {
            entity.updateModelAndTexture(target.modelId(), target.texture());
            scope = entity.demandScope();
            active = true;
        }
    }

    @Override
    public void deactivate() {
        if (!active)
            return;
        active = false;
        scope = null;
        try {
            entity.revokeAssignment();
        } finally {
            configuredTarget = null;
            previewAnimations.reset();
        }
    }

    @Override
    public void close() {
        try {
            page.removeCard(this);
            deactivate();
        } finally {
            entity.retire();
        }
    }
}
