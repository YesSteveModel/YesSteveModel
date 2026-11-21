package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.service.ClientModelService;

import com.elfmcys.ysm.proto.network.GenPreviewStartProto;
import com.elfmcys.ysm.proto.network.MissingPreviewQueryProto;
import com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewUploadRequestProto;
import com.elfmcys.ysm.proto.network.PreviewUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.PreviewUploadFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewRepairResultProto;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapability;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.NetworkPayload;
import com.elfmcys.ysm.network.dispatch.SessionPublicationPacket;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewAvailableProto;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.proto.network.ServerHelloProto;
import com.elfmcys.ysm.proto.network.SessionDecision;
import com.elfmcys.ysm.proto.network.SessionDeltaFragmentProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.proto.network.SessionResponseProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;

/** Typed model-session handlers; game-state publication is gated onto the owner thread. */
public final class SessionProtocolHandler {
    private SessionProtocolHandler() {}

    public static void sendServerHello(ServerPlayer player) {
        if (!NetworkHandler.isPlayerChannelPresent(player)) {
            return;
        }
        var requestedSyncRoaming = !ServerConfig.LOW_BANDWIDTH_USAGE.get();
        var session = ServerModelService.instance().offerSession(player, requestedSyncRoaming);
        if (!session.pending()) {
            return;
        }
        NetworkHandler.sendToClientPlayer(ServerHelloProto.newBuilder()
            .setProtocolVersion(ProtocolVersion.TRANSPORT_VERSION).setSyncRoaming(session.syncRoaming()).build(),
            player);
    }

    public static void handleServerHello(NetworkPayload<ServerHelloProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "ServerHello");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> {
                var response = ClientSessionRuntime
                    .onServerHello(connection, message.protocolVersion(), message.syncRoaming()).orElse(null);
                if (response == null) {
                    return;
                }
                NetworkHandler.sendToServer(SessionResponseProto.newBuilder()
                    .setDecision(response.accepted() ? com.elfmcys.ysm.proto.network.SessionDecision.ACCEPT
                        : com.elfmcys.ysm.proto.network.SessionDecision.DECLINE)
                    .build());
                if (response.accepted()) {
                    ClientSessionRuntime.commitAcceptedSession(connection, message.syncRoaming());
                }
            }));
        }
    }

    public static void handleSessionResponse(NetworkPayload<SessionResponseProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "SessionResponse");
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null) {
                return;
            }
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null) {
                var message = payload.protobuf();
                context.enqueueWork(() -> service.runIfCurrent(sender, connection, owner,
                    () -> acceptSession(sender, service, owner, message)));
            }
        }
    }

    public static void handleSessionFullFragment(NetworkPayload<SessionFullFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "SessionFullFragment");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptSessionFullFragment(connection, message));
        }
    }

    public static void handleSessionDeltaFragment(NetworkPayload<SessionDeltaFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "SessionDeltaFragment");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptSessionDeltaFragment(connection, message));
        }
    }

    public static void handlePreviewAvailable(NetworkPayload<PreviewAvailableProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "PreviewAvailable");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptPreviewAvailable(connection, message));
        }
    }

    public static void handleMetadataPrefixRequest(NetworkPayload<MetadataPrefixRequestProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "MetadataPrefixRequest");
            var request = payload.protobuf();
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null) {
                return;
            }
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null) {
                context.enqueueWork(() -> service.acceptMetadataPrefixRequest(sender, connection, owner, request));
            }
        }
    }

    public static void handleMissingPreviewQuery(NetworkPayload<MissingPreviewQueryProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "MissingPreviewQuery");
            var request = payload.protobuf();
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null)
                return;
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null)
                context.enqueueWork(() -> service.acceptMissingPreviewQuery(sender, connection, owner, request));
        }
    }

    public static void handlePreviewUploadRequest(NetworkPayload<PreviewUploadRequestProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "PreviewUploadRequest");
            var request = payload.protobuf();
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null)
                return;
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null)
                context.enqueueWork(() -> service.acceptPreviewUploadRequest(sender, connection, owner, request));
        }
    }

    public static void handlePreviewUploadFragment(NetworkPayload<PreviewUploadFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var message = payload.protobuf();
        var context = contextSupplier.get();
        var sender = context.getSender();
        var connection = context.getNetworkManager();
        var service = ServerModelService.current().orElse(null);
        var owner = sender == null || service == null ? null : service.session(sender, connection).orElse(null);
        if (owner == null) {
            payload.close();
            return;
        }
        var bytes = payload.raw().orElseThrow().share();
        payload.close();
        context.enqueueWork(() -> {
            service.acceptPreviewUploadFragment(sender, connection, owner, message, bytes);
        });
    }

    public static void handleGenPreviewStart(NetworkPayload<GenPreviewStartProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "GenPreviewStart");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptGenPreviewStart(connection, message));
        }
    }

    public static void handleMissingPreviewFragment(NetworkPayload<MissingPreviewFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "MissingPreviewFragment");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptMissingPreviewFragment(connection, message));
        }
    }

    public static void handlePreviewUploadAdmission(NetworkPayload<PreviewUploadAdmissionProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "PreviewUploadAdmission");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptPreviewUploadAdmission(connection, message));
        }
    }

    public static void handlePreviewRepairResult(NetworkPayload<PreviewRepairResultProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "PreviewRepairResult");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptPreviewRepairResult(connection, message));
        }
    }

    public static void handleModelChunkRequest(NetworkPayload<ModelChunkRequestProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "ModelChunkRequest");
            var request = payload.protobuf();
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null) {
                return;
            }
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null) {
                context.enqueueWork(() -> service.acceptModelChunkRequest(sender, connection, owner, request));
            }
        }
    }

    public static void handlePresentationPageRequest(NetworkPayload<PresentationPageRequestProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "PresentationPageRequest");
            var request = payload.protobuf();
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null) {
                return;
            }
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null) {
                context.enqueueWork(() -> service.acceptPresentationPageRequest(sender, connection, owner, request));
            }
        }
    }

    public static void handleResourceTransferCancel(NetworkPayload<ResourceTransferCancelProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "ResourceTransferCancel");
            var context = contextSupplier.get();
            var sender = context.getSender();
            if (sender == null) {
                return;
            }
            var connection = context.getNetworkManager();
            var service = ServerModelService.current().orElse(null);
            var owner = service == null ? null : service.session(sender, connection).orElse(null);
            if (owner != null) {
                var transferId = payload.protobuf().dataTransferId();
                context.enqueueWork(() -> service.cancelResourceTransfer(sender, connection, owner, transferId));
            }
        }
    }

    public static void handleResourceTransferFailure(NetworkPayload<ResourceTransferFailureProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "ResourceTransferFailure");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptResourceTransferFailure(connection, message));
        }
    }

    public static void handleMetadataPrefixFragment(NetworkPayload<MetadataPrefixFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        try (payload) {
            requireNoAttachment(payload, "MetadataPrefixFragment");
            var message = payload.protobuf();
            var context = contextSupplier.get();
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.acceptMetadataPrefixFragment(connection, message));
        }
    }

    public static void handleChunkFragment(NetworkPayload<ChunkFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        var message = payload.protobuf();
        var context = contextSupplier.get();
        var connection = context.getNetworkManager();
        var data = payload.raw().orElseThrow(() -> new IllegalArgumentException("ChunkFragment requires an attachment"))
            .share();
        payload.close();
        context.enqueueWork(() -> {
            ClientSessionRuntime.acceptChunkFragment(connection, message, data);
        });
    }

    public static void handlePreviewFragment(NetworkPayload<PreviewFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        handlePresentationFragment(payload, contextSupplier, ClientSessionRuntime::acceptPreviewFragment);
    }

    public static void handleIconFragment(NetworkPayload<IconFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        handlePresentationFragment(payload, contextSupplier, ClientSessionRuntime::acceptIconFragment);
    }

    public static void handlePackCoverFragment(NetworkPayload<PackCoverFragmentProto> payload,
        Supplier<NetworkEvent.Context> contextSupplier) {
        handlePresentationFragment(payload, contextSupplier, ClientSessionRuntime::acceptPackCoverFragment);
    }

    public static void refreshGrants(ServerPlayer player) {
        refreshGrants(player, true);
    }

    public static boolean applyCommandSelection(ServerPlayer player, ModelInfoCapability persistence,
        Selection selection) {
        var service = ServerModelService.current().orElse(null);
        if (service == null)
            return false;
        var session = service.session(player).orElse(null);
        var identity = selection instanceof Selection.Model model
            ? com.elfmcys.ysm.model.domain.PublishedModelId.model(model.modelId())
            : com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT;
        var texture = selection instanceof Selection.Model model ? model.textureId() : "";
        if (session != null && session.active()) {
            if (!identity.isIntrinsicDefault() && !ServerConfig.CAN_SWITCH_MODEL.get())
                return false;
            if (session.validateSelection(selection) != ServerModelSession.SelectionResult.ACCEPTED)
                return false;
            PlayerStateHandler.sendDirective(player, identity, texture);
        } else {
            if (service.catalog().flatMap(catalog -> catalog.find(identity)).isEmpty())
                return false;
            persistence.setCommandSelection(identity, texture);
            PlayerStateHandler.publishSelection(player);
        }
        return true;
    }

    private static void refreshGrants(ServerPlayer player, boolean publish) {
        var session = ServerModelService.current().flatMap(service -> service.session(player)).orElse(null);
        if (session == null) {
            return;
        }
        var previous = session.authority();
        player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).ifPresent(capability -> {
            session.setGrants(
                capability.getAuthModels().stream().filter(session::containsModel).collect(Collectors.toSet()));
            if (publish && session.hasPublishedCatalog() && !previous.grants().equals(session.grants())) {
                sendAuthorityDelta(player, previous);
            }
            invalidateSelection(player, session, publish);
        });
    }

    private static void acceptSession(ServerPlayer player, ServerModelService service, ServerModelSession session,
        SessionResponseProto response) {
        if (response.decision() == SessionDecision.DECLINE) {
            session.decline();
            return;
        }
        if (response.decision() != SessionDecision.ACCEPT || !session.activate()) {
            return;
        }
        session.updateContentPolicy(ServerConfig.RESTRICTED_AUTH.get());
        refreshGrants(player, false);
        var serverCatalog = service.catalog().orElseThrow();
        player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(capability -> {
            capability.retireProducer(session);
            ModelSelectionService.resolve(capability, serverCatalog);
        });
        invalidateSelection(player, session, false);
        service.initializePlayerState(player);
        var catalog = SessionCollectionPublication.distributableCatalog(serverCatalog.catalog());
        final ServerModelSession.CatalogTransition transition;
        try {
            transition = session.commitCatalog(catalog);
        } catch (RuntimeException invalidPublication) {
            YesSteveModel.LOGGER.error("Failed to commit initial model-session authority for {}",
                player.getGameProfile().getName(), invalidPublication);
            return;
        }
        invalidateSelection(player, session, false);
        var selected = session.selection();
        var initial = new java.util.ArrayList<ProtoMessage<?>>();
        initial
            .addAll(SessionCollectionPublication.fullFragments(transition.current(), session.allocatePublicationId()));
        initial.add(PlayerStateHandler.directive(
            selected instanceof Selection.Model model
                ? com.elfmcys.ysm.model.domain.PublishedModelId.model(model.modelId())
                : com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT,
            selected instanceof Selection.Model model ? model.textureId() : ""));
        sendPublication(player, service, session, initial, "initial model-session publication");
    }

    public static void publishCatalog(ServerPlayer player, CatalogSnapshot globalCatalog) {
        var service = ServerModelService.current().orElse(null);
        var session = service == null ? null : service.session(player).orElse(null);
        if (service == null || session == null || !session.active() || !session.hasPublishedCatalog()) {
            return;
        }
        var requestedGrants = new HashSet<ModelId>();
        player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP)
            .ifPresent(capability -> requestedGrants.addAll(capability.getAuthModels()));
        var catalog = SessionCollectionPublication.distributableCatalog(globalCatalog);
        final ServerModelSession.CatalogTransition committed;
        try {
            committed = session.commitCatalog(catalog, requestedGrants);
        } catch (RuntimeException invalidPublication) {
            YesSteveModel.LOGGER.error("Failed to commit model-session authority for {}",
                player.getGameProfile().getName(), invalidPublication);
            return;
        }
        var transition = new ServerModelSession.CatalogTransition(committed.previous(), session.authority());
        if (SessionCollectionPublication.hasDelta(transition)) {
            var transferId = session.allocatePublicationId();
            sendPublication(player, service, session,
                SessionCollectionPublication.deltaFragments(transition, transferId), "model-session catalog delta");
        }
        invalidateSelection(player, session, true);
    }

    public static void refreshContentPolicy(ServerPlayer player) {
        var session = ServerModelService.current().flatMap(service -> service.session(player)).orElse(null);
        if (session == null || !session.active() || !session.hasPublishedCatalog())
            return;
        var previous = session.authority();
        if (session.updateContentPolicy(ServerConfig.RESTRICTED_AUTH.get())) {
            sendAuthorityDelta(player, previous);
        }
    }

    private static void sendAuthorityDelta(ServerPlayer player, ServerModelSession.AuthoritySnapshot previous) {
        var service = ServerModelService.current().orElse(null);
        var session = service == null ? null : service.session(player).orElse(null);
        if (service == null || session == null || !session.active() || !session.hasPublishedCatalog()) {
            return;
        }
        var transition = new ServerModelSession.CatalogTransition(previous, session.authority());
        if (!SessionCollectionPublication.hasDelta(transition))
            return;
        var transferId = session.allocatePublicationId();
        sendPublication(player, service, session, SessionCollectionPublication.deltaFragments(transition, transferId),
            "model-session authority delta");
    }

    private static void sendPublication(ServerPlayer player, ServerModelService service, ServerModelSession session,
        List<? extends ProtoMessage<?>> messages, String description) {
        if (messages.isEmpty()) {
            return;
        }
        SessionPublicationPacket packet = null;
        try {
            packet = new SessionPublicationPacket(messages);
            if (!service.dispatch().enqueue(session, service.transport(player, session), List.of(packet))) {
                packet.close();
                packet = null;
                YesSteveModel.LOGGER.warn("{} was rejected by dispatch admission for {}", description,
                    player.getGameProfile().getName());
            }
        } catch (RuntimeException failure) {
            if (packet != null) {
                packet.close();
            }
            YesSteveModel.LOGGER.warn("Failed to enqueue {} for {}", description, player.getGameProfile().getName(),
                failure);
        }
    }

    private static <T extends ProtoMessage<T>> void requireNoAttachment(NetworkPayload<T> payload, String messageName) {
        if (payload.raw().isPresent()) {
            throw new IllegalArgumentException(messageName + " cannot have an attachment");
        }
    }

    private static <T extends ProtoMessage<T>> void handlePresentationFragment(NetworkPayload<T> payload,
        Supplier<NetworkEvent.Context> contextSupplier, PresentationFragmentConsumer<T> consumer) {
        var message = payload.protobuf();
        var context = contextSupplier.get();
        var connection = context.getNetworkManager();
        var data =
            payload.raw().map(UniBuffer::share).orElseGet(() -> com.elfmcys.ysm.buffer.SharedArrayBuffer.allocate(0));
        payload.close();
        context.enqueueWork(() -> {
            consumer.accept(connection, message, data);
        });
    }

    private static void invalidateSelection(ServerPlayer player, ServerModelSession session, boolean publish) {
        if (session.validateSelection(session.selection()) == ServerModelSession.SelectionResult.ACCEPTED)
            return;
        player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(capability -> {
            capability.commitSelection(com.elfmcys.ysm.capability.CommittedSelection
                .appearance(com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT, ""));
            if (publish)
                PlayerStateHandler.publishSelection(player);
        });
        if (publish)
            PlayerStateHandler.send(com.elfmcys.ysm.proto.network.SelectionInvalidatedProto.newBuilder().build(),
                player);
    }

    @FunctionalInterface
    private interface PresentationFragmentConsumer<T extends ProtoMessage<T>> {
        void accept(Connection connection, T message, UniBuffer data);
    }
}
