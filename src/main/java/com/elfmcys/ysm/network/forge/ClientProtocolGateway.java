package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.network.protocol.ExpressionProtocol;
import com.elfmcys.ysm.network.protocol.EntityRefEncoder;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.network.ConfigActionProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.elfmcys.ysm.proto.network.SubmitConfigActionRequestProto;
import com.elfmcys.ysm.proto.network.SwingHandRequestProto;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import com.elfmcys.ysm.network.protocol.SelectionCodec;
import com.elfmcys.ysm.capability.CommittedSelection;

public final class ClientProtocolGateway {
    private static final LocalPlayerStateReporter STATE_REPORTER = new LocalPlayerStateReporter();

    private ClientProtocolGateway() {}

    public static void tick(LocalPlayer player, PlayerAnimatableCapability capability) {
        STATE_REPORTER.tick(player, capability);
    }

    public static void resetWorld() {
        ClientModelService.current().ifPresent(service -> service.demands().reset());
        STATE_REPORTER.resetSession();
    }

    public static void playSelfAnimation(String animationId) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null)
            player.getCapability(com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP)
                .ifPresent(capability -> capability.playExtraAnimation(animationId));
        STATE_REPORTER.setAnimation(animationId);
    }

    public static void stopSelfAnimation() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null)
            player.getCapability(com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP)
                .ifPresent(PlayerAnimatableCapability::stopExtraAnimation);
        reportAnimationStopped();
    }

    public static void reportAnimationStopped() {
        STATE_REPORTER.setAnimation("");
    }

    public static void selectionCommitted(ClientPlayerPreferences.CurrentSelection selected,
        NamedRoamingSnapshot initial) {
        STATE_REPORTER.selectionCommitted(selected, initial);
    }

    public static void sendTexture(ClientPlayerPreferences.CurrentSelection selected) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null)
            send(ProtocolMessages.SELECTION_STATE_SYNC_ID,
                SelectionStateSyncProto.newBuilder().setEntityId(player.getId()).setIntentId(selected.intentId())
                    .setState(SelectionStateProto.newBuilder().setTextureId(selected.target().texture()).build())
                    .build());
    }

    public static void selectModel(ModelId hash, String textureId) {
        ClientModelService.instance().demands()
            .select(new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target(hash, textureId));
    }

    static SelectionChangeProto prepareSelection(int entityId, ClientDemandCoordinator.Target target, long intentId,
        NamedRoamingSnapshot initial, RoamingDictionary dictionary) {
        var selected = new CommittedSelection(target.identity(), intentId, null, dictionary,
            dictionary.encode(initial.toPayload(java.util.Arrays.asList(dictionary.names()))), target.texture(), "");
        return SelectionChangeProto.newBuilder().setSelection(SelectionCodec.write(entityId, selected)).build();
    }

    static void sendModelSelection(net.minecraft.network.Connection connection, SelectionChangeProto message) {
        if (!connection.isConnected() || !NetworkHandler.isChannelPresent(connection))
            throw new ClientDemandCoordinator.NotSubmitted("Selection transport is unavailable");
        NetworkHandler.sendToServer(connection, message);
    }

    public static void updateStar(ModelId hash, boolean add) {
        ClientModelService.instance().preferences().favourite(hash, add);
    }

    public static void submitConfigBoolean(AnimatableEntity<?> animatable, long generation, ConfigActionRoute route,
        boolean value) {
        submitConfigAction(animatable, generation, route, ConfigActionProto.newBuilder().setBoolValue(value));
    }

    public static void submitConfigFloat(AnimatableEntity<?> animatable, long generation, ConfigActionRoute route,
        float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("config F32 value must be finite");
        }
        submitConfigAction(animatable, generation, route, ConfigActionProto.newBuilder().setF32Value(value));
    }

    public static void submitConfigLabel(AnimatableEntity<?> animatable, long generation, ConfigActionRoute route,
        String labelName, int labelIndex) {
        submitConfigAction(animatable, generation, route,
            ConfigActionProto.newBuilder().setLabelIndex(labelIndex).setLabelName(labelName));
    }

    private static void submitConfigAction(AnimatableEntity<?> animatable, long generation, ConfigActionRoute route,
        ConfigActionProto.Builder actionBuilder) {
        animatable.waitForAsyncUpdate();
        var runtime = animatable.getExpressionRuntime();
        if (generation <= 0 || runtime.generation() != generation) {
            throw new IllegalStateException("config action belongs to a stale entity generation");
        }
        var action = actionBuilder.setButtonId(route.buttonId()).setFormIndex(route.formIndex()).build();
        if (!ControlHandler.validWireAction(action)) {
            throw new IllegalArgumentException("config action violates the wire contract");
        }
        if (action.hasBoolValue()) {
            runtime.validateConfigAction(generation, route.location(), ProgramRole.CONFIG_WRITE_BOOL, "write");
        } else if (action.hasF32Value()) {
            runtime.validateConfigAction(generation, route.location(), ProgramRole.CONFIG_WRITE_F32, "write");
        } else {
            runtime.validateConfigAction(generation, route.labelLocation(), ProgramRole.CONFIG_LABEL_ACTION,
                route.labelDiscriminator(action.labelName(), action.labelIndex()));
        }
        Entity entity = animatable.getEntity();
        var target = EntityRefEncoder.encode(entity.getId());
        send(ProtocolMessages.SUBMIT_CONFIG_ACTION_REQUEST_ID,
            configActionRequest(consumerSemantics(animatable), target, action));
    }

    public static void emitMolangSync(AnimatableEntity<?> animatable, FloatList values) {
        var selected = ClientModelService.instance().preferences().currentSelection();
        if (selected == null || selected.intentId() <= 0 || !animatable.installedForSelection(selected.operation()))
            return;
        var session = ClientSessionRuntime.businessSession().orElse(null);
        if (session == null
            || !(selected
                .operation() instanceof com.elfmcys.ysm.model.session.client.PlayerPreferenceSession.Scope scope)
            || scope.authority() != session)
            return;
        try {
            NetworkHandler.sendToServer(session.connection(),
                molangSyncMessage(animatable.getEntity().getId(), selected.intentId(), values));
        } catch (RuntimeException ignored) {
        }
    }

    private static com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics
        consumerSemantics(AnimatableEntity<?> animatable) {
        var entity = animatable;
        if (entity.getModelRenderTarget() == null) {
            throw new IllegalStateException("entity has no consumer render target");
        }
        return entity.getModelRenderTarget().sharedSemantics();
    }

    static SubmitConfigActionRequestProto configActionRequest(
        com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics semantics, EntityRefProto target,
        ConfigActionProto action) {
        return SubmitConfigActionRequestProto.newBuilder().setTarget(target)
            .setIdentity(ExpressionProtocol.identity(semantics)).setAction(action).build();
    }

    static SelectionStateSyncProto molangSyncMessage(int entityId, long intentId, FloatList values) {
        if (values.size() > MolangEventCatalog.SYNC_MAX_ARGUMENTS)
            throw new IllegalArgumentException("sync payload exceeds catalog bound");
        for (int i = 0; i < values.size(); i++)
            if (!Float.isFinite(values.getFloat(i)))
                throw new IllegalArgumentException("sync payload must be finite");
        return SelectionStateSyncProto.newBuilder().setEntityId(entityId).setIntentId(intentId)
            .setSync(SyncArgumentsProto.newBuilder().addArguments(values.toFloatArray()).build()).build();
    }

    public static void swingHand(InteractionHand hand) {
        send(ProtocolMessages.SWING_HAND_REQUEST_ID,
            SwingHandRequestProto.newBuilder().setHand(hand == InteractionHand.MAIN_HAND
                ? com.elfmcys.ysm.proto.network.Hand.MAIN : com.elfmcys.ysm.proto.network.Hand.OFF).build());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void send(int id, us.hebi.quickbuf.ProtoMessage message) {
        var spec = ProtocolMessages.REGISTRY.find(id).orElse(null);
        if (spec != null && spec.messageType().isInstance(message) && NetworkHandler.isRemoteChannelPresent()
            && ClientSessionRuntime.businessSession().isPresent()) {
            try {
                NetworkHandler.sendToServer(message);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
