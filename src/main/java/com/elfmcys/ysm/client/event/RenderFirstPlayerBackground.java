package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.entity.FirstPersonPart;
import com.elfmcys.ysm.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class RenderFirstPlayerBackground {
    // A successful hand phase consumes the background once, independently of animation time.
    private static LocalPlayer renderedPlayer;

    public static void reset() {
        renderedPlayer = null;
    }

    @SubscribeEvent
    public static void onRenderLevelLase(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            reset();
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (!YesSteveModel.isAvailable() || ClientConfig.DISABLE_SELF_MODEL.get()
            || ClientConfig.DISABLE_SELF_HANDS.get()) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || renderedPlayer == player) {
            return;
        }
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> render(event, player, cap));
    }

    static void render(RenderHandEvent event, LocalPlayer player, PlayerAnimatableCapability cap) {
        if (renderedPlayer == player) {
            return;
        }
        var renderer = RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        try {
            if (Minecraft.getInstance().options.bobView().get()) {
                bobView(poseStack, event.getPartialTick(), player);
            }
            poseStack.translate(0, -1.5, 0);
            if (renderer.render(player, cap, FirstPersonPart.BACKGROUND, poseStack, event.getMultiBufferSource(),
                event.getPackedLight(), OverlayTexture.NO_OVERLAY, event.getPartialTick())) {
                renderedPlayer = player;
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void bobView(PoseStack poseStack, float partialTick, Player player) {
        float walk = player.walkDist - player.walkDistO;
        float phase = -(player.walkDist + walk * partialTick);
        float bob = Mth.lerp(partialTick, player.oBob, player.bob);
        poseStack.translate(-Mth.sin(phase * (float)Math.PI) * bob * 0.5F,
            Math.abs(Mth.cos(phase * (float)Math.PI) * bob), 0);
        poseStack.mulPose(Axis.ZN.rotationDegrees(Mth.sin(phase * (float)Math.PI) * bob * 3));
        poseStack.mulPose(Axis.XN.rotationDegrees(Math.abs(Mth.cos(phase * (float)Math.PI - 0.2F) * bob) * 5));
    }
}
