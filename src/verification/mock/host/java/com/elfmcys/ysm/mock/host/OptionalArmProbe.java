package com.elfmcys.ysm.mock.host;

import com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent;
import com.elfmcys.ysm.config.ClientConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Observes arm events emitted by the equipped optional mod gun during normal game draws. */
final class OptionalArmProbe {
    private final Minecraft minecraft;
    private final Map<String, Integer> draws = new LinkedHashMap<>();
    private final Map<String, Object> result = new LinkedHashMap<>();
    private int ticks;
    private int step;

    OptionalArmProbe(Minecraft minecraft) {
        this.minecraft = minecraft;
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onArm(RenderPlayerArmEvent event) {
        var key = event.getArm() + ":" + event.isUseOldHandRender() + ":" + event.isCanceled();
        draws.merge(key, 1, Integer::sum);
    }

    Map<String, ?> poll() {
        if (++ticks < 30)
            return null;
        if (step == 0) {
            result.put("heldItem", net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(minecraft.player.getMainHandItem().getItem()).toString());
            result.put("visible", new LinkedHashMap<>(draws));
            draws.clear();
            ticks = 0;
            step = 1;
            ClientConfig.DISABLE_SELF_HANDS.set(true);
            return null;
        }
        result.put("handsSuppressed", new LinkedHashMap<>(draws));
        ClientConfig.DISABLE_SELF_HANDS.set(false);
        MinecraftForge.EVENT_BUS.unregister(this);
        return result;
    }
}
