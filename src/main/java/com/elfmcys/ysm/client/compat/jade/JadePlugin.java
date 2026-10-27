package com.elfmcys.ysm.client.compat.jade;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.util.ModelIdUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin
@SuppressWarnings("unused")
public class JadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration reg) {
        reg.registerEntityComponent(new ModelInfoProvider(), Entity.class);
    }

    private static class ModelInfoProvider implements IEntityComponentProvider {
        @SuppressWarnings("removal")
        private static final ResourceLocation UID = new ResourceLocation(YesSteveModel.MOD_ID, "model_info");

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor entityAccessor, IPluginConfig config) {
            if (entityAccessor.getEntity() instanceof Player player) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    if (cap.isPresentationAllowed() && cap.hasInstalledModel()) {
                        tooltip.add(Component.translatable("top.yes_steve_model.model_info.id")
                            .append(cap.getModelRenderTarget()
                                .getDisplayName(ModelIdUtil.getFileNameFromPath(cap.getInstalledModelId()))));
                    }
                });
            } else {
                entityAccessor.getEntity().getCapability(VehicleAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    if (cap.isPresentationAllowed() && cap.hasInstalledModel()) {
                        tooltip.add(Component.translatable("top.yes_steve_model.model_info.id")
                            .append(cap.getModelRenderTarget()
                                .getDisplayName(ModelIdUtil.getFileNameFromPath(cap.getInstalledModelId()))));
                    }
                });
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }
}
