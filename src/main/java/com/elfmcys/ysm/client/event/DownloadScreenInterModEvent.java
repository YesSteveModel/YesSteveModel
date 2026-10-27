package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.DownloadScreen;
import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, modid = YesSteveModel.MOD_ID, value = Dist.CLIENT)
public class DownloadScreenInterModEvent {
    private static final String DOWNLOAD_SCREEN_METHOD = "DownloadScreen";
    private static @Nullable Screen DOWNLOAD_SCREEN;

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInterModProcess(InterModProcessEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        InterModComms.getMessages(YesSteveModel.MOD_ID).findFirst().ifPresent(message -> {
            String method = message.method();
            if (DOWNLOAD_SCREEN_METHOD.equals(method) && message.messageSupplier().get() instanceof Screen screen) {
                DOWNLOAD_SCREEN = screen;
            }
        });
    }

    public static void openDownloadScreen(PlayerModelScreen modelScreen) {
        modelScreen.showScreen(Objects.requireNonNullElseGet(DOWNLOAD_SCREEN, () -> new DownloadScreen(modelScreen)));
    }
}
