package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.client.entity.FirstPersonPart;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.geo.CustomTranslucentRenderType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.common.MinecraftForge;

public class CustomFirstPersonArmRenderer {
    private CustomFirstPersonArmEntity armEntity = null;

    public boolean render(LocalPlayer player, PlayerAnimatableCapability cap, HumanoidArm arm, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, float partialTick) {
        return render(player, cap, FirstPersonPart.forArm(arm), poseStack, bufferSource, packedLight,
            OverlayTexture.NO_OVERLAY, partialTick);
    }

    public boolean render(LocalPlayer player, PlayerAnimatableCapability cap, FirstPersonPart part, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int overlay, float partialTick) {
        if (!canRender(player, cap)) {
            release();
            return false;
        }
        if (armEntity == null || !armEntity.isAssigned() || armEntity.getEntity() != player
            || armEntity.getMainModelEntity() != cap) {
            release();
            armEntity = new CustomFirstPersonArmEntity(player, cap);
        }

        var owner = armEntity;
        var prepared = owner.preparePart(part, partialTick);
        if (prepared == null)
            return false;
        poseStack.pushPose();
        try {
            if (part != FirstPersonPart.BACKGROUND) {
                poseStack.translate(part == FirstPersonPart.LEFT_ARM ? 0.25 : -0.25, 1.8, 0);
                poseStack.scale(-1, -1, 1);
            }
            var consumed = owner.renderPart(prepared, () -> {
                var renderEvent = new SpecialPlayerRenderEvent(player, cap, owner.getInstalledModelId());
                if (MinecraftForge.EVENT_BUS.post(renderEvent))
                    return null;
                if (owner != armEntity || !canRender(player, cap) || !owner.canConsume(prepared))
                    return null;
                var texture = renderEvent.getTextureLocationOverride() == null ? owner.getTextureLocation()
                    : renderEvent.getTextureLocationOverride();
                return bufferSource.getBuffer(CustomTranslucentRenderType.create(texture));
            }, poseStack.last(), packedLight, overlay, Color.WHITE.getColor());
            if (consumed && part != FirstPersonPart.BACKGROUND) {
                owner.countRender();
            }
            return consumed;
        } finally {
            poseStack.popPose();
        }
    }

    private static boolean canRender(LocalPlayer player, PlayerAnimatableCapability cap) {
        return YesSteveModel.isAvailable() && !ClientConfig.DISABLE_SELF_MODEL.get()
            && !ClientConfig.DISABLE_SELF_HANDS.get() && player != null && player == Minecraft.getInstance().player
            && cap.isPresentationAllowed();
    }

    public void release() {
        var previous = armEntity;
        armEntity = null;
        if (previous != null) {
            previous.retire();
        }
    }

    public void releaseForPlayer(LocalPlayer player) {
        if (armEntity != null && armEntity.getEntity() == player) {
            release();
        }
    }
}
