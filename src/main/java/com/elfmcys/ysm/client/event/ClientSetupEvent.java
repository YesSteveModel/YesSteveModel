package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.ClientStartup;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.elfmcys.ysm.client.gui.overlay.ExtraPlayerScreen;
import com.elfmcys.ysm.client.gui.overlay.LoadingStateScreen;
import com.elfmcys.ysm.client.input.AnimationRouletteKey;
import com.elfmcys.ysm.client.input.DebugAnimationKey;
import com.elfmcys.ysm.client.input.ExtraAnimationKey;
import com.elfmcys.ysm.client.input.ExtraPlayerConfigKey;
import com.elfmcys.ysm.client.input.PlayerModelScreenKey;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import static net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.DEBUG_TEXT;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetupEvent {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        event.enqueueWork(() -> ClientStartup.start(ModelRuntime.system()));
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PlayerModelScreenKey.PLAYER_MODEL_KEY);

        if (!YesSteveModel.isAvailable()) {
            return;
        }

        event.register(AnimationRouletteKey.ANIMATION_ROULETTE_KEY);
        event.register(AnimationRouletteKey.LOCK_ROULETTE_KEY);
        event.register(DebugAnimationKey.DEBUG_ANIMATION_KEY);
        event.register(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
        ExtraAnimationKey.registerKeyBinding(event);
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        event.registerAbove(DEBUG_TEXT.id(), "ysm_debug_info", DebugAnimationScreen.getGuiOverlay());
        event.registerAbove(DEBUG_TEXT.id(), "ysm_extra_player", new ExtraPlayerScreen());
        event.registerAbove(DEBUG_TEXT.id(), "ysm_loading_state", new LoadingStateScreen());
    }

}
