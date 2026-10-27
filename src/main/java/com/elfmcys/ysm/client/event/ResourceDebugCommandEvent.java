package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.client.command.ResourceDebugCommand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ResourceDebugCommandEvent {
    private ResourceDebugCommandEvent() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        ResourceDebugCommand.register(event.getDispatcher());
    }
}
