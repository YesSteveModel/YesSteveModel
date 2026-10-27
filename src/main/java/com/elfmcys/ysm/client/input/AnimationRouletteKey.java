package com.elfmcys.ysm.client.input;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.gui.AnimationRouletteScreen;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.util.InputCheckUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class AnimationRouletteKey {
    public static final KeyMapping ANIMATION_ROULETTE_KEY =
        new KeyMapping("key.yes_steve_model.animation_roulette.desc", KeyConflictContext.IN_GAME, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.category.yes_steve_model");

    public static final KeyMapping LOCK_ROULETTE_KEY =
        new KeyMapping("key.yes_steve_model.lock_roulette.desc", KeyConflictContext.IN_GAME, KeyModifier.ALT,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, "key.category.yes_steve_model");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.Key event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS && InputCheckUtil.keyIsMatch(event, ANIMATION_ROULETTE_KEY)
            && (!NetworkHandler.isRemoteChannelPresent() || ServerConfig.CAN_SWITCH_MODEL.get())) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    String modelId = cap.getModelId();
                    var model = cap.getModelRenderTarget();
                    if (model != null && !model.info().getExtraAnimations().isEmpty()) {
                        if (Minecraft.getInstance().screen == null) {
                            Minecraft.getInstance().setScreen(new AnimationRouletteScreen(modelId, model, cap));
                            return;
                        }
                        if (Minecraft.getInstance().screen instanceof AnimationRouletteScreen) {
                            Minecraft.getInstance().setScreen(null);
                        }
                    }
                });
            }
        }
    }
}
