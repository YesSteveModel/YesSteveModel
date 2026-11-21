package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.expression.host.PlayerQueryAccess;
import com.elfmcys.ysm.expression.host.PlayerQueryProjection;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Player.class)
public abstract class PlayerQueryProjectionMixin implements PlayerQueryAccess {
    @Unique
    private volatile PlayerQueryProjection ysm$acceptedQueryProjection = PlayerQueryProjection.MISSING;

    public final PlayerQueryProjection ysm$playerQueryProjection() {
        return ysm$acceptedQueryProjection;
    }

    public final void ysm$playerQueryProjection(PlayerQueryProjection projection) {
        ysm$acceptedQueryProjection = projection;
    }
}
