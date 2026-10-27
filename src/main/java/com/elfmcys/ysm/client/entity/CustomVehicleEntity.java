package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.config.ClientConfig;

import com.elfmcys.ysm.client.controller.VehicleOriginController;
import com.elfmcys.ysm.client.controller.collections.VehicleControllerCollection;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.VehicleModelResources;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import org.joml.Vector3f;

public class CustomVehicleEntity extends AnimatableEntity<Entity> {

    public CustomVehicleEntity(Entity vehicle) {
        super(vehicle, true);
    }

    @Override
    protected String requestedRenderTargetId() {
        var hash = getModelHash();
        return hash == null ? null : ClientModelService.instance()
            .findRenderTarget(hash, RenderTargetKind.VEHICLE, entity.getType().builtInRegistryHolder().key().location())
            .orElse(null);
    }

    @Override
    protected String fallbackRenderTargetId() {
        return null;
    }

    @Override
    protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {
        Objects.requireNonNull(target.vehicleResources(), "vehicle resources").controllerFactory().accept(this,
            animations);
    }

    @Override
    protected boolean presentationPermission() {
        return !ClientConfig.DISABLE_VEHICLE_MODEL.get();
    }

    @Override
    protected AnimationControllerData resolveControllerData(ModelRenderTarget target, String name) {
        return target.vehicleResources().controllers().get(name);
    }

    @Nullable
    public Vector3f getRotation() {
        if (!hasInstalledModel())
            return null;
        var origin =
            (VehicleOriginController)getAnimationData().getAnimationController(VehicleControllerCollection.NAME_ORIGIN);
        return origin == null ? null : origin.getRotation();
    }

    @Override
    protected GeoModel resolveGeoModel(ModelRenderTarget target, String texture) {
        return Objects.requireNonNull(target.vehicleResources(), "vehicle resources").model();
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Override
    protected AnimationStore animationSource() {
        return getModelRenderTarget().vehicleResources().animations();
    }

    @Override
    public Animation getAnimation(String name) {
        return readAnimation(getModelRenderTarget().vehicleResources().animations(), name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return getModelRenderTarget().vehicleResources().controllers().get(animationControllerName);
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
