package com.elfmcys.ysm.client.renderer.replace;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.projectile.Projectile;

public class ProjectileRendererReplace {
    public static boolean renderInMixin(Projectile entity, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        return entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP).map(cap -> {
            if (cap.isPresentationAllowed()) {
                return !RegisterEntityRenderersEvent.getProjectRenderer().renderReplacement(cap, yaw, partialTick,
                    poseStack, bufferSource, packedLight);
            }
            return true;
        }).orElse(true);
    }
}
