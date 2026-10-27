package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import net.minecraft.network.Connection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientLoggedEvent {
    private static Connection loggedIn;

    @SubscribeEvent
    public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        var connection = event.getConnection();
        if (loggedIn == connection) {
            return;
        }
        if (!YesSteveModel.isAvailable()) {
            loggedIn = connection;
            YesSteveModel.sendUnavailableMessage();
            return;
        }
        ClientSessionRuntime.beginConnection(connection);
        loggedIn = connection;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        var connection = event.getConnection();
        if (loggedIn != connection) {
            return;
        }
        loggedIn = null;
        RegisterEntityRenderersEvent.releaseFirstPersonArm();
        if (YesSteveModel.isAvailable()) {
            ClientSessionRuntime.disconnect(connection);
        }
    }
}
