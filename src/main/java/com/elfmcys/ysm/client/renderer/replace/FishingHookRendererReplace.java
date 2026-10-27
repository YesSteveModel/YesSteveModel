package com.elfmcys.ysm.client.renderer.replace;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import org.spongepowered.asm.mixin.Unique;

public class FishingHookRendererReplace {
    public static boolean renderInMixin(FishingHook entity, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        return entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP).map(cap -> {
            if (cap.isPresentationAllowed()) {
                // 鱼漂会上下乱串，这里强制归0
                entity.setXRot(0);
                entity.xRotO = 0;

                // 渲染鱼漂
                if (!RegisterEntityRenderersEvent.getProjectRenderer().renderReplacement(cap, yaw, partialTick,
                    poseStack, bufferSource, packedLight))
                    return true;

                // 渲染鱼线
                Player player = entity.getPlayerOwner();
                if (player != null) {
                    poseStack.pushPose();
                    renderFishingLine(entity, partialTick, poseStack, bufferSource, player);
                    poseStack.popPose();
                }
                return false;
            }
            return true;
        }).orElse(true);
    }

    @SuppressWarnings("all")
    private static void renderFishingLine(FishingHook hook, float partialTicks, PoseStack poseStack,
        MultiBufferSource bufferSource, Player player) {
        // 确定钓竿持握方向
        int handDirection = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        ItemStack mainHandItem = player.getMainHandItem();
        if (!mainHandItem.canPerformAction(ToolActions.FISHING_ROD_CAST)) {
            handDirection = -handDirection;
        }

        // 计算玩家动画相关参数
        float attackAnimProgress = player.getAttackAnim(partialTicks);
        float swingAnim = Mth.sin(Mth.sqrt(attackAnimProgress) * Mth.PI);
        float bodyRot = Mth.lerp(partialTicks, player.yBodyRotO, player.yBodyRot) * (Mth.PI / 180F);
        double bodyRotSin = Mth.sin(bodyRot);
        double bodyRotCos = Mth.cos(bodyRot);
        double handOffset = handDirection * 0.35;
        double armDistance = 0.8;

        // 计算玩家钓竿位置
        double playerX, playerY, playerZ;
        float eyeHeightOffset;

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        Options options = dispatcher.options;
        if ((options != null && options.getCameraType().isFirstPerson()) && player == Minecraft.getInstance().player) {
            // 第一人称视角计算
            double fovScale = 960.0 / options.fov().get();
            Vec3 cameraPlanePoint =
                dispatcher.camera.getNearPlane().getPointOnPlane((float)handDirection * 0.525F, -0.1F);
            cameraPlanePoint = cameraPlanePoint.scale(fovScale);
            cameraPlanePoint = cameraPlanePoint.yRot(swingAnim * 0.5F);
            cameraPlanePoint = cameraPlanePoint.xRot(-swingAnim * 0.7F);

            playerX = Mth.lerp(partialTicks, player.xo, player.getX()) + cameraPlanePoint.x;
            playerY = Mth.lerp(partialTicks, player.yo, player.getY()) + cameraPlanePoint.y;
            playerZ = Mth.lerp(partialTicks, player.zo, player.getZ()) + cameraPlanePoint.z;
            eyeHeightOffset = player.getEyeHeight();
        } else {
            // 第三人称视角计算
            playerX =
                Mth.lerp(partialTicks, player.xo, player.getX()) - bodyRotCos * handOffset - bodyRotSin * armDistance;
            playerY = player.yo + player.getEyeHeight() + (player.getY() - player.yo) * partialTicks - 0.45;
            playerZ =
                Mth.lerp(partialTicks, player.zo, player.getZ()) - bodyRotSin * handOffset + bodyRotCos * armDistance;
            eyeHeightOffset = player.isCrouching() ? -0.1875F : 0.0F;
        }

        // 计算鱼钩位置
        double hookX = Mth.lerp(partialTicks, hook.xo, hook.getX());
        double hookY = Mth.lerp(partialTicks, hook.yo, hook.getY()) + 0.25;
        double hookZ = Mth.lerp(partialTicks, hook.zo, hook.getZ());

        // 计算钓线向量
        float x = (float)(playerX - hookX);
        float y = (float)(playerY - hookY) + eyeHeightOffset;
        float z = (float)(playerZ - hookZ);

        // 获取钓线颜色
        float[] color = getLineColor(hook);

        // 渲染钓线
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lineStrip());
        PoseStack.Pose last = poseStack.last();
        for (int i = 0; i <= 16; ++i) {
            stringVertex(x, y, z, buffer, last, fraction(i), fraction(i + 1), color[0], color[1], color[2]);
        }

        // Iris 兼容性处理
        if (IrisCompat.isInstalled()) {
            buffer.vertex(0.0, 0.0, 0.0).color(0, 0, 0, 255).normal(0.0F, 0.0F, 0.0F).endVertex();
        }
    }

    @Unique
    private static float[] getLineColor(FishingHook fishingHook) {
        return new float[] {0f, 0f, 0f};
    }

    @Unique
    private static float fraction(int numerator) {
        return (float)numerator / (float)16;
    }

    @Unique
    private static void stringVertex(float pX, float pY, float pZ, VertexConsumer consumer, PoseStack.Pose pose,
        float fraction1, float fraction2, float r, float g, float b) {
        float x = pX * fraction1;
        float y = pY * (fraction1 * fraction1 + fraction1) * 0.5F + 0.25F;
        float z = pZ * fraction1;

        float nx = pX * fraction2 - x;
        float ny = pY * (fraction2 * fraction2 + fraction2) * 0.5F + 0.25F - y;
        float nz = pZ * fraction2 - z;
        float sqrt = Mth.sqrt(nx * nx + ny * ny + nz * nz);

        nx /= sqrt;
        ny /= sqrt;
        nz /= sqrt;

        consumer.vertex(pose.pose(), x, y, z).color(r, g, b, 1.0F).normal(pose.normal(), nx, ny, nz).endVertex();
    }
}
