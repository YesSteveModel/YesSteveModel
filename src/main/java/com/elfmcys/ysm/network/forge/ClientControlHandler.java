package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.aot.TransientExpressionSession;
import com.elfmcys.ysm.expression.host.EventArguments;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.protocol.ExpressionProtocol;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.util.ProtoBytes;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import static com.elfmcys.ysm.network.forge.ControlHandler.*;

/** Keeps client capability types out of common handler verification on dedicated servers. */
@OnlyIn(Dist.CLIENT)
final class ClientControlHandler {
    private ClientControlHandler() {}

    static void handleStarredModels(StarredModelsSnapshotProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var hashes = readHashSet(message.modelHashes());
        var connection = context.getNetworkManager();
        if (hashes != null)
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP)
                        .ifPresent(capability -> capability.setStarModels(hashes));
                }
            }));
        context.setPacketHandled(true);
    }

    static void handlePrivilegedTransient(PrivilegedTransientEventProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (validPrivilegedTransient(message)) {
            var connection = context.getNetworkManager();
            context.enqueueWork(
                () -> ClientSessionRuntime.runIfCurrent(connection, () -> executePrivilegedTransient(message)));
        }
        context.setPacketHandled(true);
    }

    static void handleConfigAction(ConfigActionEventProto message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (validWireAction(message.action())) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> {
                var level = Minecraft.getInstance().level;
                var entity = level == null ? null : level.getEntity(message.subject().entityId());
                if (entity instanceof Player player) {
                    player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
                        .ifPresent(capability -> applyConfigAction(capability, message.identity(), message.action()));
                }
            }));
        }
        context.setPacketHandled(true);
    }

    static void handleMolangSync(MolangSyncEventProto message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (ExpressionProtocol.validIdentity(message.identity()) && validArguments(message.arguments())) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> {
                var level = Minecraft.getInstance().level;
                var entity = level == null ? null : level.getEntity(message.subject().entityId());
                if (entity != null)
                    entity.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                        capability.waitForAsyncUpdate();
                        var module = capability.getExpressionRuntime().module().orElse(null);
                        if (module == null || capability.getModelRenderTarget() == null
                            || !acceptsMolangSync(message, capability.getModelRenderTarget().sharedSemantics()))
                            return;
                        capability.dispatchSync(
                            EventArguments.sync(new it.unimi.dsi.fastutil.floats.FloatArrayList(message.arguments())));
                    });
            }));
        }
        context.setPacketHandled(true);
    }

    private static void executePrivilegedTransient(PrivilegedTransientEventProto message) {
        var level = Minecraft.getInstance().level;
        if (level == null)
            return;
        for (var target : message.targets()) {
            Entity entity = level.getEntity(target.target().entityId());
            if (entity instanceof Player player) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                    if (target.modelId().remaining() != Hash256.SIZE || capability.getModelRenderTarget() == null
                        || !ProtoBytes.equals(capability.getModelRenderTarget().modelHash(), target.modelId())
                        || !capability.getModelRenderTarget().renderTargetId().equals(target.targetId()))
                        return;
                    capability.executeTransient(message.source(),
                        TransientExpressionSession.Privilege.SERVER_ADMIN_ACTION, false, null);
                });
            }
        }
    }

    private static void applyConfigAction(AnimatableEntity<?> capability, ExpressionTargetIdentityProto identity,
        ConfigActionProto action) {
        capability.waitForAsyncUpdate();
        var runtime = capability.getExpressionRuntime();
        var module = runtime.module().orElse(null);
        var entity = capability;
        if (module == null || entity.getModelRenderTarget() == null
            || !acceptsConfigAction(identity, action, entity.getModelRenderTarget().sharedSemantics()))
            return;
        var generation = runtime.generation();
        var route = new ConfigActionRoute(action.buttonId(), action.formIndex());
        if (action.hasBoolValue()) {
            capability.writeConfigBoolean(generation, route, action.boolValue());
        } else if (action.hasF32Value()) {
            capability.writeConfigFloat(generation, route, action.f32Value());
        } else if (action.hasLabelIndex()) {
            capability.executeConfigLabel(generation, route, action.labelName(), action.labelIndex());
        }
    }
}
