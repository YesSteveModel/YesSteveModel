package com.elfmcys.ysm.client.input;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.PlayerMoveEvent;
import com.elfmcys.ysm.client.gui.AnimationRouletteScreen;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.util.InputCheckUtil;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static com.elfmcys.ysm.client.gui.AnimationRouletteScreen.addRootClassify;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ExtraAnimationKey {
    public static final List<KeyMapping> EXTRA_ANIMATION_KEYS = Lists.newArrayList();

    @SubscribeEvent
    public static void registerKeyBinding(RegisterKeyMappingsEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        for (int i = 0; i <= 7; i++) {
            String name = String.format("key.yes_steve_model.extra_animation.%d.desc", i);
            KeyMapping keyMapping = new KeyMapping(name, KeyConflictContext.IN_GAME, KeyModifier.NONE,
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.category.yes_steve_model");
            event.register(keyMapping);
            EXTRA_ANIMATION_KEYS.add(keyMapping);
        }
    }

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.Key event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        for (KeyMapping key : EXTRA_ANIMATION_KEYS) {
            if (event.getAction() == GLFW.GLFW_PRESS && InputCheckUtil.keyIsMatch(event, key) && player != null
                && !PlayerMoveEvent.isMoveKey(player)) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    var model = cap.getModelRenderTarget();
                    int index = EXTRA_ANIMATION_KEYS.indexOf(key);
                    var info = model.info();
                    var animations = info.getExtraAnimations();
                    if (animations.size() > index) {
                        String keyName = animations.get(index).key();
                        if ("#return".equals(keyName)) {
                            // #return 为停止播放轮盘动画
                            ClientProtocolGateway.stopSelfAnimation();
                        } else if (keyName.startsWith("#")
                            && info.getExtraAnimationClassifications().containsKey(keyName.substring(1))) {
                            addRootClassify(keyName.substring(1));
                            AnimationRouletteScreen screen = new AnimationRouletteScreen(
                                info.getExtraAnimationButtons(), info.getExtraAnimationClassifications(), model, cap);
                            Minecraft.getInstance().setScreen(screen);
                        } else {
                            ClientProtocolGateway.playSelfAnimation(keyName);
                        }
                    }
                });
                return;
            }
        }
    }
}
