package com.elfmcys.ysm.event;

import com.elfmcys.ysm.client.compat.top.TopPlugin;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public final class InterModMsg {
    @SubscribeEvent
    @SuppressWarnings("Convert2MethodRef")
    public static void onEnqueue(final InterModEnqueueEvent event) {
        InterModComms.sendTo("theoneprobe", "getTheOneProbe", () -> new TopPlugin());
    }
}
