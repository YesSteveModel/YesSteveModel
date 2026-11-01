package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceLease;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Math;
import org.joml.Vector2f;

import java.util.Objects;

/**
 * 使用玩家模型的实体
 */
public abstract class CustomHumanoidEntity<T extends LivingEntity> extends AnimatableEntity<T> {
    protected String textureName;

    private final FloatArrayList headRotBackup = new FloatArrayList(2);
    private AnimatedGeoModel headOwner;
    private boolean fireInitEvent = false;

    /**
     * 用于禁用 YSM 模型，因为有玩家想强制显示原版玩家模型
     */
    private boolean disabled = false;

    /**
     * 专为 tacz 枪械事件使用的，用来将枪械动画重置
     */
    private boolean tacGunAnimationNeedReload = false;

    protected CustomHumanoidEntity(T entity, boolean asyncUpdate) {
        this(entity, asyncUpdate, new EntityModelBinding());
    }

    CustomHumanoidEntity(T entity, boolean asyncUpdate, EntityModelBinding modelBinding) {
        super(entity, asyncUpdate, modelBinding);
    }

    @Override
    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<T>> animationEvent, boolean shouldUpdate) {
        var model = getLoadedGeoModel();
        if (model != null) {
            prepareHeadHistory(model);
            // 更新头部旋转
            var heads = model.locatorGroup(PlayerLocator.get().head);
            var headRotBak = headRotBackup;
            for (var i = 0; i < heads.size(); i++) {
                var head = heads.get(i);

                float rotX;
                float rotY;
                if (shouldUpdate) {
                    rotX = head.getRotationX();
                    rotY = head.getRotationY();
                    headRotBak.set(i * 2, rotX);
                    headRotBak.set(i * 2 + 1, rotY);
                } else {
                    rotX = headRotBak.getFloat(i * 2);
                    rotY = headRotBak.getFloat(i * 2 + 1);
                }

                var data = animationEvent.getExtraData();
                head.setRotationX(rotX + Math.toRadians(data.headPitch));
                head.setRotationY(rotY + Math.toRadians(data.netHeadYaw));
            }
        }
    }

    @Override
    protected void updateRenderData(AnimationEvent<?> event, GeoRenderData data) {
        super.updateRenderData(event, data);
        data.renderLayersFirst = renderLayersFirst();
    }

    @Override
    protected void recoverLastCodedAnimation() {
        var model = getLoadedGeoModel();
        if (model != null) {
            prepareHeadHistory(model);
            var heads = model.locatorGroup(PlayerLocator.get().head);
            var headRotBak = headRotBackup;
            for (var i = 0; i < heads.size(); i++) {
                var head = heads.get(i);
                head.setRotationX(headRotBak.getFloat(i * 2));
                head.setRotationY(headRotBak.getFloat(i * 2 + 1));
            }
        }
    }

    @Override
    protected HumanoidStateTracker<T> createStateTracker(T entity) {
        return new HumanoidStateTracker<T>(entity);
    }

    @Override
    protected AnimationStore animationSource() {
        return getModelRenderTarget().playerResources().animations();
    }

    @Override
    public HumanoidStateTracker<T> getStateTracker() {
        return (HumanoidStateTracker<T>)super.getStateTracker();
    }

    public void updateTextureName(String textureName) {
        rejectNewUse();
        waitForAsyncUpdate();
        this.textureName = textureName;
        if (isAssigned())
            updateModelHash(getModelHash());
    }

    public void updateModelAndTexture(Hash256 modelHash, String textureName) {
        rejectNewUse();
        waitForAsyncUpdate();
        this.textureName = textureName;
        updateModelHash(modelHash);
    }

    public void updateModelAndTexture(String modelPath, String textureName) {
        ClientModelService.instance().resolvePath(modelPath)
            .ifPresent(hash -> updateModelAndTexture(hash, textureName));
    }

    @Override
    protected String requestedTextureName() {
        return textureName == null ? "" : textureName;
    }

    public void setDisabled(boolean disabled) {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        this.disabled = disabled;
        restrictionsChanged();
    }

    public boolean isDisabled() {
        return disabled;
    }

    @Override
    protected boolean presentationPermission() {
        return !disabled;
    }

    private void prepareHeadHistory(AnimatedGeoModel model) {
        if (headOwner == model)
            return;
        headOwner = model;
        var heads = model.locatorGroup(PlayerLocator.get().head);
        var headRot = headRotBackup;
        headRot.size(heads.size() * 2);
        for (var i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var rot = new Vector2f(head.getRotationX(), head.getRotationY());
            headRot.set(i * 2, rot.x);
            headRot.set(i * 2 + 1, rot.y);
        }
    }

    @Override
    protected void onModelEnded() {
        super.onModelEnded();
        if (hasInstalledModel())
            return;
        headRotBackup.clear();
        headOwner = null;
        tacGunAnimationNeedReload = false;
        fireInitEvent = false;
    }

    @Override
    protected Runnable prepareRoleInstallation(ModelRenderTarget target,
        EntityExpressionRuntime.PreparedInstallation prepared) {
        return () -> {
            headRotBackup.clear();
            headOwner = null;
            tacGunAnimationNeedReload = false;
            fireInitEvent = true;
        };
    }

    @Override
    protected void dispatchPersistentEvents(EntityExpressionRuntime.Evaluation evaluation, boolean shouldUpdate) {
        if (fireInitEvent) {
            fireInitEvent = false;
            evaluation.dispatchPlayerInit();
        }
        evaluation.dispatchPlayerUpdate(shouldUpdate);
    }

    public ConditionManager getConditionManager() {
        return getModelRenderTarget().playerResources().conditionManager();
    }

    @Override
    protected GeoModel resolveGeoModel(ModelRenderTarget target, String texture) {
        var resources = Objects.requireNonNull(target.playerResources(), "humanoid resources");
        var variant = resources.variants().get(texture);
        return (variant == null ? resources.defaultVariant() : variant).mainModel();
    }

    @Override
    protected AnimationControllerData resolveControllerData(ModelRenderTarget target, String name) {
        return target.playerResources().animationControllers().get(name);
    }

    @Nullable
    @Override
    public Animation getAnimation(String name) {
        return readAnimation(getModelRenderTarget().playerResources().animations(), name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String name) {
        return getModelRenderTarget().playerResources().animationControllers().get(name);
    }

    public String getTextureName() {
        var target = getModelRenderTarget();
        if (target == null)
            return "";
        var resources = target.playerResources();
        var loaded = getLoadedGeoModel().getModel();
        for (var entry : resources.variants().entrySet()) {
            if (entry.getValue().mainModel() == loaded)
                return entry.getKey();
        }
        return resources.defaultTextureName();
    }

    @Override
    @NotNull
    @SuppressWarnings("unchecked")
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Nullable
    public PlayerModelVariant getModelVariant() {
        var target = getModelRenderTarget();
        return target == null ? null : target.playerResources().variants().get(getTextureName());
    }

    @Override
    public float getHeightScale() {
        return getModelRenderTarget().info().getPlayerSettings().heightScale();
    }

    @Override
    public float getWidthScale() {
        return getModelRenderTarget().info().getPlayerSettings().widthScale();
    }

    public boolean renderLayersFirst() {
        return getModelRenderTarget().info().getPlayerSettings().renderLayersFirst();
    }

    public boolean isTacGunAnimationNeedReload() {
        return tacGunAnimationNeedReload;
    }

    public void setTacGunAnimationNeedReload(boolean tacGunAnimationNeedReload) {
        this.tacGunAnimationNeedReload = tacGunAnimationNeedReload;
    }

}
