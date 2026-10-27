package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.event.CapabilityEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;positionRider(Lnet/minecraft/world/entity/Entity;)V",
            shift = At.Shift.AFTER))
    private void afterPositionRider(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        ServerPlayer serverPlayer = (ServerPlayer)(Object)this;
        if (vehicle.getFirstPassenger() == serverPlayer) {
            CapabilityEvent.onVehicleSetModel(vehicle, serverPlayer);
        }
    }
}
