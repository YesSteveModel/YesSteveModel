package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigFormsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButtonProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.proto.network.ConfigActionProto;
import com.elfmcys.ysm.proto.network.ConfigActionEventProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.SubmitConfigActionRequestProto;
import com.elfmcys.ysm.util.ProtoUtil;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExpressionActionIngressTest {

    @Test
    void configCrossesGatewayServerAndReceiverWithTheSameSharedProjection() throws Exception {
        var settings = settings();
        try (var sender = TestExpressionModules.createLocalVariant(1, 3, settings);
            var receiver = TestExpressionModules.createLocalVariant(2, 17, settings)) {
            var action = ConfigActionProto.newBuilder().setButtonId("mode").setFormIndex(0).setLabelName("")
                .setBoolValue(true).build();
            var request =
                ClientProtocolGateway.configActionRequest(sender.sharedSemantics(TestExpressionModules.consumerId()),
                    EntityRefProto.newBuilder().setEntityId(9).build(), action);
            request = roundTrip(ProtocolMessages.SUBMIT_CONFIG_ACTION_REQUEST_ID, request,
                SubmitConfigActionRequestProto.class);
            var forwarded = ControlHandler.authorizeConfigAction(request,
                receiver.sharedSemantics(TestExpressionModules.consumerId()).modelId(),
                receiver.sharedSemantics(TestExpressionModules.consumerId()).targetId(), settings, 9);
            assertNotNull(forwarded);

            var received = roundTrip(ProtocolMessages.CONFIG_ACTION_EVENT_ID, forwarded, ConfigActionEventProto.class);
            assertTrue(ControlHandler.acceptsConfigAction(received.identity(), received.action(),
                receiver.sharedSemantics(TestExpressionModules.consumerId())));
            assertEquals(9, received.subject().entityId());
        }
    }

    @Test
    void syncUsesSelectionIdentityAndDoesNotRequireSharedSemantics() throws Exception {
        for (var args : new float[][] {new float[0], new float[] {1.25F, -2.5F}}) {
            var event = roundTrip(ProtocolMessages.SELECTION_STATE_SYNC_ID,
                ClientProtocolGateway.molangSyncMessage(41, 9, new FloatArrayList(args)),
                com.elfmcys.ysm.proto.network.SelectionStateSyncProto.class);
            assertEquals(41, event.entityId());
            assertEquals(9, event.intentId());
            assertTrue(event.hasSync());
            assertEquals(new FloatArrayList(args), event.sync().arguments());
        }
    }

    private static SettingsProto settings() {
        var program =
            ProgramProto.newBuilder().setFormat(1).setBytecode(java.nio.ByteBuffer.wrap(new byte[] {1})).build();
        return SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("").setDisablePreviewRotation(false)
            .addExtraAnimationButtons(ExtraAnimationButtonProto.newBuilder().setId("mode").setName("").setSound("")
                .addConfigForms(ConfigFormsProto.newBuilder().setType("checkbox").setTitle("").setDescription("")
                    .setStep(0).setMin(0).setMax(0).setReadProgram(program).setWriteProgram(program).build())
                .build())
            .build();
    }

    @SuppressWarnings("unchecked")
    private static <T extends ProtoMessage<T>> T roundTrip(int messageId, T message, Class<T> messageType)
        throws Exception {
        var rawSpec = ProtocolMessages.REGISTRY.find(messageId).orElseThrow();
        assertEquals(messageType, rawSpec.messageType());
        var spec = (ProtocolMessageSpec<T>)rawSpec;
        try (var frame = FrameCodec.encode(messageId, ProtoUtil.serializeToArray(message), new byte[0]);
            var decoded = FrameCodec.decode(frame.bytes(), id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
            assertEquals(messageId, decoded.messageId());
            assertEquals(0, decoded.attachment().size());
            return ProtocolBuffer.parse(decoded.protobuf(), spec.parser());
        }
    }
}
