package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.*;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.network.protocol.*;
import com.elfmcys.ysm.network.NetworkDiagnostics;
import com.elfmcys.ysm.proto.network.*;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Consumer;

/** Consumes validated state in the exact business session and current entity owner. */
@net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
final class ClientPlayerStateHandler {
    private ClientPlayerStateHandler() {}

    private static void enqueue(NetworkEvent.Context context, us.hebi.quickbuf.ProtoMessage<?> message,
        Runnable action) {
        var connection = context.getNetworkManager();
        var business = ClientSessionRuntime.businessSession(connection).orElse(null);
        if (business == null) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "missing-session");
            return;
        }
        context.enqueueWork(() -> {
            if (!ClientSessionRuntime.runIfBusinessSessionCurrent(connection, business, action))
                NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "retired-session");
        });
    }

    private static void subject(int id, boolean includeLocal, us.hebi.quickbuf.ProtoMessage<?> message,
        Consumer<PlayerAnimatableCapability> action) {
        var level = Minecraft.getInstance().level;
        var entity = level == null ? null : level.getEntity(id);
        if (!(entity instanceof Player player)) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "missing-subject");
            return;
        }
        if (!includeLocal && player instanceof LocalPlayer) {
            NetworkDiagnostics.message("self-ignored", MessageDirection.SERVER_TO_CLIENT, message, "local-player");
            return;
        }
        var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (capability == null) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "missing-capability");
            return;
        }
        action.accept(capability);
    }

    static void selection(SelectionChangeProto message, NetworkEvent.Context context) {
        if (!message.hasSelection()) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "missing-selection");
            return;
        }
        final CommittedSelection selected;
        try {
            selected = SelectionCodec.read(message.selectionUnsafe(), null);
        } catch (IllegalArgumentException invalid) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-selection");
            return;
        }
        enqueue(context, message, () -> subject(message.selectionUnsafe().entityId(), false, message, cap -> {
            applySelection(cap, selected);
            NetworkDiagnostics.message("applied", MessageDirection.SERVER_TO_CLIENT, message, "selection");
        }));
    }

    private static void applySelection(PlayerAnimatableCapability cap, CommittedSelection selected) {
        cap.waitForAsyncUpdate();
        var previous = cap.remoteSelection();
        var operation =
            previous != null && previous.intentId() == selected.intentId() && previous.model().equals(selected.model())
                && java.util.Arrays.equals(previous.dictionary().names(), selected.dictionary().names())
                    ? cap.remoteSelectionOperation() : new Object();
        var hash = selected.model() instanceof PublishedModelId.Model model ? model.hash() : null;
        cap.selectModel(hash, selected.texture(), operation);
        cap.acceptRemoteSelection(selected, operation, ClientSessionRuntime.remoteSyncBudget());
        cap.applyRemoteAnimation(selected.animation());
    }

    static void selectionState(SelectionStateSyncProto message, NetworkEvent.Context context) {
        if (!PlayerStateValidator.validSyncShape(message)) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-sync");
            return;
        }
        enqueue(context, message, () -> subject(message.entityId(), false, message, cap -> {
            var selected = cap.remoteSelection();
            if (selected == null || selected.intentId() != message.intentId()) {
                NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message,
                    "selection-or-intent");
                return;
            }
            if (message.hasState()) {
                var delta = message.state();
                final RoamingValues values;
                try {
                    values =
                        delta.hasRoaming() ? SelectionCodec.read(delta.roamingUnsafe(), selected.dictionary()) : null;
                } catch (IllegalArgumentException invalid) {
                    NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-state");
                    return;
                }
                if (values != null)
                    cap.updateRemoteRoamingVars(values);
                if (delta.hasTextureId()) {
                    selected.texture(delta.textureIdUnsafe());
                    var hash = selected.model() instanceof PublishedModelId.Model model ? model.hash() : null;
                    cap.selectModel(hash, selected.texture(), cap.remoteSelectionOperation());
                }
                if (delta.hasAnimation()) {
                    selected
                        .animation(delta.animationUnsafe().hasStopped() ? "" : delta.animationUnsafe().animationId());
                    cap.applyRemoteAnimation(selected.animation());
                }
                NetworkDiagnostics.message("applied", MessageDirection.SERVER_TO_CLIENT, message, "selection-state");
            } else {
                cap.acceptRemoteSync(EventArguments.sync(new FloatArrayList(message.sync().arguments())));
                NetworkDiagnostics.message("event-accepted", MessageDirection.SERVER_TO_CLIENT, message,
                    "current-selection");
            }
        }));
    }

    static void entityState(EntityStateSyncProto message, NetworkEvent.Context context) {
        if (message.entityId() < 0 || !message.hasState()
            || !PlayerStateValidator.validEntityState(message.stateUnsafe(), false)) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-entity-state");
            return;
        }
        enqueue(context, message, () -> subject(message.entityId(), true, message, cap -> {
            applyEntity(cap, message.stateUnsafe());
            NetworkDiagnostics.message("applied", MessageDirection.SERVER_TO_CLIENT, message, "entity-state");
        }));
    }

    private static void applyEntity(PlayerAnimatableCapability cap, EntityStateProto state) {
        cap.waitForAsyncUpdate();
        cap.getStateTracker().updateProtocolState(state.hasGameplay() ? state.gameplayUnsafe() : null,
            state.hasEffects() ? state.effectsUnsafe() : null, true);
    }

    static void track(EntityTrackProto message, NetworkEvent.Context context) {
        if (!message.hasSelection() || !message.hasState() || !message.hasFlags()
            || !PlayerStateValidator.validEntityState(message.stateUnsafe(), true)) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-track");
            return;
        }
        final CommittedSelection selected;
        try {
            selected = SelectionCodec.read(message.selectionUnsafe(), null);
        } catch (IllegalArgumentException invalid) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-selection");
            return;
        }
        enqueue(context, message, () -> subject(message.selectionUnsafe().entityId(), false, message, cap -> {
            applySelection(cap, selected);
            applyEntity(cap, message.stateUnsafe());
            cap.setDisabled(message.flagsUnsafe().disabled());
            NetworkDiagnostics.message("applied", MessageDirection.SERVER_TO_CLIENT, message, "track");
        }));
    }

    static void flags(PlayerFlagsProto message, NetworkEvent.Context context) {
        if (!PlayerStateValidator.validFlags(message)) {
            NetworkDiagnostics.message("dropped", MessageDirection.SERVER_TO_CLIENT, message, "invalid-flags");
            return;
        }
        enqueue(context, message, () -> subject(message.entityId(), true, message, cap -> {
            cap.setDisabled(message.stateUnsafe().disabled());
            NetworkDiagnostics.message("applied", MessageDirection.SERVER_TO_CLIENT, message, "flags");
        }));
    }

    static void serverFlags(ServerFlagsProto message, NetworkEvent.Context context) {
        enqueue(context, message, () -> {
            ClientSessionRuntime.serverFlags(message.canSwitchModel());
            NetworkDiagnostics.message("applied", MessageDirection.SERVER_TO_CLIENT, message, "server-flags");
        });
    }

    static void directive(SelectionDirectiveProto message, NetworkEvent.Context context) {
        if (!message.hasModel() || !ModelReferenceCodec.valid(message.modelUnsafe()) || !PlayerStateValidator
            .validSelectionState(SelectionStateProto.newBuilder().setTextureId(message.textureId()).build(), false))
            return;
        enqueue(context, message, () -> {
            ClientModelService.instance().demands().reselect(new ClientDemandCoordinator.Target(
                ModelReferenceCodec.read(message.modelUnsafe()), message.textureId()));
            NetworkDiagnostics.message("directive-consumed", MessageDirection.SERVER_TO_CLIENT, message,
                "reselect-requested");
        });
    }

    static void invalidated(SelectionInvalidatedProto message, NetworkEvent.Context context) {
        enqueue(context, message, () -> {
            ClientModelService.instance().preferences().invalidateSelection();
            NetworkDiagnostics.message("invalidation-consumed", MessageDirection.SERVER_TO_CLIENT, message,
                "reselect-requested");
        });
    }
}
