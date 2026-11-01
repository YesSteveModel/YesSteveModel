package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.gui.GuiFrameInput;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.asset.PageImages;
import org.jetbrains.annotations.Nullable;

/** Keeps presentation animation inputs; demand and image state belong to their owners. */
final class CatalogModelCardState implements AutoCloseable, ClientDemandCoordinator.CardDisplay {
    private final CustomGuiPlayerEntity entity;
    private final ClientDemandCoordinator.PageScope page;
    private final ClientDemandCoordinator.Target target;
    private @Nullable ClientDemandCoordinator.EntityScope scope;
    private boolean active;
    private final CatalogCardImages images;
    private final CatalogModelPreviewAnimationState previewAnimations = new CatalogModelPreviewAnimationState();
    private ModelRenderTarget configuredTarget;
    private boolean borrowed;
    private boolean deactivationPending;
    private boolean closed;
    private boolean cleaned;
    private GuiFrameInput frameInput;
    private GeoRenderData prepared;

    CatalogModelCardState(CatalogPageFrame frame, ClientDemandCoordinator.PageScope page, ClientCatalogEntry entry,
        CatalogModelMetadata metadata, CustomGuiPlayerEntity entity) {
        this.entity = entity;
        this.page = page;
        this.target = new ClientDemandCoordinator.Target(entry.modelHash(), metadata.defaultTexture());
        page.card(this, target);
        frame.register(this);
        var settings = entry.displayRepresentation().view().getManifest().info().settings();
        images = new CatalogCardImages(frame.images(), entry.modelHash(), settings.hasGuiBackground(),
            settings.hasGuiForeground(), page.eagerLoading());
    }

    @Nullable
    ModelRenderTarget renderTarget() {
        return !closed && active ? entity.displayedModel() : null;
    }

    ClientDemandCoordinator.Target target() {
        return target;
    }

    @Nullable
    TextureHolder preview() {
        return images.preview();
    }

    boolean loading() {
        return images.previewLoading() || active && scope != null && scope.failure() == null
            && (scope.hasUnfinishedWork() || page.preparing(target));
    }

    boolean missingPreview() {
        return images.previewAbsent() && renderTarget() == null && !loading() && loadError() == null;
    }

    @Nullable
    TextureHolder background() {
        return images.background();
    }

    @Nullable
    TextureHolder foreground() {
        return images.foreground();
    }

    @Nullable
    Throwable loadError() {
        var failure = page.feedback(target).failure();
        if (failure == null && scope != null)
            failure = scope.failure();
        if (failure == null)
            failure = entity.modelLoadFailure();
        if (failure == null)
            failure = images.failure();
        return failure;
    }

    void updatePreviewAnimations(GuiFrameInput input) {
        var installed = entity.hasMatchingInstallation() ? entity.getModelRenderTarget() : null;
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
        previewAnimations.apply(entity.getPreviewInfo(), input.hovered(), input.focused(), input.now());
    }

    void previewPolicy(boolean eager, @Nullable PageImages images) {
        this.images.advancePolicy(eager, images);
    }

    java.util.List<PageImages.Image> images() {
        return images.preparations();
    }

    void captureInput(float partialTick, boolean hovered, boolean focused, long now, boolean disableRotation) {
        frameInput = GuiFrameInput.capture(entity.getEntity(), partialTick, disableRotation, true, hovered, focused, now);
        prepared = null;
    }

    GuiFrameInput frameInput() { return frameInput; }
    GeoRenderData prepared() { return prepared; }
    void prepared(GeoRenderData data) { prepared = data; }
    CustomGuiPlayerEntity entity() { return entity; }
    ClientDemandCoordinator.EntityScope scope() { return active ? scope : null; }
    boolean closed() { return closed || page.isClosed(); }
    void borrow() { borrowed = true; }
    void returned() {
        borrowed = false;
        if (closed) finishClose();
        else finishDeactivation();
    }

    @Override
    public void activate(ModelResourceDemand.Mode mode) {
        if (closed() || borrowed)
            return;
        entity.setResourceDemandMode(mode);
        if (!active) {
            entity.updateModelAndTexture(target.modelId(), target.texture());
            scope = entity.demandScope();
            if (scope != null)
                scope.useFrameInstallation();
            if (scope != null)
                page.entity(scope);
            active = true;
        }
    }

    @Override
    public void deactivate() {
        Throwable failure = null;
        if (active) {
            active = false;
            deactivationPending = true;
            var previous = scope;
            scope = null;
            try { if (previous != null) previous.close(); }
            catch (Throwable error) { failure = error; }
        }
        if (!borrowed) {
            try { finishDeactivation(); } catch (Throwable error) {
                failure = com.elfmcys.ysm.client.gui.GuiFrameTasks.append(failure, error);
            }
        }
        throwFailure(failure);
    }

    private void finishDeactivation() {
        if (!deactivationPending)
            return;
        deactivationPending = false;
        try {
            entity.revokeAssignment();
        } finally {
            configuredTarget = null;
            previewAnimations.reset();
            prepared = null;
        }
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        if (borrowed)
            return;
        finishClose();
    }

    private void finishClose() {
        if (cleaned)
            return;
        cleaned = true;
        Throwable failure = null;
        try { page.removeCard(this); } catch (Throwable error) { failure = error; }
        try { deactivate(); } catch (Throwable error) {
            failure = com.elfmcys.ysm.client.gui.GuiFrameTasks.append(failure, error);
        }
        try { entity.retire(); } catch (Throwable error) {
            failure = com.elfmcys.ysm.client.gui.GuiFrameTasks.append(failure, error);
        }
        throwFailure(failure);
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }
}
