package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
@SuppressWarnings("resource")
public class MobEffectSyncEvent {
    @SubscribeEvent
    public static void onAdded(MobEffectEvent.Added event) {
        if (!YesSteveModel.isAvailable() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && event.getEffectInstance().getEffect() != null) {
            var effectInstance = event.getEffectInstance();
            player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(cap -> {
                cap.getPropertiesTracker().addEffect(player, effectInstance.getEffect(),
                    effectInstance.getAmplifier() + 1);
            });
        }
    }

    @SubscribeEvent
    public static void onRemoved(MobEffectEvent.Remove event) {
        if (!YesSteveModel.isAvailable() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && event.getEffect() != null) {
            player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(cap -> {
                cap.getPropertiesTracker().removeEffect(player, event.getEffect());
            });
        }
    }

    @SubscribeEvent
    public static void onExpired(MobEffectEvent.Expired event) {
        if (!YesSteveModel.isAvailable() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && event.getEffectInstance() != null
            && event.getEffectInstance().getEffect() != null) {
            player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(cap -> {
                cap.getPropertiesTracker().removeEffect(player, event.getEffectInstance().getEffect());
            });
        }
    }
}
