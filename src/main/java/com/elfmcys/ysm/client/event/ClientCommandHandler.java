package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.command.ClientRootCommand;
import com.elfmcys.ysm.client.command.ResourceDebugCommand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ClientCommandHandler {
    private ClientCommandHandler() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        ResourceDebugCommand.register(event.getDispatcher());
        if (YesSteveModel.isAvailable()) {
            ClientRootCommand.register(event.getDispatcher());
        }
    }
}
