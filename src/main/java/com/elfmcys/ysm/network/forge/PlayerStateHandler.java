package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.*;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.NetworkDiagnostics;
import com.elfmcys.ysm.network.protocol.*;
import com.elfmcys.ysm.proto.network.*;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;

/** State admission and publication run in the original player's server owner window. */
public final class PlayerStateHandler {
    private PlayerStateHandler() {}

    public static void handleSelectionChange(SelectionChangeProto message, Supplier<NetworkEvent.Context> source) {
        var context = source.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            ClientPlayerStateHandler.selection(message, context);
            return;
        }
        var sender = context.getSender();
        if (sender == null || !message.hasSelection()) {
            NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message,
                "missing-sender-or-selection");
            return;
        }
        enqueue(context, sender, message, session -> {
            final CommittedSelection next;
            try {
                next = SelectionCodec.read(message.selectionUnsafe(), session);
            } catch (IllegalArgumentException invalid) {
                NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "invalid-selection");
                return;
            }
            if (message.selectionUnsafe().entityId() != sender.getId() || next.intentId() <= 0
                || !session.observeSelectionRequest(next.intentId())) {
                NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "subject-or-intent");
                return;
            }
            var capability = sender.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null);
            if (capability == null) {
                NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "missing-capability");
                return;
            }
            var result = commitSelection(session, capability, next, ServerConfig.CAN_SWITCH_MODEL.get());
            NetworkDiagnostics.message(
                result == ServerModelSession.SelectionResult.ACCEPTED ? "selection-committed" : "selection-rejected",
                MessageDirection.CLIENT_TO_SERVER, message, result.name());
            if (result == ServerModelSession.SelectionResult.ACCEPTED)
                publishSelection(sender);
            else
                sendDirective(sender, PublishedModelId.INTRINSIC_DEFAULT, "");
        });
    }

    static ServerModelSession.SelectionResult commitSelection(ServerModelSession session,
        ModelInfoCapability capability, CommittedSelection next, boolean canSwitch) {
        var decision = canSwitch || next.model().isIntrinsicDefault() ? session.validateSelection(next.selection())
            : ServerModelSession.SelectionResult.INVALID_REQUEST;
        if (decision == ServerModelSession.SelectionResult.ACCEPTED)
            capability.commitSelection(next);
        return decision;
    }

    public static void handleSelectionState(SelectionStateSyncProto message, Supplier<NetworkEvent.Context> source) {
        var context = source.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            ClientPlayerStateHandler.selectionState(message, context);
            return;
        }
        if (!PlayerStateValidator.validSyncShape(message)) {
            NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "invalid-sync");
            return;
        }
        var sender = context.getSender();
        if (sender == null || message.entityId() != sender.getId() || message.intentId() <= 0) {
            NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "subject-or-intent");
            return;
        }
        enqueue(context, sender, message, session -> {
            var capability = sender.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null);
            var selection = capability == null ? null : capability.selection();
            if (selection == null || !selection.accepts(session, message.intentId())) {
                NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message,
                    "selection-or-session");
                return;
            }
            if (message.hasState()) {
                if (message.state().hasRoaming() && !session.syncRoaming()) {
                    NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message,
                        "roaming-disabled");
                    return;
                }
                try {
                    SelectionCodec.apply(selection, message.state());
                } catch (IllegalArgumentException invalid) {
                    NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "invalid-state");
                    return;
                }
            }
            NetworkDiagnostics.message("accepted", MessageDirection.CLIENT_TO_SERVER, message, "current-selection");
            broadcast(sender, message);
        });
    }

    public static void handleEntityState(EntityStateSyncProto message, Supplier<NetworkEvent.Context> source) {
        var context = source.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT)
            ClientPlayerStateHandler.entityState(message, context);
        else
            NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "server-authority");
        // Every field currently belongs to the game server; splitting packets grants no new C2S authority.
    }

    public static void handleTrack(EntityTrackProto message, Supplier<NetworkEvent.Context> source) {
        ClientPlayerStateHandler.track(message, source.get());
    }

    public static void handleFlags(PlayerFlagsProto message, Supplier<NetworkEvent.Context> source) {
        ClientPlayerStateHandler.flags(message, source.get());
    }

    public static void handleServerFlags(ServerFlagsProto message, Supplier<NetworkEvent.Context> source) {
        ClientPlayerStateHandler.serverFlags(message, source.get());
    }

    public static void handleDirective(SelectionDirectiveProto message, Supplier<NetworkEvent.Context> source) {
        ClientPlayerStateHandler.directive(message, source.get());
    }

    public static void handleInvalidated(SelectionInvalidatedProto message, Supplier<NetworkEvent.Context> source) {
        ClientPlayerStateHandler.invalidated(message, source.get());
    }

    private static void enqueue(NetworkEvent.Context context, ServerPlayer sender, ProtoMessage<?> message,
        java.util.function.Consumer<ServerModelSession> action) {
        var connection = context.getNetworkManager();
        var service = ServerModelService.current().orElse(null);
        var session = service == null ? null : service.session(sender, connection).orElse(null);
        if (session == null) {
            NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "missing-session");
            return;
        }
        context.enqueueWork(() -> {
            if (!service.runIfCurrent(sender, connection, session, () -> {
                if (session.active())
                    action.accept(session);
                else
                    NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message,
                        "inactive-session");
            }))
                NetworkDiagnostics.message("dropped", MessageDirection.CLIENT_TO_SERVER, message, "retired-connection");
        });
    }

    public static EntityStateSyncProto.Builder newDelta(int entityId) {
        return EntityStateSyncProto.newBuilder().setEntityId(entityId);
    }

    public static void broadcast(ServerPlayer subject, ProtoMessage<?> message) {
        ServerModelService.current().ifPresent(service -> service.sendTrackedState(subject, message));
    }

    public static void send(ProtoMessage<?> message, Player observer) {
        if (!(observer instanceof ServerPlayer player))
            return;
        ServerModelService.current().ifPresent(service -> service.sendState(player, message));
    }

    public static boolean publishSelection(ServerPlayer player) {
        var capability = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null);
        var catalog = ServerModelService.current().flatMap(ServerModelService::catalog).orElse(null);
        if (capability == null || catalog == null || ModelSelectionService.resolve(capability, catalog).isEmpty())
            return false;
        capability.consumeDirty();
        broadcast(player, SelectionChangeProto.newBuilder()
            .setSelection(SelectionCodec.write(player.getId(), capability.selection())).build());
        return true;
    }

    public static EntityStateSyncProto entityInitial(ServerPlayer player) {
        return EntityStateSyncProto.newBuilder().setEntityId(player.getId())
            .setState(java.util.Objects
                .requireNonNull(player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null))
                .getPropertiesTracker().snapshot(player))
            .build();
    }

    public static void sendEntityInitial(ServerPlayer player) {
        send(entityInitial(player), player);
    }

    public static PlayerFlagsProto flags(ServerPlayer player) {
        var capability = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).orElse(null);
        return PlayerFlagsProto.newBuilder().setEntityId(player.getId())
            .setState(
                PlayerFlagsStateProto.newBuilder().setDisabled(capability != null && capability.isDisabled()).build())
            .build();
    }

    public static ServerFlagsProto serverFlags() {
        return ServerFlagsProto.newBuilder().setCanSwitchModel(ServerConfig.CAN_SWITCH_MODEL.get()).build();
    }

    public static void sendFlags(ServerPlayer subject, Player observer) {
        send(flags(subject), observer);
    }

    public static void broadcastFlags(ServerPlayer subject) {
        broadcast(subject, flags(subject));
    }

    public static SelectionDirectiveProto directive(PublishedModelId model, String texture) {
        return SelectionDirectiveProto.newBuilder().setModel(ModelReferenceCodec.create(model)).setTextureId(texture)
            .build();
    }

    public static void sendDirective(ServerPlayer player, PublishedModelId model, String texture) {
        send(directive(model, texture), player);
    }
}
