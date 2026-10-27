package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ReplacePlayerHandRenderEvent {
    @SubscribeEvent
    public static void onRenderHand(RenderArmEvent event) {
        if (!YesSteveModel.isAvailable() || ClientConfig.DISABLE_SELF_MODEL.get()
            || ClientConfig.DISABLE_SELF_HANDS.get() || !(event.getPlayer() instanceof LocalPlayer player)) {
            return;
        }
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> render(event, player, cap));
    }

    static void render(RenderArmEvent event, LocalPlayer player, PlayerAnimatableCapability cap) {
        if (RegisterEntityRenderersEvent.getFirstPersonArmRenderer().render(player, cap, event.getArm(),
            event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(),
            Minecraft.getInstance().getPartialTick())) {
            event.setCanceled(true);
        }
    }
}
