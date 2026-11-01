package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.proto.network.GenPreviewStartProto;
import com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.PreviewRepairResultProto;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.RemoteCatalogActivation;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.NegotiatedSessionPolicy;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewAvailableProto;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.proto.network.SessionDeltaFragmentProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.util.ThreadScheduling;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadPolicy;
import com.elfmcys.ysm.util.ThreadScheduling.ThreadRole;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * Owns the client adapter's one exact Connection and its independent model business and catalog lifetimes.
 *
 * <p><b>Execution:</b> Synchronized entry points serialize current-owner checks and replacement.
 * Callers must route domain work to the client owner thread. An exact check here does not authorize
 * an arbitrary worker to mutate Minecraft state.
 *
 * <p><b>Ownership:</b> Replacement removes current before closing transfer, publication and activation
 * owners. Late callbacks can dispose their own results but cannot borrow a replacement connection.
 * Catalog failure can retain handshake business eligibility; protocol failure retires both.
 *
 * <p><b>Seam:</b> [client connection retirement](docs/architecture/network/overview.md#连接撤销交接).
 */
public final class ClientSessionRuntime {
    private static ConnectionState current;

    private ClientSessionRuntime() {}

    /**
     * Replaces the current connection and opens its model-session owners.
     * IOException or RuntimeException from remote-store setup retires the partial owner before propagation.
     *
     * @param connection the non-null exact host connection
     * @return the new session; negotiation or content activation can still be pending
     * @throws IllegalStateException if remote-store setup fails after creation of the connection owner
     */
    public static synchronized ClientModelSession beginConnection(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        closeCurrent();

        var models = ClientModelService.instance();
        var sessionOwner = models.beginConnection();
        final ClientModelSession session;
        try {
            var requestedMode = ClientConfig.NETWORK_SESSION_MODE.get();
            session = new ClientModelSession(requestedMode, NetworkHandler.isChannelPresent(connection),
                models.catalog().catalog());
        } catch (Throwable failure) {
            models.disconnect(sessionOwner);
            throw failure;
        }
        var owner =
            new ConnectionState(connection, session, ClientSessionRuntime::sendCatalogDeltaFailureNotice, sessionOwner);
        current = owner;
        if (session.state() == ClientModelSession.State.NEGOTIATING) {
            owner.collectionPublication =
                new SessionCollectionPublication.Receiver(SessionCollectionPublication.defaultAnimations());
            owner.assetTransfer = new ClientAssetTransfer(connection, session);
            try {
                owner.activation = models.beginRemoteActivation(owner.assetTransfer, owner.assetTransfer, sessionOwner);
            } catch (IOException | RuntimeException failure) {
                closeCurrent();
                throw new IllegalStateException("Failed to open remote model store", failure);
            }
        }
        if (!Minecraft.getInstance().isLocalServer()) {
            owner.startMissingServerNotice();
        }
        return session;
    }

    public static synchronized ClientModelSession session() {
        if (current == null) {
            throw new IllegalStateException("No client model session");
        }
        return current.session;
    }

    public static synchronized Optional<ClientModelSession> currentModelSession() {
        return current == null || current.closed ? Optional.empty() : Optional.of(current.session);
    }

    static synchronized Optional<BusinessSession> businessSession() {
        return current == null ? Optional.empty() : Optional.ofNullable(current.businessSession);
    }

    public static synchronized Optional<ClientModelSession.State> state() {
        return current == null ? Optional.empty() : Optional.of(current.session.state());
    }

    public static synchronized Optional<Set<Hash256>> authoritativeGrants() {
        if (current == null || current.session.state() == ClientModelSession.State.LOCAL
            || current.session.state() == ClientModelSession.State.CLOSED) {
            return Optional.empty();
        }
        return Optional.of(current.session.remoteSnapshot().map(RemotePublicationSnapshot::grants).orElseGet(Set::of));
    }

    public static synchronized boolean runIfCurrent(Connection connection, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (current == null || current.connection != connection || current.closed) {
            return false;
        }
        action.run();
        return true;
    }

    static synchronized Optional<ClientModelSession.Response> onServerHello(Connection connection,
        String transportVersion, boolean syncRoaming) {
        var owner = owner(connection);
        if (owner == null) {
            return Optional.empty();
        }
        var response = owner.session.onServerHello(transportVersion, syncRoaming);
        if (owner.session.state() == ClientModelSession.State.INTRINSIC_DEFAULT_ONLY) {
            failProtocolSession(owner);
            return response;
        }
        return response;
    }

    static synchronized void commitAcceptedSession(Connection connection, boolean syncRoaming) {
        var owner = owner(connection);
        if (owner == null || owner.businessSession != null) {
            return;
        }
        owner.businessSession = new BusinessSession(connection, PlayerStateSessionPolicy.create(syncRoaming));
    }

    static synchronized void acceptSessionFullFragment(Connection connection, SessionFullFragmentProto message) {
        var owner = owner(connection);
        if (owner == null || owner.collectionPublication == null) {
            return;
        }
        applyCollectionResult(owner, message.transferId(),
            owner.collectionPublication.acceptFull(message, owner.session.remoteSnapshot().orElse(null)));
    }

    static synchronized void acceptSessionDeltaFragment(Connection connection, SessionDeltaFragmentProto message) {
        var owner = owner(connection);
        if (owner == null || owner.collectionPublication == null) {
            return;
        }
        applyCollectionResult(owner, message.transferId(),
            owner.collectionPublication.acceptDelta(message, owner.session.remoteSnapshot().orElse(null)));
    }

    static synchronized void acceptGenPreviewStart(Connection connection, GenPreviewStartProto message) {
        var owner = owner(connection);
        if (owner == null || owner.assetTransfer == null || owner.session.state() != ClientModelSession.State.ACTIVE)
            return;
        if (owner.previewRepair != null && !owner.previewRepair.terminal()) {
            sendCatalogDeltaFailureNotice("message.yes_steve_model.gen_preview.busy");
            return;
        }
        owner.previewRepair = new PreviewRepairBatch(owner.session, owner.assetTransfer,
            ClientModelService.instance()::managementPreview,
            item -> showPreviewMessage(Component.translatable("message.yes_steve_model.gen_preview.item", item.path(),
                Component.translatable(
                    "message.yes_steve_model.gen_preview." + item.outcome().name().toLowerCase(java.util.Locale.ROOT)),
                item.reason())),
            summary -> showPreviewMessage(Component.translatable("message.yes_steve_model.gen_preview.summary",
                summary.total(), summary.success(), summary.failed(), summary.skipped(), summary.unprocessed())),
            failure -> showPreviewMessage(Component.translatable("message.yes_steve_model.gen_preview.query_failed",
                String.valueOf(failure.getMessage()))));
    }

    private static void showPreviewMessage(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null)
            player.sendSystemMessage(message);
    }

    public static synchronized void tick() {
        var owner = current;
        if (owner == null || owner.closed)
            return;
        if (owner.assetTransfer != null) {
            var megabits = com.elfmcys.ysm.config.ServerConfig.BANDWIDTH_LIMIT.get();
            owner.assetTransfer.tickUpload(System.nanoTime(), megabits == 0 ? 0 : megabits * 1_000_000L / 8);
        }
        if (owner.previewRepair != null) {
            owner.previewRepair.tick();
            if (owner.previewRepair.terminal())
                owner.previewRepair = null;
        }
    }

    static synchronized void acceptMissingPreviewFragment(Connection connection, MissingPreviewFragmentProto message) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null)
            owner.assetTransfer.acceptMissingPreviews(message);
    }

    static synchronized void acceptPreviewUploadAdmission(Connection connection, PreviewUploadAdmissionProto message) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null)
            owner.assetTransfer.acceptUploadAdmission(message);
    }

    static synchronized void acceptPreviewRepairResult(Connection connection, PreviewRepairResultProto message) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null)
            owner.assetTransfer.acceptRepairResult(message);
    }

    static synchronized void acceptMetadataPrefixFragment(Connection connection, MetadataPrefixFragmentProto message) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null) {
            owner.assetTransfer.acceptMetadata(message);
        }
    }

    static synchronized void acceptChunkFragment(Connection connection, ChunkFragmentProto message, UniBuffer data) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null) {
            owner.assetTransfer.acceptChunk(message, data);
        }
    }

    static synchronized void acceptPreviewFragment(Connection connection, PreviewFragmentProto message,
        UniBuffer data) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null) {
            owner.assetTransfer.acceptPreview(message, data);
        }
    }

    static synchronized void acceptPreviewAvailable(Connection connection, PreviewAvailableProto message) {
        var owner = owner(connection);
        if (owner == null)
            return;
        if (message.modelId().remaining() != Hash256.SIZE || message.containerId().remaining() != Hash256.SIZE) {
            failProtocolSession(owner);
            return;
        }
        var identity = new ModelFileIdentity(new Hash256(com.elfmcys.ysm.util.ProtoBytes.copy(message.modelId())),
            new Hash256(com.elfmcys.ysm.util.ProtoBytes.copy(message.containerId())));
        owner.session.invalidatePreview(identity);
        if (owner.previewRepair != null)
            owner.previewRepair.repaired(identity);
    }

    static synchronized void acceptIconFragment(Connection connection, IconFragmentProto message, UniBuffer data) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null) {
            owner.assetTransfer.acceptIcon(message, data);
        }
    }

    static synchronized void acceptPackCoverFragment(Connection connection, PackCoverFragmentProto message,
        UniBuffer data) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null) {
            owner.assetTransfer.acceptPackCover(message, data);
        }
    }

    private static void applyCollectionResult(ConnectionState owner, long transferId,
        SessionCollectionPublication.Result result) {
        if (result.status() == SessionCollectionPublication.Status.PENDING) {
            return;
        }
        if (result.status() == SessionCollectionPublication.Status.INTRINSIC_INVALID) {
            YesSteveModel.LOGGER.warn("Rejecting intrinsically invalid model-session publication transfer={}",
                transferId, result.cause());
            failCatalogSession(owner);
            notifyCatalogDeltaFailure(owner.connection, result.status());
            return;
        }
        if (result.status() == SessionCollectionPublication.Status.BASELINE_DRIFT) {
            YesSteveModel.LOGGER.warn("Discarding model-session delta for stale baseline transfer={}", transferId,
                result.cause());
            notifyCatalogDeltaFailure(owner.connection, result.status());
            return;
        }
        try {
            if (result.status() == SessionCollectionPublication.Status.FULL) {
                owner.session.publishFull(result.publication());
            } else {
                if (result.missingRemovals() != 0) {
                    YesSteveModel.LOGGER.debug(
                        "Applied model-session delta with {} already-absent removal(s) transfer={}",
                        result.missingRemovals(), transferId);
                }
                owner.session.publishPublication(result.publication());
            }
            if (owner.activation == null) {
                throw new IllegalStateException("No remote activation owner");
            }
            activateCurrent(owner, owner.activation);
        } catch (RuntimeException invalid) {
            YesSteveModel.LOGGER.warn("Failed to publish model-session collection transfer={}", transferId, invalid);
            failCatalogSession(owner);
        }
    }

    static synchronized void notifyCatalogDeltaFailure(Connection connection,
        SessionCollectionPublication.Status status) {
        Objects.requireNonNull(status, "status");
        var owner = owner(connection);
        if (owner == null || owner.catalogDeltaFailureNotified
            || status != SessionCollectionPublication.Status.BASELINE_DRIFT
                && status != SessionCollectionPublication.Status.INTRINSIC_INVALID) {
            return;
        }
        var translationKey = switch (status) {
            case BASELINE_DRIFT -> "message.yes_steve_model.client.catalog_delta_drift";
            case INTRINSIC_INVALID -> "message.yes_steve_model.client.catalog_delta_invalid";
            default -> throw new AssertionError("handled above");
        };
        owner.catalogDeltaFailureNotified = true;
        try {
            owner.catalogDeltaFailureNotice.accept(translationKey);
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to show model-session delta failure notice", failure);
        }
    }

    private static void sendCatalogDeltaFailureNotice(String translationKey) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.translatable(translationKey));
        }
    }

    static synchronized void acceptResourceTransferFailure(Connection connection,
        ResourceTransferFailureProto failure) {
        var owner = owner(connection);
        if (owner != null && owner.assetTransfer != null) {
            owner.assetTransfer.fail(failure);
        }
    }

    private static void activateCurrent(ConnectionState connection, RemoteCatalogActivation activation) {
        var snapshot = connection.session.activationSnapshot().orElseThrow();
        ClientModelService.instance().publishSessionActivation(snapshot);
        var expectedEntries = snapshot.publication().entries();
        activation.activate(snapshot).whenComplete((result, failure) -> Minecraft.getInstance()
            .execute(() -> completeActivation(connection, activation, expectedEntries, result, failure)));
    }

    private static synchronized void completeActivation(ConnectionState connection, RemoteCatalogActivation activation,
        Map<Hash256, PublicationEntry> expectedEntries, ActivationSnapshot snapshot, Throwable failure) {
        if (current != connection || connection.closed || connection.activation != activation
            || connection.session.state() != ClientModelSession.State.ACTIVE || !connection.session.remoteSnapshot()
                .map(value -> value.entries().equals(expectedEntries)).orElse(false)) {
            return;
        }
        if (failure != null) {
            if (!superseded(failure)) {
                failCatalogSession(connection);
            }
            return;
        }
        try {
            connection.session.publishActivation(snapshot);
            ClientModelService.instance()
                .publishSessionActivation(connection.session.activationSnapshot().orElseThrow());
        } catch (RuntimeException invalid) {
            failCatalogSession(connection);
        }
    }

    static boolean superseded(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void reopenCatalog() {
        retryTransient(null);
    }

    private static void retryTransient(Hash256 modelId) {
        if (current == null || current.activation == null
            || current.session.state() != ClientModelSession.State.ACTIVE) {
            return;
        }
        var retries = current.session.retryTransient(modelId);
        if (retries.isEmpty()) {
            return;
        }
        current.activation.restart(retries);
        activateCurrent(current, current.activation);
    }

    public static synchronized RemotePresentationFetcher presentationFetcher() {
        var owner = current;
        return (members, receiver) -> fetchPresentation(owner, members, receiver);
    }

    private static synchronized CompletableFuture<Void> fetchPresentation(ConnectionState owner,
        List<RemotePresentationFetcher.Member> members, RemotePresentationFetcher.PresentationReceiver receiver) {
        if (owner == null || current != owner || owner.closed
            || owner.session.state() != ClientModelSession.State.ACTIVE || owner.assetTransfer == null) {
            return CompletableFuture
                .failedFuture(new CancellationException("Page belongs to an inactive remote model session"));
        }
        return owner.assetTransfer.fetch(members, receiver);
    }

    public static synchronized void failProtocolSession(Connection connection) {
        var owner = owner(connection);
        if (owner != null) {
            failProtocolSession(owner);
        }
    }

    static synchronized void failCatalogSession(Connection connection) {
        var owner = owner(connection);
        if (owner != null) {
            failCatalogSession(owner);
        }
    }

    /**
     * Retires only the current exact connection. A delayed disconnect for an old connection has no effect.
     *
     * @param connection the host connection identity whose owner should end
     */
    public static synchronized void disconnect(Connection connection) {
        if (current != null && current.connection == connection) {
            closeCurrent();
        }
    }

    private static ConnectionState owner(Connection connection) {
        return current != null && current.connection == connection && !current.closed ? current : null;
    }

    private static void failProtocolSession(ConnectionState owner) {
        owner.businessSession = null;
        failCatalogSession(owner);
    }

    private static void failCatalogSession(ConnectionState owner) {
        if (current != owner || owner.closed) {
            return;
        }
        owner.session.failCompleteTransfer();
        ClientModelService.current().ifPresent(ClientModelService::failRemoteCatalog);
        owner.closeCatalogOwners();
    }

    private static boolean closeCurrent() {
        var owner = current;
        current = null;
        if (owner == null) {
            return false;
        }
        try {
            owner.close();
        } finally {
            try {
                ClientProtocolGateway.resetWorld();
            } finally {
                ClientModelService.instance().disconnect(owner.sessionOwner);
            }
        }
        return true;
    }

    private static final class ConnectionState implements AutoCloseable {
        private final Connection connection;
        private final ClientModelSession session;
        private final Consumer<String> catalogDeltaFailureNotice;
        private final Object sessionOwner;
        private BusinessSession businessSession;
        private SessionCollectionPublication.Receiver collectionPublication;
        private ClientAssetTransfer assetTransfer;
        private RemoteCatalogActivation activation;
        private PreviewRepairBatch previewRepair;
        private Thread missingServerNotice;
        private boolean catalogDeltaFailureNotified;
        private volatile boolean closed;

        private ConnectionState(Connection connection, ClientModelSession session,
            Consumer<String> catalogDeltaFailureNotice, Object sessionOwner) {
            this.connection = connection;
            this.session = session;
            this.catalogDeltaFailureNotice =
                Objects.requireNonNull(catalogDeltaFailureNotice, "catalogDeltaFailureNotice");
            this.sessionOwner = Objects.requireNonNull(sessionOwner, "sessionOwner");
        }

        private void startMissingServerNotice() {
            missingServerNotice =
                new Thread(ThreadScheduling.entry(ThreadRole.MISSING_SERVER_NOTICE, ThreadPolicy.BELOW_NORMAL, () -> {
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (closed) {
                        return;
                    }
                    Minecraft.getInstance().execute(() -> runIfCurrent(connection, () -> {
                        var player = Minecraft.getInstance().player;
                        if (player != null && player.connection.isAcceptingMessages()
                            && session.state() == ClientModelSession.State.LOCAL
                            && !NetworkHandler.isChannelPresent(connection)) {
                            player.sendSystemMessage(
                                Component.translatable("message.yes_steve_model.client.server_not_found"));
                        }
                    }));
                }), "YSM Missing Server Notice");
            missingServerNotice.setDaemon(true);
            missingServerNotice.start();
        }

        private void closeMissingServerNotice() {
            var producer = missingServerNotice;
            missingServerNotice = null;
            if (producer == null) {
                return;
            }
            producer.interrupt();
            var interrupted = false;
            while (producer.isAlive()) {
                try {
                    producer.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void closeCatalogOwners() {
            if (previewRepair != null) {
                previewRepair.close();
                previewRepair = null;
            }
            var transfer = assetTransfer;
            var owner = activation;
            var publication = collectionPublication;
            assetTransfer = null;
            activation = null;
            collectionPublication = null;
            if (transfer != null) {
                transfer.close();
            }
            if (owner != null) {
                owner.close();
            }
            if (publication != null) {
                publication.clear();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            businessSession = null;
            try {
                closeMissingServerNotice();
            } finally {
                try {
                    closeCatalogOwners();
                } finally {
                    session.close();
                }
            }
        }
    }

    record BusinessSession(Connection connection, NegotiatedSessionPolicy policy) {
        BusinessSession {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(policy, "policy");
        }
    }
}
