package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.event.CapabilityEvent;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.ExpressionProtocol;
import com.elfmcys.ysm.network.protocol.StarredModelSnapshots;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshotProto;
import com.elfmcys.ysm.proto.network.SwingHandRequestProto;
import com.elfmcys.ysm.proto.network.UpdateStarredModelRequestProto;
import com.elfmcys.ysm.util.ProtoBytes;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.Collection;
import java.util.function.Supplier;

public final class ControlHandler {
    private static final int MAX_TRANSIENT_TARGETS = 1024;
    private static final int MAX_TRANSIENT_SOURCE_BYTES = 4096;

    private ControlHandler() {}

    public static void handleStarredModels(StarredModelsSnapshotProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        ClientControlHandler.handleStarredModels(message, contextSupplier);
    }

    public static void handleUpdateStar(UpdateStarredModelRequestProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && message.modelHash().remaining() == Hash256.SIZE
            && message.operation() != com.elfmcys.ysm.proto.network.StarredModelOperation.UNSPECIFIED) {
            var hash = new Hash256(ProtoBytes.copy(message.modelHash()));
            enqueueServer(context, sender,
                () -> sender.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).ifPresent(capability -> {
                    if (message.operation() == com.elfmcys.ysm.proto.network.StarredModelOperation.ADD) {
                        capability.addModel(hash);
                    } else {
                        capability.removeModel(hash);
                    }
                }));
        }
        context.setPacketHandled(true);
    }

    public static void handlePrivilegedTransient(PrivilegedTransientEventProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        ClientControlHandler.handlePrivilegedTransient(message, contextSupplier);
    }

    public static PrivilegedTransientEventProto privilegedTransient(String source, Collection<ServerPlayer> players) {
        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TRANSIENT_SOURCE_BYTES) {
            throw new IllegalArgumentException("transient source exceeds wire bound");
        }
        var result = PrivilegedTransientEventProto.newBuilder().setSource(source)
            .setCapability(TransientCapability.SERVER_ADMIN_ACTION);
        players.stream().limit(MAX_TRANSIENT_TARGETS).forEach(player -> {
            var target = serverTarget(player);
            if (target != null) {
                result.addTargets(PrivilegedTransientTargetProto.newBuilder()
                    .setTarget(EntityRefProto.newBuilder().setEntityId(player.getId()).build())
                    .setModelId(ProtoBytes.wrap(target.model().representation().modelId()))
                    .setTargetId(target.targetId()).build());
            }
        });
        return result.build();
    }

    public static void handleSubmitConfigAction(SubmitConfigActionRequestProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && validWireAction(message.action())) {
            enqueueServer(context, sender, () -> {
                var entity = resolveEntity(sender, message.target());
                if (entity == null)
                    return;
                if (entity != sender)
                    return;
                var target = serverTarget(entity);
                if (target == null)
                    return;
                var event = authorizeConfigAction(message, target.model().representation().modelId(), target.targetId(),
                    target.settings(), entity.getId());
                if (event == null)
                    return;
                NetworkHandler.broadcastToVisiblePlayersAndSelf(event, entity);
            });
        }
        context.setPacketHandled(true);
    }

    public static void handleConfigAction(ConfigActionEventProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        ClientControlHandler.handleConfigAction(message, contextSupplier);
    }

    public static void handleEmitMolangSync(EmitMolangSyncProto message,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && ExpressionProtocol.validIdentity(message.identity())
            && validArguments(message.arguments())) {
            enqueueServer(context, sender, () -> {
                var target = serverTarget(sender);
                if (target == null)
                    return;
                var event = authorizeMolangSync(message, target.model().representation().modelId(), target.targetId(),
                    target.settings(), sender.getId());
                if (event == null)
                    return;
                NetworkHandler.broadcastToVisiblePlayersAndSelf(event, sender);
            });
        }
        context.setPacketHandled(true);
    }

    public static void handleMolangSync(MolangSyncEventProto message, Supplier<NetworkEvent.Context> contextSupplier) {
        ClientControlHandler.handleMolangSync(message, contextSupplier);
    }

    public static void handleSwingHand(SwingHandRequestProto message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && message.hand() != com.elfmcys.ysm.proto.network.Hand.UNSPECIFIED) {
            enqueueServer(context, sender, () -> swing(sender, message.hand() == com.elfmcys.ysm.proto.network.Hand.MAIN
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND));
        }
        context.setPacketHandled(true);
    }

    private static void enqueueServer(NetworkEvent.Context context, ServerPlayer sender, Runnable action) {
        var connection = context.getNetworkManager();
        var service = ServerModelService.current().orElse(null);
        var owner = service == null ? null : service.session(sender, connection).orElse(null);
        if (owner != null) {
            context.enqueueWork(() -> service.runIfCurrent(sender, connection, owner, () -> {
                if (owner.active()) {
                    action.run();
                }
            }));
        }
    }

    public static void applyAcceptedModelSelection(ServerPlayer sender, Hash256 hash, String textureId) {
        applyAcceptedModelSelection(sender, hash, textureId, true);
    }

    static void applyAcceptedModelSelection(ServerPlayer sender, Hash256 hash, String textureId, boolean publishState) {
        var modelCapability = sender.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).resolve().orElse(null);
        if (modelCapability == null) {
            return;
        }
        ServerModelService.instance().catalog().ifPresent(snapshot -> {
            var previousModelId = modelCapability.getModelId();
            if (hash == null) {
                ModelSelectionService.selectBuiltinDefault(modelCapability, snapshot, textureId);
            } else {
                modelCapability.setModelAndTexture(hash, textureId);
            }
            if (!java.util.Objects.equals(previousModelId, modelCapability.getModelId())) {
                modelCapability.applyClientAnimation("");
            }
        });
        if (publishState && PlayerStateHandler.sendAuthoritativeFull(sender, true) && sender.getVehicle() != null
            && sender.getVehicle().getFirstPassenger() == sender) {
            CapabilityEvent.onVehicleSetModel(sender.getVehicle(), sender);
        }
    }

    static Set<Hash256> readHashSet(Iterable<java.nio.ByteBuffer> values) {
        var result = new HashSet<Hash256>();
        var count = 0;
        for (var value : values) {
            if (++count > StarredModelSnapshots.MAX_MODEL_SET_SIZE || value.remaining() != Hash256.SIZE)
                return null;
            if (!result.add(new Hash256(com.elfmcys.ysm.util.ProtoBytes.copy(value))))
                return null;
        }
        return result;
    }

    static boolean validArguments(it.unimi.dsi.fastutil.floats.FloatList values) {
        if (values.size() > MolangEventCatalog.SYNC_MAX_ARGUMENTS)
            return false;
        for (var i = 0; i < values.size(); i++)
            if (!Float.isFinite(values.getFloat(i)))
                return false;
        return true;
    }

    static ConfigActionEventProto authorizeConfigAction(SubmitConfigActionRequestProto message, Hash256 modelId,
        String targetId, SettingsProto settings, int subjectId) {
        if (!validWireAction(message.action())
            || !ExpressionProtocol.matchesServerAuthority(message.identity(), modelId, targetId, settings)
            || !validCatalogAction(message.action(), settings)) {
            return null;
        }
        return ConfigActionEventProto.newBuilder()
            .setSubject(EntityRefProto.newBuilder().setEntityId(subjectId).build()).setIdentity(message.identity())
            .setAction(message.action()).build();
    }

    static MolangSyncEventProto authorizeMolangSync(EmitMolangSyncProto message, Hash256 modelId, String targetId,
        SettingsProto settings, int subjectId) {
        if (!validArguments(message.arguments())
            || !ExpressionProtocol.matchesServerAuthority(message.identity(), modelId, targetId, settings)) {
            return null;
        }
        return MolangSyncEventProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(subjectId).build())
            .setIdentity(message.identity()).addAllArguments(message.arguments()).build();
    }

    static boolean acceptsMolangSync(MolangSyncEventProto message,
        com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics semantics) {
        return validArguments(message.arguments()) && ExpressionProtocol.matches(message.identity(), semantics);
    }

    static boolean acceptsConfigAction(ExpressionTargetIdentityProto identity, ConfigActionProto action,
        com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics semantics) {
        return validWireAction(action) && ExpressionProtocol.matches(identity, semantics);
    }

    static boolean validPrivilegedTransient(PrivilegedTransientEventProto message) {
        if (message.targets().size() > MAX_TRANSIENT_TARGETS
            || message.source().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TRANSIENT_SOURCE_BYTES
            || message.capability() != TransientCapability.SERVER_ADMIN_ACTION) {
            return false;
        }
        for (var target : message.targets()) {
            if (target.target().hasPlayerId() || target.modelId().remaining() != Hash256.SIZE
                || target.targetId().isBlank() || target.targetId().length() > ExpressionProtocol.MAX_TARGET_ID_LENGTH)
                return false;
        }
        return true;
    }

    static boolean validWireAction(ConfigActionProto action) {
        if (action.buttonId().isBlank() || action.buttonId().length() > ExpressionProtocol.MAX_BUTTON_ID_LENGTH
            || action.formIndex() < 0 || action.formIndex() > ConfigActionRoute.MAX_FORM_INDEX)
            return false;
        if (action.hasBoolValue()) {
            return action.labelName().isEmpty();
        }
        if (action.hasF32Value()) {
            return action.labelName().isEmpty() && Float.isFinite(action.f32Value());
        }
        return action.hasLabelIndex() && action.labelIndex() >= 0
            && action.labelIndex() <= ConfigActionRoute.MAX_FORM_INDEX && !action.labelName().isEmpty()
            && action.labelName().length() <= ExpressionProtocol.MAX_LABEL_NAME_LENGTH;
    }

    static boolean validCatalogAction(ConfigActionProto action, SettingsProto settings) {
        for (var button : settings.extraAnimationButtons()) {
            if (!button.id().equals(action.buttonId()) || action.formIndex() < 0
                || action.formIndex() >= button.configForms().size())
                continue;
            var form = button.configForms().get(action.formIndex());
            if (action.hasBoolValue())
                return form.type().equals("checkbox");
            if (action.hasF32Value())
                return form.type().equals("range");
            if (!form.type().equals("radio") || action.labelIndex() < 0 || action.labelIndex() >= form.labels().size())
                return false;
            var label = form.labels().get(action.labelIndex());
            return label.name().equals(action.labelName());
        }
        return false;
    }

    private static Entity resolveEntity(ServerPlayer sender, EntityRefProto reference) {
        var entity = sender.serverLevel().getEntity(reference.entityId());
        if (entity == null)
            return null;
        if (!reference.hasPlayerId())
            return entity;
        if (!(entity instanceof Player player))
            return null;
        var expected = PlayerIdDerivation.derive(player.getUUID()).bytes();
        var actual = reference.playerIdUnsafe().duplicate();
        if (actual.remaining() != expected.length)
            return null;
        for (var index = 0; index < expected.length; index++) {
            if (actual.get() != expected[index])
                return null;
        }
        return entity;
    }

    private static ServerTarget serverTarget(Entity entity) {
        var catalog = ServerModelService.current().flatMap(ServerModelService::catalog).orElse(null);
        if (catalog == null)
            return null;
        ManagedContainer model;
        if (entity instanceof ServerPlayer player) {
            var modelId = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP)
                .map(value -> value.getModelId()).orElse(null);
            model = modelId == null ? catalog.defaultModel().orElse(null) : catalog.find(modelId).orElse(null);
        } else {
            return null;
        }
        if (model == null)
            return null;
        return new ServerTarget(model, "player", model.view().getMetadata().getSettings());
    }

    private record ServerTarget(ManagedContainer model, String targetId, SettingsProto settings) {
    }

    private static void swing(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() || !stack.onEntitySwing(player)) {
            if (!player.swinging || player.swingTime >= currentSwingDuration(player) / 2 || player.swingTime < 0) {
                player.swingTime = -1;
                player.swinging = true;
                player.swingingArm = hand;
                if (player.level() instanceof ServerLevel level) {
                    var packet = new ClientboundAnimatePacket(player, hand == InteractionHand.MAIN_HAND ? 0 : 3);
                    ServerChunkCache chunks = level.getChunkSource();
                    chunks.broadcast(player, packet);
                }
            }
        }
    }

    private static int currentSwingDuration(LivingEntity entity) {
        if (MobEffectUtil.hasDigSpeed(entity))
            return 6 - (1 + MobEffectUtil.getDigSpeedAmplification(entity));
        return entity.hasEffect(MobEffects.DIG_SLOWDOWN)
            ? 6 + (1 + entity.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) * 2 : 6;
    }
}
