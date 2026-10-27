package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.geo.GeoEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

public class CustomVehicleRenderer extends GeoEntityRenderer<Entity, CustomVehicleEntity> {
    public CustomVehicleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(Entity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
        int packedLight) {
        if (Minecraft.getInstance().player == null || entity.isInvisibleTo(Minecraft.getInstance().player)) {
            return;
        }
        entity.getCapability(VehicleAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.checkModelUpdate();
            render(cap, yaw, partialTick, poseStack, bufferSource, packedLight);
        });
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation(Entity entity) {
        return entity.getCapability(VehicleAnimatableCapabilityProvider.CAP)
            .map(CustomVehicleEntity::getTextureLocation).orElse(MissingTextureAtlasSprite.getLocation());
    }
}
