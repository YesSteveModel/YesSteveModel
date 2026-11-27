package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTeleportMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void ysm$acceptLocalTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        PlayerTeleportState.recordLocal(minecraft.player);
    }

    @WrapOperation(method = "handleTeleportEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFIZ)V"))
    private void ysm$acceptRemoteTeleport(Entity entity, double x, double y, double z, float yaw, float pitch,
        int steps, boolean teleport, Operation<Void> original) {
        if (entity instanceof RemotePlayer player)
            PlayerTeleportState.recordRemote(player, x, y, z);
        original.call(entity, x, y, z, yaw, pitch, steps, teleport);
    }
}
