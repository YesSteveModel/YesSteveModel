package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistry;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.dispatch.ResourceDispatchWorker;
import com.elfmcys.ysm.network.dispatch.ServerAssetTransfers;
import com.elfmcys.ysm.network.dispatch.ServerPreviewWork;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.proto.network.MissingPreviewQueryProto;
import com.elfmcys.ysm.proto.network.PreviewUploadRequestProto;
import com.elfmcys.ysm.proto.network.PreviewUploadFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewAvailableProto;
import com.elfmcys.ysm.proto.network.GenPreviewStartProto;
import com.elfmcys.ysm.network.forge.ForgeTransportPort;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Side-local owner of the server catalog, connections, and global distribution worker. */
public final class ServerModelService implements AutoCloseable {
    private static volatile ServerModelService INSTANCE;

    private final MinecraftServer server;
    private final ReloadableModelCatalog catalog;
    private final ResourceDispatchWorker dispatch;
    private final ModelExportService.Environment exports;
    private final ServerPreviewWork previewWork = new ServerPreviewWork();
    private final ReloadableModelCatalog.Subscription publicationSubscription;
    private final ServerConnectionRegistry<ConnectionState> connections = new ServerConnectionRegistry<>();
    private final AtomicReference<CatalogSnapshot> pendingPublication = new AtomicReference<>();
    private final PreviewStorage previews;
    private final ServerResourceRuntime serverResources;

    private ServerModelService(MinecraftServer server) {
        this.server = server;
        var system = ModelRuntime.system();
        catalog = system.catalog();
        previews = system.previews();
        serverResources = system.serverResources();
        dispatch = new ResourceDispatchWorker(ServerModelService::dispatchSettings);
        dispatch.start();
        exports = server.isDedicatedServer() ? null : net.minecraftforge.fml.DistExecutor.unsafeCallWhenOn(
            net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> ClientModelService.createExportEnvironment(server, () -> new ServerCatalog(catalog.current())));
        publicationSubscription = catalog.subscribe(transition -> pendingPublication.set(transition.current()));
    }

    /** Applies cross-owner catalog facts and transfer completions on the server tick. */
    public synchronized void tick() {
        if (INSTANCE != this) {
            return;
        }
        var publication = pendingPublication.getAndSet(null);
        if (publication != null) {
            for (var player : server.getPlayerList().getPlayers()) {
                try {
                    SessionProtocolHandler.publishCatalog(player, publication);
                } catch (RuntimeException failure) {
                    YesSteveModel.LOGGER.error("Failed to publish catalog to {}", player.getGameProfile().getName(),
                        failure);
                }
            }
        }
        if (exports != null)
            exports.tick();
        server.getPlayerList().getPlayers().forEach(SessionProtocolHandler::refreshContentPolicy);
        connections.forEach(state -> state.assetTransfers.tick());
    }

    public static synchronized ServerModelService start(MinecraftServer server) {
        if (INSTANCE == null) {
            INSTANCE = new ServerModelService(server);
            if (server.isDedicatedServer()) {
                INSTANCE.catalog.reload();
            }
        }
        return INSTANCE;
    }

    public static ServerModelService instance() {
        var service = INSTANCE;
        if (service == null) {
            throw new IllegalStateException("Server model service is not running");
        }
        return service;
    }

    public static Optional<ServerModelService> current() {
        return Optional.ofNullable(INSTANCE);
    }

    public Optional<ServerCatalog> catalog() {
        return Optional.of(new ServerCatalog(catalog.current()));
    }

    public synchronized ServerModelSession offerSession(ServerPlayer player, boolean syncRoaming) {
        if (INSTANCE != this) {
            throw new IllegalStateException("Server model service is not running");
        }
        var playerId = player.getUUID();
        var current = connections.current(playerId, player.connection.connection).orElse(null);
        if (current != null) {
            return current.session;
        }
        var session = new ServerModelSession(catalog::current, syncRoaming);
        var transport = new ForgeTransportPort(player);
        connections.replace(playerId, player.connection.connection, new ConnectionState(player, session, transport));
        return session;
    }

    public synchronized Optional<ServerModelSession> session(ServerPlayer player) {
        return session(player, player.connection.connection);
    }

    public synchronized Optional<ServerModelSession> session(ServerPlayer player, Connection connection) {
        return connections.current(player.getUUID(), connection).map(state -> state.session);
    }

    public synchronized boolean runIfCurrent(ServerPlayer player, ServerModelSession owner, Runnable action) {
        return runIfCurrent(player, player.connection.connection, owner, action);
    }

    public synchronized boolean runIfCurrent(ServerPlayer player, Connection connection, ServerModelSession owner,
        Runnable action) {
        var state = connections.current(player.getUUID(), connection).orElse(null);
        if (state == null || state.session != owner) {
            return false;
        }
        return connections.runIfCurrent(player.getUUID(), connection, state, action);
    }

    public synchronized void closeSession(ServerPlayer player) {
        connections.remove(player.getUUID(), player.connection.connection);
    }

    public ResourceDispatchWorker dispatch() {
        return dispatch;
    }

    public synchronized ForgeTransportPort transport(ServerPlayer player, ServerModelSession owner) {
        var state = connections.current(player.getUUID(), player.connection.connection).orElse(null);
        if (state == null || state.session != owner) {
            throw new IllegalStateException("Model session is not current");
        }
        return state.transport;
    }

    public ModelScanReport lastReport() {
        return catalog.current().report();
    }

    public synchronized void acceptMetadataPrefixRequest(ServerPlayer player, Connection connection,
        ServerModelSession owner, MetadataPrefixRequestProto request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized void acceptModelChunkRequest(ServerPlayer player, Connection connection,
        ServerModelSession owner, ModelChunkRequestProto request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized void acceptPresentationPageRequest(ServerPlayer player, Connection connection,
        ServerModelSession owner, PresentationPageRequestProto request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized boolean startGenPreview(ServerPlayer player) {
        var state = connections.current(player.getUUID(), player.connection.connection).orElse(null);
        if (INSTANCE != this || !player.hasPermissions(2) || state == null || !state.session.active()
            || !state.session.hasPublishedCatalog())
            return false;
        NetworkHandler.sendToClientPlayer(GenPreviewStartProto.newBuilder().build(), player);
        return true;
    }

    public synchronized void acceptMissingPreviewQuery(ServerPlayer player, Connection connection,
        ServerModelSession owner, MissingPreviewQueryProto query) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(query));
    }

    public synchronized void acceptPreviewUploadRequest(ServerPlayer player, Connection connection,
        ServerModelSession owner, PreviewUploadRequestProto request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized void acceptPreviewUploadFragment(ServerPlayer player, Connection connection,
        ServerModelSession owner, PreviewUploadFragmentProto fragment, UniBuffer bytes) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(fragment, bytes));
    }

    private void publishCommittedPreview(ModelFileIdentity identity) {
        server.execute(() -> {
            synchronized (ServerModelService.this) {
                if (INSTANCE != this)
                    return;
                var notification =
                    PreviewAvailableProto.newBuilder().setModelId(java.nio.ByteBuffer.wrap(identity.modelId().bytes()))
                        .setContainerId(java.nio.ByteBuffer.wrap(identity.containerId().bytes())).build();
                for (var player : server.getPlayerList().getPlayers()) {
                    var state = connections.current(player.getUUID(), player.connection.connection).orElse(null);
                    if (state == null || !state.session.active())
                        continue;
                    try {
                        NetworkHandler.sendToClientPlayer(notification, player);
                    } catch (RuntimeException failure) {
                        YesSteveModel.LOGGER.warn("Failed to notify committed preview to {}", player.getUUID(),
                            failure);
                    }
                }
            }
        });
    }

    public synchronized void cancelResourceTransfer(ServerPlayer player, Connection connection,
        ServerModelSession owner, long transferId) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.cancel(transferId));
    }

    public CompletableFuture<ReloadOutcome> reload() {
        return catalog.reload().thenApply(result -> {
            if (result.status() == ReloadStatus.COMMITTED)
                serverResources.invalidatePreviews();
            return new ReloadOutcome(result.status() == ReloadStatus.COMMITTED, result.modelCount(),
                result.errorCount(), result.warnings(), result.message());
        });
    }

    ModelExportService.Environment exportEnvironment() {
        if (exports == null)
            throw new IllegalStateException("Export requires an integrated server");
        return exports;
    }

    @Override
    public synchronized void close() {
        if (INSTANCE != this) {
            return;
        }
        INSTANCE = null;
        publicationSubscription.close();
        pendingPublication.set(null);
        RuntimeException failure = closeOwner(null, connections::close);
        if (exports != null)
            failure = closeOwner(failure, exports::close);
        failure = closeOwner(failure, previewWork::close);
        failure = closeOwner(failure, dispatch::close);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeOwner(RuntimeException failure, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private void acceptResourceRequest(ServerPlayer player, Connection connection, ServerModelSession owner,
        Predicate<ServerAssetTransfers> request) {
        var state = connections.current(player.getUUID(), connection).orElse(null);
        if (state == null || state.session != owner) {
            return;
        }
        if (!request.test(state.assetTransfers)) {
            connections.remove(player.getUUID(), connection);
        }
    }

    public record ReloadOutcome(boolean success, int modelCount, int errorCount, List<ModelScanWarning> warnings,
        String message) {
        public ReloadOutcome {
            warnings = List.copyOf(warnings);
        }

        public int warningCount() {
            return warnings.stream().mapToInt(ModelScanWarning::occurrences).sum();
        }
    }

    private static ResourceDispatchWorker.Settings dispatchSettings() {
        var megabits = ServerConfig.BANDWIDTH_LIMIT.get();
        var bytesPerSecond = megabits == 0 ? 0L : megabits * 1_000_000L / 8;
        return new ResourceDispatchWorker.Settings(ServerConfig.DISPATCH_SOFT_LIMIT.get(),
            ServerConfig.DISPATCH_HARD_LIMIT.get(), bytesPerSecond);
    }

    private final class ConnectionState implements AutoCloseable {
        private final ServerModelSession session;
        private final ForgeTransportPort transport;
        private final ServerAssetTransfers assetTransfers;
        private boolean closed;

        private ConnectionState(ServerPlayer player, ServerModelSession session, ForgeTransportPort transport) {
            this.session = session;
            this.transport = transport;
            assetTransfers = new ServerAssetTransfers(session, dispatch, transport, ServerConfig.RESTRICTED_AUTH::get,
                failure -> ServerModelService.this
                    .runIfCurrent(player, session, () -> NetworkHandler.sendToClientPlayer(failure, player)),
                serverResources, previews,
                new ServerAssetTransfers.Management(previewWork, () -> player.hasPermissions(2),
                    message -> ServerModelService.this.runIfCurrent(player, session,
                        () -> NetworkHandler.sendToClientPlayer(message, player)),
                    ServerModelService.this::publishCommittedPreview));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            assetTransfers.close();
            dispatch.disconnect(session);
            session.close();
        }
    }
}
