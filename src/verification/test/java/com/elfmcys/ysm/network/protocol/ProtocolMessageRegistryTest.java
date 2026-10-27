package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.ConfigActionEventProto;
import com.elfmcys.ysm.proto.network.EmitMolangSyncProto;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.MolangSyncEventProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PrivilegedTransientEventProto;
import com.elfmcys.ysm.proto.network.ProjectileModelStateProto;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.proto.network.SelectModelRequestProto;
import com.elfmcys.ysm.proto.network.SelectModelResultProto;
import com.elfmcys.ysm.proto.network.ServerHelloProto;
import com.elfmcys.ysm.proto.network.SessionDeltaFragmentProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.proto.network.SessionResponseProto;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshotProto;
import com.elfmcys.ysm.proto.network.SubmitConfigActionRequestProto;
import com.elfmcys.ysm.proto.network.SwingHandRequestProto;
import com.elfmcys.ysm.proto.network.UpdateStarredModelRequestProto;
import com.elfmcys.ysm.proto.network.VehicleModelStateProto;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolMessageRegistryTest {
    @Test
    void rejectsDuplicateIdsAndTypes() {
        var first = spec(1, SessionResponseProto.class, SessionResponseProto::parseFrom);
        var duplicateId = spec(1, ServerHelloProto.class, ServerHelloProto::parseFrom);
        var duplicateType = spec(2, SessionResponseProto.class, SessionResponseProto::parseFrom);

        assertThrows(IllegalArgumentException.class,
            () -> ProtocolMessageRegistry.builder().add(first).add(duplicateId));
        assertThrows(IllegalArgumentException.class,
            () -> ProtocolMessageRegistry.builder().add(first).add(duplicateType));
    }

    @Test
    void modelSessionTableHasExactTypedIdentityAndAttachmentPolicy() {
        var expected = List.of(
            row(0, MessageDirection.SERVER_TO_CLIENT, ServerHelloProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(1, MessageDirection.CLIENT_TO_SERVER, SessionResponseProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(2, MessageDirection.SERVER_TO_CLIENT, SessionFullFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(3, MessageDirection.SERVER_TO_CLIENT, SessionDeltaFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(4, MessageDirection.CLIENT_TO_SERVER, MetadataPrefixRequestProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(5, MessageDirection.CLIENT_TO_SERVER, ModelChunkRequestProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(6, MessageDirection.CLIENT_TO_SERVER, PresentationPageRequestProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(7, MessageDirection.CLIENT_TO_SERVER, ResourceTransferCancelProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(8, MessageDirection.SERVER_TO_CLIENT, ResourceTransferFailureProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(9, MessageDirection.CLIENT_TO_SERVER, SelectModelRequestProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(10, MessageDirection.SERVER_TO_CLIENT, SelectModelResultProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(11, MessageDirection.SERVER_TO_CLIENT, MetadataPrefixFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN),
            row(12, MessageDirection.SERVER_TO_CLIENT, ChunkFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.REQUIRED),
            row(13, MessageDirection.SERVER_TO_CLIENT, PreviewFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL),
            row(14, MessageDirection.SERVER_TO_CLIENT, IconFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL),
            row(15, MessageDirection.SERVER_TO_CLIENT, PackCoverFragmentProto.class,
                ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL),
            row(16, MessageDirection.CLIENT_TO_SERVER, PlayerStateReportProto.class,
                ProtocolLimits.MAX_PLAYER_STATE_BYTES),
            row(17, MessageDirection.SERVER_TO_CLIENT, PlayerStateUpdateProto.class,
                ProtocolLimits.MAX_PLAYER_STATE_BYTES),
            row(18, MessageDirection.SERVER_TO_CLIENT, StarredModelsSnapshotProto.class,
                ProtocolLimits.MAX_MODEL_SET_BYTES),
            row(19, MessageDirection.CLIENT_TO_SERVER, UpdateStarredModelRequestProto.class,
                ProtocolLimits.MAX_STAR_UPDATE_BYTES),
            row(21, MessageDirection.SERVER_TO_CLIENT, PrivilegedTransientEventProto.class,
                ProtocolLimits.MAX_TRANSIENT_EVENT_BYTES),
            row(22, MessageDirection.CLIENT_TO_SERVER, SubmitConfigActionRequestProto.class,
                ProtocolLimits.MAX_ENTITY_ACTION_BYTES),
            row(23, MessageDirection.CLIENT_TO_SERVER, EmitMolangSyncProto.class, ProtocolLimits.MAX_STAR_UPDATE_BYTES),
            row(24, MessageDirection.SERVER_TO_CLIENT, MolangSyncEventProto.class,
                ProtocolLimits.MAX_MOLANG_SYNC_BYTES),
            row(25, MessageDirection.CLIENT_TO_SERVER, SwingHandRequestProto.class,
                ProtocolLimits.MAX_SWING_HAND_BYTES),
            row(26, MessageDirection.SERVER_TO_CLIENT, ProjectileModelStateProto.class,
                ProtocolLimits.MAX_MINECRAFT_STATE_BYTES),
            row(27, MessageDirection.SERVER_TO_CLIENT, VehicleModelStateProto.class,
                ProtocolLimits.MAX_MINECRAFT_STATE_BYTES),
            row(28, MessageDirection.SERVER_TO_CLIENT, ConfigActionEventProto.class,
                ProtocolLimits.MAX_ENTITY_ACTION_BYTES),
            row(29, MessageDirection.SERVER_TO_CLIENT, com.elfmcys.ysm.proto.network.PreviewAvailableProto.class, 128),
            row(30, MessageDirection.SERVER_TO_CLIENT, com.elfmcys.ysm.proto.network.GenPreviewStartProto.class, 16),
            row(31, MessageDirection.CLIENT_TO_SERVER, com.elfmcys.ysm.proto.network.MissingPreviewQueryProto.class,
                32),
            row(32, MessageDirection.SERVER_TO_CLIENT, com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto.class,
                30 * 1024),
            row(33, MessageDirection.CLIENT_TO_SERVER, com.elfmcys.ysm.proto.network.PreviewUploadRequestProto.class,
                128),
            row(34, MessageDirection.SERVER_TO_CLIENT, com.elfmcys.ysm.proto.network.PreviewUploadAdmissionProto.class,
                32),
            new Expected(35, MessageDirection.CLIENT_TO_SERVER,
                com.elfmcys.ysm.proto.network.PreviewUploadFragmentProto.class, 64,
                ProtocolMessageSpec.AttachmentPolicy.REQUIRED),
            row(36, MessageDirection.SERVER_TO_CLIENT, com.elfmcys.ysm.proto.network.PreviewRepairResultProto.class,
                64));

        for (var row : expected) {
            var actual = ProtocolMessages.REGISTRY.find(row.id).orElseThrow();
            assertEquals(row.direction, actual.direction());
            assertEquals(row.type, actual.messageType());
            assertEquals(row.maxEncodedBytes, actual.maxEncodedBytes());
            assertEquals(row.attachmentPolicy, actual.attachmentPolicy());
        }
        assertEquals(36, ProtocolMessages.REGISTRY.messages().size());
        assertFalse(ProtocolMessages.REGISTRY.find(20).isPresent());
    }

    @Test
    void attachmentPolicyRejectsOnlyStructurallyImpossibleShapes() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN.validate(1));
        assertThrows(IllegalArgumentException.class, () -> ProtocolMessageSpec.AttachmentPolicy.REQUIRED.validate(0));
        ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN.validate(0);
        ProtocolMessageSpec.AttachmentPolicy.REQUIRED.validate(1);
        ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL.validate(0);
        ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL.validate(1);
    }

    @Test
    void expressionIngressHasTypedDirectionsAndOnlyPrivilegedMessageCarriesSource() {
        assertMessage(ProtocolMessages.SUBMIT_CONFIG_ACTION_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            SubmitConfigActionRequestProto.class);
        assertMessage(ProtocolMessages.CONFIG_ACTION_EVENT_ID, MessageDirection.SERVER_TO_CLIENT,
            ConfigActionEventProto.class);
        assertMessage(ProtocolMessages.EMIT_MOLANG_SYNC_ID, MessageDirection.CLIENT_TO_SERVER,
            EmitMolangSyncProto.class);
        assertMessage(ProtocolMessages.MOLANG_SYNC_EVENT_ID, MessageDirection.SERVER_TO_CLIENT,
            MolangSyncEventProto.class);
        assertMessage(ProtocolMessages.PRIVILEGED_TRANSIENT_EVENT_ID, MessageDirection.SERVER_TO_CLIENT,
            PrivilegedTransientEventProto.class);

        for (var type : List.of(SubmitConfigActionRequestProto.class, ConfigActionEventProto.class,
            EmitMolangSyncProto.class, MolangSyncEventProto.class)) {
            assertFalse(
                java.util.Arrays.stream(type.getMethods()).anyMatch(method -> method.getName().equals("getSource")));
        }
    }

    private static void assertMessage(int id, MessageDirection direction, Class<?> type) {
        var actual = ProtocolMessages.REGISTRY.find(id).orElseThrow();
        assertEquals(direction, actual.direction());
        assertEquals(type, actual.messageType());
        assertEquals(ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, actual.attachmentPolicy());
    }

    private static Expected row(int id, MessageDirection direction, Class<?> type,
        ProtocolMessageSpec.AttachmentPolicy attachmentPolicy) {
        return new Expected(id, direction, type, FrameCodec.MAX_DECODED_PROTO_BYTES, attachmentPolicy);
    }

    private static Expected row(int id, MessageDirection direction, Class<?> type, int maxEncodedBytes) {
        return new Expected(id, direction, type, maxEncodedBytes, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN);
    }

    private static <T extends ProtoMessage<T>> ProtocolMessageSpec<T> spec(int id, Class<T> type,
        ProtocolMessageSpec.Parser<T> parser) {
        return new ProtocolMessageSpec<>(id, MessageDirection.CLIENT_TO_SERVER, type, 1024,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, parser, (payload, context) -> payload.close());
    }

    private record Expected(int id, MessageDirection direction, Class<?> type, int maxEncodedBytes,
        ProtocolMessageSpec.AttachmentPolicy attachmentPolicy) {
    }
}
