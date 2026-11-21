package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.event.CapabilityEvent;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.ExpressionProtocol;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.SwingHandRequestProto;
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
    private ControlHandler() {}

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

    static boolean validArguments(it.unimi.dsi.fastutil.floats.FloatList values) {
        if (values.size() > MolangEventCatalog.SYNC_MAX_ARGUMENTS)
            return false;
        for (var i = 0; i < values.size(); i++)
            if (!Float.isFinite(values.getFloat(i)))
                return false;
        return true;
    }

    static ConfigActionEventProto authorizeConfigAction(SubmitConfigActionRequestProto message, ModelId modelId,
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

    static boolean acceptsConfigAction(ExpressionTargetIdentityProto identity, ConfigActionProto action,
        com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics semantics) {
        return validWireAction(action) && ExpressionProtocol.matches(identity, semantics);
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
