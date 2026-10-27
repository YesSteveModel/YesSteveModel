package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.renderer.replace.EntityRendererReplace;
import com.elfmcys.ysm.client.renderer.replace.FishingHookRendererReplace;
import com.elfmcys.ysm.client.renderer.replace.ProjectileRendererReplace;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.util.RenderUtil;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @WrapWithCondition(method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;"
                + "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private boolean replaceRender(EntityRenderer<?> renderer, Entity entity, float rotationYaw, float partialTicks,
        PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (!YesSteveModel.isAvailable()) {
            return true;
        }

        if (entity instanceof Projectile projectile && !ClientConfig.DISABLE_PROJECTILE_MODEL.get()) {
            // 鱼漂比较特殊
            if (projectile instanceof FishingHook hook) {
                return FishingHookRendererReplace.renderInMixin(hook, rotationYaw, partialTicks, poseStack, buffer,
                    packedLight);
            } else {
                return ProjectileRendererReplace.renderInMixin(projectile, rotationYaw, partialTicks, poseStack, buffer,
                    packedLight);
            }
        }

        if (!ClientConfig.DISABLE_VEHICLE_MODEL.get()) {
            RenderUtil.adjustPassengerPosition(entity, poseStack, partialTicks);
            return EntityRendererReplace.renderInMixin(entity, rotationYaw, partialTicks, poseStack, buffer,
                packedLight);
        }

        return true;
    }
}
