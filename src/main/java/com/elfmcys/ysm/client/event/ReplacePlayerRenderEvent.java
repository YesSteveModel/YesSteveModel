package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.PlayerAnimatorCompat;
import com.elfmcys.ysm.client.compat.realcamera.RealCameraCompat;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.util.PersonView;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ReplacePlayerRenderEvent {
    @SubscribeEvent
    public static void onRender(RenderPlayerEvent.Pre event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        Player playerRender = event.getEntity();
        LocalPlayer playerSelf = Minecraft.getInstance().player;
        if (playerRender.equals(playerSelf) && ClientConfig.DISABLE_SELF_MODEL.get()) {
            return;
        }
        if (!playerRender.equals(playerSelf) && ClientConfig.DISABLE_OTHER_MODEL.get()) {
            return;
        }
        if (event.getEntity().isSpectator()) {
            return;
        }
        playerRender.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            var ctx = RenderUtil.extractRenderContext();
            if (cap.isPresentationAllowed()) {
                if (!PersonView.isFirstPersonView(cap) || FirstPersonCompat.isRenderingPlayer()
                    || RealCameraCompat.isActive() || (ClientConfig.DISABLE_EXTERNAL_FIRST_PERSON_ANIM.get()
                        || !PlayerAnimatorCompat.hasThirdPersonModelAnim(playerSelf))) {
                    RegisterEntityRenderersEvent.getPlayerRenderer().renderReplacement(cap, event.getEntity().getYRot(),
                        event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(),
                        event.getPackedLight(), () -> event.setCanceled(true));
                }
            }
        });
    }
}
