package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.expression.inventory.MolangEventCatalog;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.info.roulette.forms.ConfigActionRoute;
import com.elfmcys.ysm.network.protocol.ExpressionProtocol;
import com.elfmcys.ysm.network.protocol.EntityRefEncoder;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.network.ConfigActionProto;
import com.elfmcys.ysm.proto.network.EmitMolangSyncProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.SelectModelRequestProto;
import com.elfmcys.ysm.proto.network.SubmitConfigActionRequestProto;
import com.elfmcys.ysm.proto.network.SwingHandRequestProto;
import com.elfmcys.ysm.proto.network.UpdateStarredModelRequestProto;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.nio.ByteBuffer;

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
        STATE_REPORTER.setAnimation(animationId);
    }

    public static void stopSelfAnimation() {
        STATE_REPORTER.setAnimation("");
    }

    public static void acceptAuthoritativeFull(Hash256 modelHash, Integer roamingKey) {
        STATE_REPORTER.acceptAuthoritativeFull(modelHash, roamingKey);
    }

    public static void localPlayerCloned() {
        STATE_REPORTER.restartReportLifetime();
    }

    public static void selectModel(Hash256 hash, String textureId) {
        ClientModelService.instance().demands()
            .select(new com.elfmcys.ysm.client.demand.ClientDemandCoordinator.Target(hash, textureId));
    }

    static void sendModelSelection(Hash256 hash, String textureId, long intentId) {
        if (intentId == 0)
            throw new IllegalArgumentException("Selection requires a nonzero intent identifier");
        var request = SelectModelRequestProto.newBuilder().setTextureId(textureId).setIntentId(intentId);
        if (hash == null) {
            request.setIntrinsicDefault(true);
        } else {
            request.setModelId(ByteBuffer.wrap(hash.bytes()));
        }
        if (!NetworkHandler.isRemoteChannelPresent() || ClientSessionRuntime.businessSession().isEmpty())
            throw new IllegalStateException("Model selection connection is unavailable");
        NetworkHandler.sendToServer(request.build());
    }

    public static void updateStar(Hash256 hash, boolean add) {
        send(ProtocolMessages.UPDATE_STARRED_MODEL_REQUEST_ID,
            UpdateStarredModelRequestProto.newBuilder().setModelHash(ByteBuffer.wrap(hash.bytes()))
                .setOperation(add ? com.elfmcys.ysm.proto.network.StarredModelOperation.ADD
                    : com.elfmcys.ysm.proto.network.StarredModelOperation.REMOVE)
                .build());
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
        var runtime = animatable.getExpressionRuntime();
        var module =
            runtime.module().orElseThrow(() -> new IllegalStateException("entity has no current expression target"));
        if (generation <= 0 || runtime.generation() != generation) {
            throw new IllegalStateException("config action belongs to a stale entity generation");
        }
        var action = actionBuilder.setButtonId(route.buttonId()).setFormIndex(route.formIndex()).build();
        if (!ControlHandler.validWireAction(action)) {
            throw new IllegalArgumentException("config action violates the wire contract");
        }
        if (action.hasBoolValue()) {
            module.requireProgram(route.location(), ProgramRole.CONFIG_WRITE_BOOL, "write");
        } else if (action.hasF32Value()) {
            module.requireProgram(route.location(), ProgramRole.CONFIG_WRITE_F32, "write");
        } else {
            module.requireProgram(route.labelLocation(), ProgramRole.CONFIG_LABEL_ACTION,
                route.labelDiscriminator(action.labelName(), action.labelIndex()));
        }
        Entity entity = animatable.getEntity();
        var target = EntityRefEncoder.encode(entity.getId());
        send(ProtocolMessages.SUBMIT_CONFIG_ACTION_REQUEST_ID,
            configActionRequest(consumerSemantics(animatable), target, action));
    }

    public static void emitMolangSync(AnimatableEntity<?> animatable, FloatList values) {
        var module = animatable.getExpressionRuntime().module()
            .orElseThrow(() -> new IllegalStateException("entity has no current expression target"));
        send(ProtocolMessages.EMIT_MOLANG_SYNC_ID, molangSyncMessage(consumerSemantics(animatable), values));
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

    static EmitMolangSyncProto
        molangSyncMessage(com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics semantics, FloatList values) {
        if (values.size() > MolangEventCatalog.SYNC_MAX_ARGUMENTS) {
            throw new IllegalArgumentException("sync payload exceeds catalog bound");
        }
        for (var index = 0; index < values.size(); index++) {
            if (!Float.isFinite(values.getFloat(index))) {
                throw new IllegalArgumentException("sync payload must contain finite F32 values");
            }
        }
        var message = EmitMolangSyncProto.newBuilder();
        values.forEach((float value) -> message.addArguments(value));
        message.setIdentity(ExpressionProtocol.identity(semantics));
        return message.build();
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
