package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.config.ClientConfig;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.Objects;
import java.util.function.Supplier;

public class CustomFirstPersonArmEntity extends AnimatableEntity<LocalPlayer> {
    private final PlayerAnimatableCapability mainModelEntity;
    private final FirstPersonModelState firstPersonState = new FirstPersonModelState();

    public CustomFirstPersonArmEntity(LocalPlayer player, PlayerAnimatableCapability mainModelEntity) {
        super(player, false);
        this.mainModelEntity = mainModelEntity;
        if (mainModelEntity.isAssigned())
            updateModelHash(mainModelEntity.getModelHash());
    }

    @Override
    protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {
        target.playerResources().fpArmControllerFactory().accept(this, animations);
    }

    @Override
    protected boolean presentationPermission() {
        return mainModelEntity != null && mainModelEntity.isPresentationAllowed()
            && !ClientConfig.DISABLE_SELF_HANDS.get();
    }

    public PlayerAnimatableCapability getMainModelEntity() {
        return mainModelEntity;
    }

    public boolean updateArm(float partialTick) {
        // The hand pass runs after LevelRenderer has cleared its world scope.
        var updated = updateAnimationOnly(partialTick, RenderContext.levelImmutable());
        if (!updated)
            firstPersonState.invalidateArms();
        return updated;
    }

    @Override
    protected void tickAnimation(AnimationEvent<AnimatableEntity<LocalPlayer>> event) {
        var sample = event.renderTicks;
        firstPersonState.prepareArms(sample, false);
        var completed = false;
        try {
            super.tickAnimation(event);
            firstPersonState.prepareArms(sample, lastFrameUpdated);
            completed = true;
        } finally {
            if (!completed)
                firstPersonState.invalidateArms();
        }
    }

    public boolean synchronizeFirstPersonModel() {
        waitForAsyncUpdate();
        checkModelUpdate();
        var loaded = getLoadedGeoModel();
        firstPersonState.bind(getModelRenderTarget(), loaded == null ? null : loaded.getModel());
        return getModelRenderTarget() != null && loaded != null;
    }

    public boolean hasPart(FirstPersonPart part) {
        return firstPersonState.hasPart(part);
    }

    public record PreparedPart(Object installation, GeoModel model, FirstPersonPart part, long revision) {
    }

    public PreparedPart preparePart(FirstPersonPart part, float partialTick) {
        if (!synchronizeFirstPersonModel() || !hasPart(part) || !isPresentationAllowed())
            return null;
        if (part == FirstPersonPart.BACKGROUND) {
            firstPersonState.prepareBackground(
                entity.tickCount + (partialTick != 1f ? partialTick : Minecraft.getInstance().getFrameTime()));
        } else if (!updateArm(partialTick))
            return null;
        if (!isPresentationAllowed() || getLoadedGeoModel() == null || !hasPart(part))
            return null;
        return new PreparedPart(installationIdentity(), getLoadedGeoModel().getModel(), part,
            firstPersonState.revision(part));
    }

    public boolean canPrepare(FirstPersonPart part, RenderContext context) {
        return canPrepare(context) && hasPart(part);
    }

    public BlockReason getPreparationBlockReason(FirstPersonPart part, RenderContext context) {
        var reason = getPreparationBlockReason(context);
        return reason != BlockReason.NONE ? reason : hasPart(part) ? BlockReason.NONE : BlockReason.MISSING_PART;
    }

    public boolean canConsume(PreparedPart prepared) {
        return prepared != null && canConsumeInstallation(prepared.installation()) && getLoadedGeoModel() != null
            && getLoadedGeoModel().getModel() == prepared.model()
            && firstPersonState.revision(prepared.part()) == prepared.revision() && hasPart(prepared.part());
    }

    public boolean renderPart(PreparedPart prepared, Supplier<VertexConsumer> output, PoseStack.Pose pose, int light,
        int overlay, int color) {
        return inDrawBorrow(() -> {
            if (!canConsume(prepared))
                return false;
            var consumer = output.get();
            if (consumer == null || !canConsume(prepared))
                return false;
            var attributes =
                prepared.part() == FirstPersonPart.BACKGROUND ? null : getLoadedGeoModel().getBoneAttributes();
            return firstPersonState.render(prepared.part(), attributes, consumer, pose, light, overlay, color);
        });
    }

    @Override
    protected final void onModelEnded() {
        firstPersonState.clear();
        super.onModelEnded();
    }

    @Override
    protected void refreshAssignment() {
        if (isAssigned()) {
            if (mainModelEntity.isAssigned())
                updateModelHash(mainModelEntity.getModelHash());
            else
                revokeAssignment();
        }
    }

    @Override
    protected AnimationStore animationSource() {
        return getModelRenderTarget().playerResources().fpArmAnimations();
    }

    @Override
    public void checkModelUpdate() {
        super.checkModelUpdate();
        var loaded = getLoadedGeoModel();
        firstPersonState.bind(getModelRenderTarget(), loaded == null ? null : loaded.getModel());
    }

    @Override
    protected String requestedTextureName() {
        return mainModelEntity.getRequestedTextureName();
    }

    @Override
    protected String requestedRenderTargetId() {
        return RenderTargetIds.PLAYER;
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return getModelRenderTarget().playerResources().fpArmAnimationControllers().get(animationControllerName);
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Override
    public float getWidthScale() {
        return getModelRenderTarget().info().getPlayerSettings().widthScale();
    }

    @Override
    public float getHeightScale() {
        return getModelRenderTarget().info().getPlayerSettings().heightScale();
    }

    @Override
    public @Nullable Animation getAnimation(String name) {
        return readAnimation(getModelRenderTarget().playerResources().fpArmAnimations(), name);
    }

    public FPArmConditionManager getFPArmConditionManager() {
        return getModelRenderTarget().playerResources().fpArmConditionManager();
    }

    @Override
    protected GeoModel resolveGeoModel(ModelRenderTarget target, String texture) {
        var resources = Objects.requireNonNull(target.playerResources());
        var variant = resources.variants().get(texture);
        return (variant == null ? resources.defaultVariant() : variant).armModel();
    }

    @Override
    protected AnimationControllerData resolveControllerData(ModelRenderTarget target, String name) {
        return target.playerResources().fpArmAnimationControllers().get(name);
    }

    @Override
    protected RenderTargetExpressionModule expressions(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target) {
        return target.armExpressions();
    }

    @Override
    protected com.elfmcys.ysm.model.resource.client.CommonAsset
        assets(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target) {
        return target.armAssets();
    }

    @Override
    protected @Nullable Runnable prepareRoleInstallation(ModelRenderTarget target,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
        mainModelEntity.waitForAsyncUpdate();
        prepared.connectArmTo(mainModelEntity.getExpressionRuntime());
        return null;
    }
}
