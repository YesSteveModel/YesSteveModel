package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.geckolib3.geo.GeoProjectilesRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.NotNull;

public class CustomProjectileRenderer extends GeoProjectilesRenderer<Projectile, CustomProjectileEntity> {
    public CustomProjectileRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(Projectile entity, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        if (Minecraft.getInstance().player == null || entity.isInvisibleTo(Minecraft.getInstance().player)) {
            return;
        }
        entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.checkModelUpdate();
            render(cap, yaw, partialTick, poseStack, bufferSource, packedLight);
        });
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation(Projectile entity) {
        return entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP)
            .map(CustomProjectileEntity::getTextureLocation).orElse(MissingTextureAtlasSprite.getLocation());
    }
}
