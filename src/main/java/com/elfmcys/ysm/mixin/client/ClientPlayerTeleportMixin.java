package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AbstractClientPlayer.class)
public abstract class ClientPlayerTeleportMixin implements PlayerTeleportState.Access {
    @Unique
    private volatile PlayerTeleportState ysm$teleportState = PlayerTeleportState.NONE;

    public final PlayerTeleportState ysm$teleportState() {
        return ysm$teleportState;
    }

    public final void ysm$teleportState(PlayerTeleportState state) {
        ysm$teleportState = state;
    }
}
