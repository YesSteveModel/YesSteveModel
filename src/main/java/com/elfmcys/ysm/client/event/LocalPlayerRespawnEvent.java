package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class LocalPlayerRespawnEvent {
    @SubscribeEvent
    public static void onFire(ClientPlayerNetworkEvent.Clone event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        // 仅客户端运行时，执行后续的 copyFrom 会出现 null 值问题
        if (!NetworkHandler.isRemoteChannelPresent()) {
            return;
        }

        event.getOldPlayer().reviveCaps();
        event.getOldPlayer().getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .ifPresent(oldCap -> event.getNewPlayer().getCapability(PlayerAnimatableCapabilityProvider.CAP)
                .ifPresent(newCap -> newCap.moveFrom(oldCap)));
        event.getOldPlayer().invalidateCaps();
        ClientProtocolGateway.localPlayerCloned();
    }
}
