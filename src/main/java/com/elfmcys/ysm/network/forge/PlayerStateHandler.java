package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapability;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoSyncAssembler;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.util.StringPool;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.ModelReferenceCodec;
import com.elfmcys.ysm.network.protocol.PlayerStateSection;
import com.elfmcys.ysm.network.protocol.PlayerStateValidator;
import com.elfmcys.ysm.network.protocol.ProtocolLimits;
import com.elfmcys.ysm.proto.network.AnimationStateProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

public final class PlayerStateHandler {
    private static final int MAX_ANIMATION_ID_BYTES = 256;

    private PlayerStateHandler() {}

    public static PlayerStateUpdateProto.Builder newFull(ServerPlayer player) {
        return PlayerStateUpdateProto.newBuilder().setSubject(serverEntityRef(player.getId()))
            .setMode(StateWriteMode.FULL);
    }

    public static PlayerStateUpdateProto.Builder newDelta(int entityId) {
        return PlayerStateUpdateProto.newBuilder().setSubject(serverEntityRef(entityId)).setMode(StateWriteMode.DELTA);
    }

    public static void broadcast(ServerPlayer player, PlayerStateUpdateProto update) {
        try {
            NetworkHandler.broadcastToVisiblePlayersAndSelf(update, player);
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to broadcast a player-state update for {}", player.getScoreboardName(),
                failure);
        }
    }

    public static void sendToObserver(PlayerStateUpdateProto update, Player observer) {
        try {
            NetworkHandler.sendToClientPlayer(update, observer);
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to send a player-state update to {}", observer.getScoreboardName(),
                failure);
        }
    }

    public static void handleReport(PlayerStateReportProto report, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            context.setPacketHandled(true);
            return;
        }
        var connection = context.getNetworkManager();
        var service = ServerModelService.current().orElse(null);
        var owner = service == null ? null : service.session(sender, connection).orElse(null);
        if (owner == null) {
            context.setPacketHandled(true);
            return;
        }
        if (!PlayerStateValidator.validReport(report) || !validServerReportHeader(report, sender)) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> service.runIfCurrent(sender, connection, owner, () -> {
            try {
                if (!owner.acceptsPlayerStateReport(report)) {
                    return;
                }
                sender.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP)
                    .ifPresent(capability -> applyReport(sender, capability, owner, report));
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.warn("Failed to process a player-state report from {}", sender.getScoreboardName(),
                    failure);
            }
        }));
        context.setPacketHandled(true);
    }

    public static void handleUpdate(PlayerStateUpdateProto update, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (!PlayerStateValidator.validUpdate(update)) {
            context.setPacketHandled(true);
            return;
        }
        var connection = context.getNetworkManager();
        context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> applyClientUpdate(update)));
        context.setPacketHandled(true);
    }

    public static boolean sendAuthoritativeFull(ServerPlayer player, boolean broadcast) {
        var capability = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).resolve().orElse(null);
        if (capability == null) {
            return false;
        }
        if (broadcast) {
            capability.getPropertiesTracker().tick(player, false, ServerConfig.LOW_BANDWIDTH_USAGE.get());
            capability.consumeDirty();
        }
        var update = buildAuthoritativeFull(player, capability).orElse(null);
        if (update == null) {
            return false;
        }
        if (broadcast) {
            broadcast(player, update);
        } else {
            sendToObserver(update, player);
        }
        return true;
    }

    private static boolean validServerReportHeader(PlayerStateReportProto report, ServerPlayer sender) {
        return !report.subject().hasPlayerId() && report.subject().entityId() == sender.getId();
    }

    private static void applyReport(ServerPlayer sender, ModelInfoCapability capability, ServerModelSession owner,
        PlayerStateReportProto report) {
        var full = report.mode() == StateWriteMode.FULL;
        NetworkHandler.traceNetwork("player-state-report-accepted", "playerId=" + sender.getId(),
            "mode=" + report.mode(), "hasRoaming=" + report.hasRoaming(),
            "roamingVariables=" + (report.hasRoaming() ? report.roamingUnsafe().variables().size() : 0));
        if (full) {
            var snapshot = ServerModelService.current().flatMap(ServerModelService::catalog).orElse(null);
            if (snapshot == null || ModelSelectionService.resolve(capability, snapshot).isEmpty()) {
                return;
            }
        }
        if (report.hasAnimation() && report.animationUnsafe().hasAnimationId()
            && !isAnimationAllowed(capability, report.animationUnsafe().animationId())) {
            return;
        }
        if (report.hasRoaming() && !validRoaming(capability, report.roamingUnsafe())) {
            return;
        }
        var sections = owner.playerStateReportPolicy().requestedSections();
        if (full && sections.contains(PlayerStateSection.ROAMING) && !report.hasRoaming()
            && capability.getModelId() == null) {
            return;
        }

        AnimationStateProto animation = null;
        if (sections.contains(PlayerStateSection.ANIMATION)) {
            if (report.hasAnimation()) {
                animation = report.animationUnsafe().hasStopped()
                    ? AnimationStateProto.newBuilder().setStopped(true).build()
                    : AnimationStateProto.newBuilder().setAnimationId(report.animationUnsafe().animationId()).build();
            } else if (full) {
                animation = AnimationStateProto.newBuilder().setStopped(true).build();
            }
        }

        Object2FloatOpenHashMap<String> roamingVariables = null;
        var roamingKey = 0;
        if (sections.contains(PlayerStateSection.ROAMING) && (report.hasRoaming() || full)) {
            roamingKey =
                report.hasRoaming() ? report.roamingUnsafe().modelKey() : capability.getModelId().roamingHash();
            roamingVariables =
                new Object2FloatOpenHashMap<>(report.hasRoaming() ? report.roamingUnsafe().variables().size() : 0);
        }
        if (report.hasRoaming()) {
            if (roamingVariables == null) {
                return;
            }
            for (var variable : report.roamingUnsafe().variables()) {
                roamingVariables.put(variable.name(), variable.value_());
            }
        }

        if (animation != null) {
            capability.applyClientAnimation(animation.hasStopped() ? "" : animation.animationId());
        }
        if (roamingVariables != null) {
            capability.applyClientRoaming(roamingKey, roamingVariables, full);
        }

        final PlayerStateUpdateProto update;
        if (full) {
            // Every fact taken from this report is applied above, and the real FULL baseline is
            // accepted here at the same frontier. The observation advance, the authoritative update
            // construction and the broadcast below are best-effort and cannot roll these back.
            owner.commitPlayerStateReport(true);
            capability.getPropertiesTracker().tick(sender, false, !sections.contains(PlayerStateSection.ROAMING));
            capability.consumeDirty();
            update = buildAuthoritativeFull(sender, capability).orElse(null);
        } else {
            var updateBuilder = newDelta(sender.getId());
            if (animation != null) {
                updateBuilder.setAnimation(animation);
            }
            if (roamingVariables != null) {
                var roaming = RoamingStateProto.newBuilder().setModelKey(roamingKey);
                roamingVariables.object2FloatEntrySet().fastForEach(entry -> roaming.addVariables(
                    MolangVariableProto.newBuilder().setName(entry.getKey()).setValue(entry.getFloatValue()).build()));
                updateBuilder.setRoaming(roaming.build());
            }
            update = updateBuilder.build();
            owner.commitPlayerStateReport(false);
        }
        if (update != null) {
            broadcast(sender, update);
        }
    }

    private static boolean isAnimationAllowed(ModelInfoCapability capability, String animationId) {
        if (animationId.isBlank() || animationId.getBytes(StandardCharsets.UTF_8).length > MAX_ANIMATION_ID_BYTES
            || capability.getModelId() == null) {
            return false;
        }
        return ServerModelService.current().flatMap(ServerModelService::catalog)
            .flatMap(snapshot -> snapshot.find(capability.getModelId())).map(model -> {
                var settings = model.view().getManifest().info().settings();
                if (!settings.extraAnimation().isEmpty()) {
                    for (var animation : settings.extraAnimation()) {
                        if (animationId.equals(animation.key()))
                            return true;
                    }
                }
                if (!settings.extraAnimationClassify().isEmpty()) {
                    for (var classification : settings.extraAnimationClassify()) {
                        if (classification.extraAnimation().isEmpty()) {
                            continue;
                        }
                        for (var animation : classification.extraAnimation()) {
                            if (animationId.equals(animation.key()))
                                return true;
                        }
                    }
                }
                return false;
            }).orElse(false);
    }

    private static boolean validRoaming(ModelInfoCapability capability, RoamingStateProto roaming) {
        if (capability.getModelId() == null || capability.getModelId().roamingHash() != roaming.modelKey()
            || roaming.variables().size() > ProtocolLimits.MAX_ROAMING_VARIABLES) {
            return false;
        }
        var names = new HashSet<String>();
        for (var variable : roaming.variables()) {
            if (!Float.isFinite(variable.value_()) || variable.name().isBlank()
                || variable.name()
                    .getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES
                || !names.add(variable.name())) {
                return false;
            }
        }
        return true;
    }

    private static void applyClientUpdate(PlayerStateUpdateProto update) {
        var entityId = update.subject().entityId();
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(entityId) instanceof Player player)) {
            return;
        }
        var builtinDefaultHash = update.hasModel() && ModelReferenceCodec.read(update.modelUnsafe().model()) == null
            ? ClientModelService.instance().defaultRenderTarget().modelHash() : null;
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .ifPresent(capability -> applyClientUpdate(player, capability, update, builtinDefaultHash));
    }

    private static void applyClientUpdate(Player player,
        com.elfmcys.ysm.capability.PlayerAnimatableCapability capability, PlayerStateUpdateProto update,
        @Nullable Hash256 builtinDefaultHash) {
        var full = update.mode() == StateWriteMode.FULL;
        var authoritativeModelHash = update.hasModel() ? ModelReferenceCodec.read(update.modelUnsafe().model()) : null;
        var appliedModelHash =
            update.hasModel() ? appliedPlayerModelHash(authoritativeModelHash, builtinDefaultHash) : null;
        if (full) {
            capability.getStateTracker().reset();
        }
        if (update.hasModel()) {
            var model = update.modelUnsafe();
            capability.updateModelAndTexture(appliedModelHash, model.textureId());
            capability.setDisabled(model.disabled());
        }
        capability.getStateTracker().updateProtocolState(update.hasGameplay() ? update.gameplayUnsafe() : null,
            update.hasEffects() ? update.effectsUnsafe() : null, full);
        if (update.hasAnimation()) {
            if (update.animationUnsafe().hasStopped()) {
                capability.stopExtraAnimation();
            } else {
                capability.playExtraAnimation(update.animationUnsafe().animationId());
            }
        } else if (full) {
            capability.stopExtraAnimation();
        }
        var roaming = roamingApplication(update);
        if (roaming != null) {
            if (roaming.modelKey() == null) {
                capability.clearRoamingVars();
            } else if (roaming.full()) {
                capability.resetRoamingVars(roaming.modelKey(), roaming.values());
            } else {
                capability.updateRemoteRoamingVars(roaming.modelKey(), roaming.values());
            }
        }
        if (full && player == Minecraft.getInstance().player) {
            ClientProtocolGateway.acceptAuthoritativeFull(authoritativeModelHash,
                selfFullRoamingKey(update, appliedModelHash));
        }
    }

    static Hash256 appliedPlayerModelHash(@Nullable Hash256 authoritativeModelHash, Hash256 builtinDefaultHash) {
        return authoritativeModelHash == null ? builtinDefaultHash : authoritativeModelHash;
    }

    /**
     * The roaming namespace an accepted self FULL binds. A FULL expresses its complete scope, so an absent roaming
     * section is the empty state of the namespace named by that same model, not a reason to withhold the report
     * authority. The wire compresses the builtin default model to a flag, and such a FULL leaves the namespace to the
     * applied model.
     */
    static Integer selfFullRoamingKey(PlayerStateUpdateProto update, Hash256 modelHash) {
        if (update.hasRoaming()) {
            return update.roamingUnsafe().modelKey();
        }
        return modelHash == null ? null : modelHash.roamingHash();
    }

    static RoamingApplication roamingApplication(PlayerStateUpdateProto update) {
        var full = update.mode() == StateWriteMode.FULL;
        if (!update.hasRoaming()) {
            if (!full) {
                return null;
            }
            return new RoamingApplication(true, null, new Int2FloatOpenHashMap());
        }
        var values = new Int2FloatOpenHashMap(update.roamingUnsafe().variables().size());
        for (var variable : update.roamingUnsafe().variables()) {
            values.put(StringPool.computeIfAbsent(variable.name()), variable.value_());
        }
        return new RoamingApplication(full, update.roamingUnsafe().modelKey(), values);
    }

    private static Optional<PlayerStateUpdateProto> buildAuthoritativeFull(ServerPlayer player,
        ModelInfoCapability capability) {
        return ServerModelService.current().flatMap(ServerModelService::catalog)
            .flatMap(snapshot -> ModelInfoSyncAssembler.build(player, capability, snapshot));
    }

    private static EntityRefProto serverEntityRef(int entityId) {
        return EntityRefProto.newBuilder().setEntityId(entityId).build();
    }

    record RoamingApplication(boolean full, Integer modelKey, Int2FloatOpenHashMap values) {
    }
}
