package com.elfmcys.ysm.client.compat.top;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleModelInfoCapabilityProvider;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.MetadataProto;
import com.elfmcys.ysm.util.ModelIdUtil;
import java.util.function.Function;
import mcjty.theoneprobe.api.ElementAlignment;
import mcjty.theoneprobe.api.IProbeHitEntityData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoEntityProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

public final class TopPlugin implements Function<ITheOneProbe, Void> {
    @Nullable
    @Override
    public Void apply(@Nullable ITheOneProbe probe) {
        if (probe != null) {
            probe.registerEntityProvider(new ModelInfoProvider());
        }
        return null;
    }

    private static class ModelInfoProvider implements IProbeInfoEntityProvider {
        @SuppressWarnings("removal")
        private static final String ID = (new ResourceLocation(YesSteveModel.MOD_ID, "model_info")).toString();

        @Override
        public void addProbeEntityInfo(ProbeMode probeMode, IProbeInfo probeInfo, Player sourcePlayer, Level world,
            Entity entity, IProbeHitEntityData iProbeHitEntityData) {
            if (entity instanceof ServerPlayer player) {
                player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(cap -> {
                    if (cap.isMandatory() || NetworkHandler.isPlayerChannelPresent(player)) {
                        var hash = cap.getModelId();
                        if (hash == null) {
                            return;
                        }
                        ServerModelService.instance().catalog().flatMap(snapshot -> snapshot.find(hash))
                            .ifPresent(m -> {
                                var metadata =
                                    m.view().getManifest().info().metadata().map(MetadataProto::name).orElse("");
                                probeInfo
                                    .horizontal(probeInfo.defaultLayoutStyle().alignment(ElementAlignment.ALIGN_CENTER))
                                    .text(Component.translatable("top.yes_steve_model.model_info.id")
                                        .append(StringUtils.defaultIfBlank(metadata,
                                            ModelIdUtil.getFileNameFromPath(m.location().path().value()))));
                            });
                    }
                });
            } else {
                entity.getCapability(VehicleModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                    if (cap.isInitialized()) {
                        var hash = cap.getOwnerModelHash();
                        if (hash == null) {
                            return;
                        }
                        ServerModelService.instance().catalog().flatMap(snapshot -> snapshot.find(hash))
                            .filter(m -> hasVehicle(m.view().getManifest(), entity)).ifPresent(m -> {
                                var metadata =
                                    m.view().getManifest().info().metadata().map(MetadataProto::name).orElse("");
                                probeInfo
                                    .horizontal(probeInfo.defaultLayoutStyle().alignment(ElementAlignment.ALIGN_CENTER))
                                    .text(Component.translatable("top.yes_steve_model.model_info.id")
                                        .append(StringUtils.defaultIfBlank(metadata,
                                            ModelIdUtil.getFileNameFromPath(m.location().path().value()))));
                            });
                    }
                });
            }
        }

        @Override
        public String getID() {
            return ID;
        }

        private static boolean hasVehicle(ManifestProto manifest, Entity entity) {
            var id = entity.getType().builtInRegistryHolder().key().location();
            for (var replacement : manifest.renderTargets()) {
                if (replacement.kind() != RenderTargetKind.VEHICLE) {
                    continue;
                }
                if (replacement.match().isEmpty()) {
                    continue;
                }
                for (var match : replacement.match()) {
                    if (id.toString().equals(match)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
