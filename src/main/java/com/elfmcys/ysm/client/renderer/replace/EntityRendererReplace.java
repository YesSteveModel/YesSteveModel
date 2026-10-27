package com.elfmcys.ysm.client.renderer.replace;

import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

public class EntityRendererReplace {
    public static boolean renderInMixin(Entity entity, float yawIn, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        return entity.getCapability(VehicleAnimatableCapabilityProvider.CAP).map(cap -> {
            if (cap.isPresentationAllowed()) {
                float yaw = getYaw(entity, yawIn, partialTick);
                return !RegisterEntityRenderersEvent.getVehicleRenderer().renderReplacement(cap, yaw, partialTick,
                    poseStack, bufferSource, packedLight);
            }
            return true;
        }).orElse(true);
    }

    public static float getYaw(Entity entity, float yawIn, float partialTick) {
        float yaw = yawIn;

        if (entity instanceof LivingEntity livingEntity) {
            yaw = getLivingEntityYaw(livingEntity, partialTick);
        } else if (entity instanceof AbstractMinecart minecart) {
            yaw = getMinecartYaw(minecart, partialTick, yaw);
        }
        return yaw;
    }

    private static float getLivingEntityYaw(LivingEntity livingEntity, float partialTick) {
        float yaw = Mth.rotLerp(partialTick, livingEntity.yBodyRotO, livingEntity.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, livingEntity.yHeadRotO, livingEntity.yHeadRot);

        boolean shouldSit = livingEntity.isPassenger()
            && (livingEntity.getVehicle() != null && livingEntity.getVehicle().shouldRiderSit());
        if (shouldSit && livingEntity.getVehicle() instanceof LivingEntity vehicle) {
            yaw = Mth.rotLerp(partialTick, vehicle.yBodyRotO, vehicle.yBodyRot);

            float wrappedYawDiff = Mth.wrapDegrees(headYaw - yaw);
            wrappedYawDiff = Mth.clamp(wrappedYawDiff, -85.0F, 85.0F);

            yaw = headYaw - wrappedYawDiff;
            if (wrappedYawDiff * wrappedYawDiff > 2500.0F) {
                yaw += wrappedYawDiff * 0.2F;
            }
        }
        return yaw;
    }

    private static float getMinecartYaw(AbstractMinecart minecart, float partialTick, float yaw) {
        double interpX = Mth.lerp(partialTick, minecart.xOld, minecart.getX());
        double interpY = Mth.lerp(partialTick, minecart.yOld, minecart.getY());
        double interpZ = Mth.lerp(partialTick, minecart.zOld, minecart.getZ());
        Vec3 basePos = minecart.getPos(interpX, interpY, interpZ);
        if (basePos != null) {
            Vec3 posFront = minecart.getPosOffs(interpX, interpY, interpZ, 0.3F);
            Vec3 posBack = minecart.getPosOffs(interpX, interpY, interpZ, -0.3F);
            if (posFront == null) {
                posFront = basePos;
            }

            if (posBack == null) {
                posBack = basePos;
            }
            Vec3 offset = posBack.add(-posFront.x, -posFront.y, -posFront.z);
            if (offset.length() != 0) {
                yaw = (float)(Math.atan2(offset.z, offset.x) * 180 / Math.PI);
            }
        }
        return yaw;
    }
}
