package com.elfmcys.ysm.client.compat.swarfare;

import com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent;
import com.atsuishio.superbwarfare.client.renderer.CustomGunRenderer;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ReplacePlayerArmRender {
    private boolean enabled = true;

    @SubscribeEvent
    public void onRenderHand(RenderPlayerArmEvent event) {
        if (!enabled || !YesSteveModel.isAvailable())
            return;
        HostInput input;
        try {
            input = readHost(event);
        } catch (LinkageError incompatibleApi) {
            enabled = false;
            YesSteveModel.LOGGER.warn("Disabling Superb Warfare hand adapter: incompatible API", incompatibleApi);
            return;
        }
        if (input.player() == null)
            return;
        input.player().getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .ifPresent(cap -> render(event, input, cap));
    }

    // Keep optional API linkage isolation outside YSM output and consumer callbacks.
    private static HostInput readHost(RenderPlayerArmEvent event) {
        var bone = event.getBone();
        return new HostInput(event.getLocalPlayer(), event.getArm(), event.getStack(), event.getCurrentBuffer(),
            event.getPackedLightIn(), event.isUseOldHandRender(), bone.getPivotX(), bone.getPivotY(), bone.getPivotZ(),
            CustomGunRenderer.SCALE_RECIPROCAL);
    }

    static void render(RenderPlayerArmEvent event, HostInput input, PlayerAnimatableCapability cap) {
        PoseStack stack = input.stack();
        stack.pushPose();
        try {
            float side = input.arm() == HumanoidArm.LEFT ? -1 : 1;
            stack.translate(side * input.scaleReciprocal(), 2 * input.scaleReciprocal(), 0);
            stack.translate(side * 0.275, 0.0625, 0);
            if (input.oldPlacement()) {
                stack.translate((input.pivotX() - 1) / 16f, (input.pivotY() - 2) / 16f, input.pivotZ() / 16f);
            } else {
                stack.translate(input.pivotX() / 16f, (input.pivotY() + 7) / 16f, input.pivotZ() / 16f);
                stack.mulPose(Axis.YP.rotationDegrees(180));
                stack.mulPose(Axis.ZP.rotationDegrees(180));
            }
            if (RegisterEntityRenderersEvent.getFirstPersonArmRenderer().render(input.player(), cap, input.arm(), stack,
                input.buffers(), input.light(), Minecraft.getInstance().getPartialTick())) {
                event.setCanceled(true);
            }
        } finally {
            stack.popPose();
        }
    }

    record HostInput(LocalPlayer player, HumanoidArm arm, PoseStack stack, MultiBufferSource buffers, int light,
        boolean oldPlacement, float pivotX, float pivotY, float pivotZ, float scaleReciprocal) {
    }
}
