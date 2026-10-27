package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.GenPreviewStartProto;
import com.elfmcys.ysm.proto.network.MissingPreviewQueryProto;
import com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewUploadRequestProto;
import com.elfmcys.ysm.proto.network.PreviewUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.PreviewUploadFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewRepairResultProto;
import com.elfmcys.ysm.network.NetworkPayload;
import com.elfmcys.ysm.network.forge.ControlHandler;
import com.elfmcys.ysm.network.forge.MinecraftStateHandler;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
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
import com.elfmcys.ysm.proto.network.PreviewAvailableProto;
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
import net.minecraftforge.network.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Stable ids and the only parser/handler registry for the current wire. */
public final class ProtocolMessages {
    public static final int SERVER_HELLO_ID = 0;
    public static final int SESSION_RESPONSE_ID = 1;
    public static final int SESSION_FULL_FRAGMENT_ID = 2;
    public static final int SESSION_DELTA_FRAGMENT_ID = 3;
    public static final int METADATA_PREFIX_REQUEST_ID = 4;
    public static final int MODEL_CHUNK_REQUEST_ID = 5;
    public static final int PRESENTATION_PAGE_REQUEST_ID = 6;
    public static final int RESOURCE_TRANSFER_CANCEL_ID = 7;
    public static final int RESOURCE_TRANSFER_FAILURE_ID = 8;
    public static final int SELECT_MODEL_REQUEST_ID = 9;
    public static final int SELECT_MODEL_RESULT_ID = 10;
    public static final int METADATA_PREFIX_FRAGMENT_ID = 11;
    public static final int CHUNK_FRAGMENT_ID = 12;
    public static final int PREVIEW_FRAGMENT_ID = 13;
    public static final int ICON_FRAGMENT_ID = 14;
    public static final int PACK_COVER_FRAGMENT_ID = 15;

    public static final int PLAYER_STATE_REPORT_ID = 16;
    public static final int PLAYER_STATE_UPDATE_ID = 17;
    public static final int STARRED_MODELS_SNAPSHOT_ID = 18;
    public static final int UPDATE_STARRED_MODEL_REQUEST_ID = 19;
    public static final int PRIVILEGED_TRANSIENT_EVENT_ID = 21;
    public static final int SUBMIT_CONFIG_ACTION_REQUEST_ID = 22;
    public static final int EMIT_MOLANG_SYNC_ID = 23;
    public static final int MOLANG_SYNC_EVENT_ID = 24;
    public static final int SWING_HAND_REQUEST_ID = 25;
    public static final int PROJECTILE_MODEL_STATE_ID = 26;
    public static final int VEHICLE_MODEL_STATE_ID = 27;
    public static final int CONFIG_ACTION_EVENT_ID = 28;
    public static final int PREVIEW_AVAILABLE_ID = 29;

    public static final int GEN_PREVIEW_START_ID = 30;

    public static final int MISSING_PREVIEW_QUERY_ID = 31;

    public static final int MISSING_PREVIEW_FRAGMENT_ID = 32;

    public static final int PREVIEW_UPLOAD_REQUEST_ID = 33;

    public static final int PREVIEW_UPLOAD_ADMISSION_ID = 34;

    public static final int PREVIEW_UPLOAD_FRAGMENT_ID = 35;

    public static final int PREVIEW_REPAIR_RESULT_ID = 36;

    // Handler lambdas defer mixed-side target-class resolution until direction validation;
    // direct method references make the JVM resolve client-only types on dedicated servers.
    public static final ProtocolMessageRegistry REGISTRY = ProtocolMessageRegistry.builder()
        .add(new ProtocolMessageSpec<>(PREVIEW_REPAIR_RESULT_ID, MessageDirection.SERVER_TO_CLIENT,
            PreviewRepairResultProto.class, 64, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            PreviewRepairResultProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePreviewRepairResult(payload, context)))
        .add(new ProtocolMessageSpec<>(PREVIEW_UPLOAD_FRAGMENT_ID, MessageDirection.CLIENT_TO_SERVER,
            PreviewUploadFragmentProto.class, 64, ProtocolMessageSpec.AttachmentPolicy.REQUIRED,
            PreviewUploadFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePreviewUploadFragment(payload, context)))
        .add(new ProtocolMessageSpec<>(PREVIEW_UPLOAD_ADMISSION_ID, MessageDirection.SERVER_TO_CLIENT,
            PreviewUploadAdmissionProto.class, 32, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            PreviewUploadAdmissionProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePreviewUploadAdmission(payload, context)))
        .add(new ProtocolMessageSpec<>(PREVIEW_UPLOAD_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            PreviewUploadRequestProto.class, 128, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            PreviewUploadRequestProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePreviewUploadRequest(payload, context)))
        .add(new ProtocolMessageSpec<>(MISSING_PREVIEW_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
            MissingPreviewFragmentProto.class, 30720, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            MissingPreviewFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleMissingPreviewFragment(payload, context)))
        .add(new ProtocolMessageSpec<>(MISSING_PREVIEW_QUERY_ID, MessageDirection.CLIENT_TO_SERVER,
            MissingPreviewQueryProto.class, 32, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            MissingPreviewQueryProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleMissingPreviewQuery(payload, context)))
        .add(new ProtocolMessageSpec<>(GEN_PREVIEW_START_ID, MessageDirection.SERVER_TO_CLIENT,
            GenPreviewStartProto.class, 16, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            GenPreviewStartProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleGenPreviewStart(payload, context)))
        .add(new ProtocolMessageSpec<>(PREVIEW_AVAILABLE_ID, MessageDirection.SERVER_TO_CLIENT,
            PreviewAvailableProto.class, 128, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            PreviewAvailableProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePreviewAvailable(payload, context)))
        .add(spec(SERVER_HELLO_ID, MessageDirection.SERVER_TO_CLIENT, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            ServerHelloProto.class, ServerHelloProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleServerHello(payload, context)))
        .add(spec(SESSION_RESPONSE_ID, MessageDirection.CLIENT_TO_SERVER,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, SessionResponseProto.class, SessionResponseProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleSessionResponse(payload, context)))
        .add(spec(SESSION_FULL_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, SessionFullFragmentProto.class,
            SessionFullFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleSessionFullFragment(payload, context)))
        .add(spec(SESSION_DELTA_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, SessionDeltaFragmentProto.class,
            SessionDeltaFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleSessionDeltaFragment(payload, context)))
        .add(spec(METADATA_PREFIX_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, MetadataPrefixRequestProto.class,
            MetadataPrefixRequestProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleMetadataPrefixRequest(payload, context)))
        .add(spec(MODEL_CHUNK_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, ModelChunkRequestProto.class,
            ModelChunkRequestProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleModelChunkRequest(payload, context)))
        .add(spec(PRESENTATION_PAGE_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, PresentationPageRequestProto.class,
            PresentationPageRequestProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePresentationPageRequest(payload, context)))
        .add(spec(RESOURCE_TRANSFER_CANCEL_ID, MessageDirection.CLIENT_TO_SERVER,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, ResourceTransferCancelProto.class,
            ResourceTransferCancelProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleResourceTransferCancel(payload, context)))
        .add(spec(RESOURCE_TRANSFER_FAILURE_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, ResourceTransferFailureProto.class,
            ResourceTransferFailureProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleResourceTransferFailure(payload, context)))
        .add(spec(SELECT_MODEL_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, SelectModelRequestProto.class,
            SelectModelRequestProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleSelectModelRequest(payload, context)))
        .add(spec(SELECT_MODEL_RESULT_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, SelectModelResultProto.class,
            SelectModelResultProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleSelectModelResult(payload, context)))
        .add(spec(METADATA_PREFIX_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN, MetadataPrefixFragmentProto.class,
            MetadataPrefixFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleMetadataPrefixFragment(payload, context)))
        .add(spec(CHUNK_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT, ProtocolMessageSpec.AttachmentPolicy.REQUIRED,
            ChunkFragmentProto.class, ChunkFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleChunkFragment(payload, context)))
        .add(spec(PREVIEW_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL, PreviewFragmentProto.class,
            PreviewFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePreviewFragment(payload, context)))
        .add(spec(ICON_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT, ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL,
            IconFragmentProto.class, IconFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handleIconFragment(payload, context)))
        .add(spec(PACK_COVER_FRAGMENT_ID, MessageDirection.SERVER_TO_CLIENT,
            ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL, PackCoverFragmentProto.class,
            PackCoverFragmentProto::parseFrom,
            (payload, context) -> SessionProtocolHandler.handlePackCoverFragment(payload, context)))
        .add(proto(PLAYER_STATE_REPORT_ID, MessageDirection.CLIENT_TO_SERVER, PlayerStateReportProto.class,
            ProtocolLimits.MAX_PLAYER_STATE_BYTES, PlayerStateReportProto::parseFrom,
            (message, context) -> PlayerStateHandler.handleReport(message, context)))
        .add(proto(PLAYER_STATE_UPDATE_ID, MessageDirection.SERVER_TO_CLIENT, PlayerStateUpdateProto.class,
            ProtocolLimits.MAX_PLAYER_STATE_BYTES, PlayerStateUpdateProto::parseFrom,
            (message, context) -> PlayerStateHandler.handleUpdate(message, context)))
        .add(proto(STARRED_MODELS_SNAPSHOT_ID, MessageDirection.SERVER_TO_CLIENT, StarredModelsSnapshotProto.class,
            ProtocolLimits.MAX_MODEL_SET_BYTES, StarredModelsSnapshotProto::parseFrom,
            (message, context) -> ControlHandler.handleStarredModels(message, context)))
        .add(proto(UPDATE_STARRED_MODEL_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            UpdateStarredModelRequestProto.class, ProtocolLimits.MAX_STAR_UPDATE_BYTES,
            UpdateStarredModelRequestProto::parseFrom,
            (message, context) -> ControlHandler.handleUpdateStar(message, context)))
        .add(
            proto(PRIVILEGED_TRANSIENT_EVENT_ID, MessageDirection.SERVER_TO_CLIENT, PrivilegedTransientEventProto.class,
                ProtocolLimits.MAX_TRANSIENT_EVENT_BYTES, PrivilegedTransientEventProto::parseFrom,
                (message, context) -> ControlHandler.handlePrivilegedTransient(message, context)))
        .add(proto(SUBMIT_CONFIG_ACTION_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER,
            SubmitConfigActionRequestProto.class, ProtocolLimits.MAX_ENTITY_ACTION_BYTES,
            SubmitConfigActionRequestProto::parseFrom,
            (message, context) -> ControlHandler.handleSubmitConfigAction(message, context)))
        .add(proto(EMIT_MOLANG_SYNC_ID, MessageDirection.CLIENT_TO_SERVER, EmitMolangSyncProto.class,
            ProtocolLimits.MAX_STAR_UPDATE_BYTES, EmitMolangSyncProto::parseFrom,
            (message, context) -> ControlHandler.handleEmitMolangSync(message, context)))
        .add(proto(MOLANG_SYNC_EVENT_ID, MessageDirection.SERVER_TO_CLIENT, MolangSyncEventProto.class,
            ProtocolLimits.MAX_MOLANG_SYNC_BYTES, MolangSyncEventProto::parseFrom,
            (message, context) -> ControlHandler.handleMolangSync(message, context)))
        .add(proto(SWING_HAND_REQUEST_ID, MessageDirection.CLIENT_TO_SERVER, SwingHandRequestProto.class,
            ProtocolLimits.MAX_SWING_HAND_BYTES, SwingHandRequestProto::parseFrom,
            (message, context) -> ControlHandler.handleSwingHand(message, context)))
        .add(proto(PROJECTILE_MODEL_STATE_ID, MessageDirection.SERVER_TO_CLIENT, ProjectileModelStateProto.class,
            ProtocolLimits.MAX_MINECRAFT_STATE_BYTES, ProjectileModelStateProto::parseFrom,
            (message, context) -> MinecraftStateHandler.handleProjectile(message, context)))
        .add(proto(VEHICLE_MODEL_STATE_ID, MessageDirection.SERVER_TO_CLIENT, VehicleModelStateProto.class,
            ProtocolLimits.MAX_MINECRAFT_STATE_BYTES, VehicleModelStateProto::parseFrom,
            (message, context) -> MinecraftStateHandler.handleVehicle(message, context)))
        .add(proto(CONFIG_ACTION_EVENT_ID, MessageDirection.SERVER_TO_CLIENT, ConfigActionEventProto.class,
            ProtocolLimits.MAX_ENTITY_ACTION_BYTES, ConfigActionEventProto::parseFrom,
            (message, context) -> ControlHandler.handleConfigAction(message, context)))
        .build();

    private ProtocolMessages() {}

    private static <T extends ProtoMessage<T>> ProtocolMessageSpec<T> spec(int id, MessageDirection direction,
        ProtocolMessageSpec.AttachmentPolicy attachmentPolicy, Class<T> type, ProtocolMessageSpec.Parser<T> parser,
        ProtocolMessageSpec.Handler<T> handler) {
        return new ProtocolMessageSpec<>(id, direction, type, FrameCodec.MAX_DECODED_PROTO_BYTES, attachmentPolicy,
            parser, handler);
    }

    private static <T extends ProtoMessage<T>> ProtocolMessageSpec<T> proto(int id, MessageDirection direction,
        Class<T> type, int maxBytes, ProtocolMessageSpec.Parser<T> parser,
        BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        return new ProtocolMessageSpec<>(id, direction, type, maxBytes, ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN,
            parser, (payload, context) -> handleProto(payload, context, handler));
    }

    private static <T extends ProtoMessage<T>> void handleProto(NetworkPayload<T> payload,
        Supplier<NetworkEvent.Context> context, BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        try (payload) {
            if (payload.raw().isPresent()) {
                throw new IllegalArgumentException(
                    "Raw attachment is not valid for " + payload.protobuf().getClass().getName());
            }
            handler.accept(payload.protobuf(), context);
        }
    }
}
