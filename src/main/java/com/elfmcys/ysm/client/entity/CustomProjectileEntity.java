package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.config.ClientConfig;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ProjectileModelResources;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;

public class CustomProjectileEntity extends AnimatableEntity<Projectile> {

    public CustomProjectileEntity(Projectile projectile) {
        super(projectile, true);
    }

    @Override
    protected String requestedRenderTargetId() {
        var hash = getModelHash();
        return hash == null ? null : ClientModelService.instance().findRenderTarget(hash, RenderTargetKind.PROJECTILE,
            entity.getType().builtInRegistryHolder().key().location()).orElse(null);
    }

    @Override
    protected String fallbackRenderTargetId() {
        return null;
    }

    @Override
    protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {
        Objects.requireNonNull(target.projectileResources(), "projectile resources").controllerFactory().accept(this,
            animations);
    }

    @Override
    protected boolean presentationPermission() {
        return !ClientConfig.DISABLE_PROJECTILE_MODEL.get();
    }

    @Override
    protected AnimationControllerData resolveControllerData(ModelRenderTarget target, String name) {
        return target.projectileResources().controllers().get(name);
    }

    @Override
    protected GeoModel resolveGeoModel(ModelRenderTarget target, String texture) {
        return Objects.requireNonNull(target.projectileResources(), "projectile resources").model();
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Override
    protected AnimationStore animationSource() {
        return getModelRenderTarget().projectileResources().animations();
    }

    @Override
    public Animation getAnimation(String name) {
        return readAnimation(getModelRenderTarget().projectileResources().animations(), name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return getModelRenderTarget().projectileResources().controllers().get(animationControllerName);
    }

    @Override
    public float getWidthScale() {
        return 0.7F;
    }

    @Override
    public float getHeightScale() {
        return 0.7F;
    }

}
