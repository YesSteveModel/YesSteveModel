package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.AuthModelsCapability;
import com.elfmcys.ysm.capability.ModelInfoCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.ProjectileAnimatableCapability;
import com.elfmcys.ysm.capability.ProjectileModelInfoCapability;
import com.elfmcys.ysm.capability.StarModelsCapability;
import com.elfmcys.ysm.capability.VehicleAnimatableCapability;
import com.elfmcys.ysm.capability.VehicleModelInfoCapability;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CommonEvent {
    @SubscribeEvent
    public static void onSetupEvent(FMLCommonSetupEvent event) {
        if (!YesSteveModel.isAvailable()) {
            event.enqueueWork(() -> ModLoader.get().addWarning(YesSteveModel.getUnavailableWarning()));
            return;
        }
        event.enqueueWork(() -> {
            ModelRuntime.initialize();
            NetworkHandler.init();
            if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
                ModelRuntime.system().catalog().activate();
            }
        });
    }

    @SubscribeEvent
    public static void registerCapability(RegisterCapabilitiesEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        event.register(ModelInfoCapability.class);
        event.register(ProjectileModelInfoCapability.class);
        event.register(VehicleModelInfoCapability.class);
        event.register(AuthModelsCapability.class);
        event.register(StarModelsCapability.class);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            event.register(PlayerAnimatableCapability.class);
            event.register(ProjectileAnimatableCapability.class);
            event.register(VehicleAnimatableCapability.class);
        }
    }

}
