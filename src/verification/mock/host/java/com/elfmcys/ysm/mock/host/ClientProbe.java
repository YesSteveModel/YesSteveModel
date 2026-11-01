package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.proto.network.SelectModelRequestProto;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public final class ClientProbe {
    private final HostIo io;
    private final String address;
    private Minecraft minecraft;
    private HostAction action;
    private PendingAction pending;
    private Connection currentConnection;
    private Connection retiredConnection;
    private int connectionOrdinal;
    private int ticks;
    private int readyAt;
    private int reconnectAt;
    private int connectAttempts;
    private long connectStartedAtNanos;
    private boolean ready;
    private boolean connecting;
    private boolean reconnectRequested;
    private boolean stopAfterAck;

    public ClientProbe(HostIo io) {
        this.io = io;
        address = requiredEnvironment("YSM_MOCK_SERVER_ADDRESS");
        EntityPresentationProbe.register();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        minecraft = Minecraft.getInstance();
        ticks++;
        try {
            publishReady();
            connectWhenNeeded();
            advance();
        } catch (Throwable failure) {
            failAction(failure);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || action == null || !action.name().equals("entity-reload"))
            return;
        try {
            advance();
        } catch (Throwable failure) {
            failAction(failure);
        }
    }

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        currentConnection = event.getConnection();
        connectionOrdinal++;
        connecting = false;
        reconnectRequested = false;
        try {
            io.event("connection-opened", null,
                Map.of("attempts", connectAttempts, "connection", connectionId(currentConnection), "ordinal",
                    connectionOrdinal, "ownerThread", Minecraft.getInstance().isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record client login", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        var connection = event.getConnection();
        if (connection == null) {
            return;
        }
        retiredConnection = connection;
        currentConnection = null;
        connecting = false;
        if (reconnectRequested) {
            reconnectAt = ticks + 20;
        }
        try {
            io.event("connection-closing", null,
                Map.of("connection", connection == null ? "none" : connectionId(connection), "capturedOldConnection",
                    connection != null, "ownerThread", Minecraft.getInstance().isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record client logout", failure);
        }
    }

    private void publishReady() throws IOException {
        if (ready || !YesSteveModel.isAvailable() || ClientModelService.current().isEmpty()) {
            return;
        }
        ready = true;
        readyAt = ticks;
        io.ready(Map.of("available", true, "clientThread", minecraft.isSameThread(), "renderThread",
            RenderSystem.isOnRenderThread()));
    }

    private void connectWhenNeeded() throws IOException {
        if (address.equals("integrated"))
            return;
        if (!ready || minecraft.getConnection() != null) {
            return;
        }
        if (connecting) {
            if (System.nanoTime() - connectStartedAtNanos < TimeUnit.SECONDS.toNanos(40)) {
                return;
            }
            connecting = false;
            io.event("connection-retry", null, Map.of("attempt", connectAttempts + 1, "reason", "pre-login-timeout",
                "screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName()));
        }
        if (connectionOrdinal == 0 && ticks - readyAt < 40) {
            return;
        }
        if (connectionOrdinal > 0 && (!reconnectRequested || ticks < reconnectAt)) {
            return;
        }
        connecting = true;
        connectAttempts++;
        connectStartedAtNanos = System.nanoTime();
        var serverAddress = ServerAddress.parseString(address);
        var serverData = new ServerData("YSM mock host", address, false);
        ConnectScreen.startConnecting(minecraft.screen, minecraft, serverAddress, serverData, false);
    }

    private void advance() throws Exception {
        if (!ready) {
            return;
        }
        if (pending != null) {
            var result = pending.poll();
            if (result != null) {
                io.complete(action, result);
                action = null;
                pending = null;
                if (stopAfterAck) {
                    minecraft.stop();
                }
            }
            return;
        }
        action = io.nextAction();
        if (action == null) {
            return;
        }
        io.event("action-started", action.id(), Map.of("name", action.name()));
        pending = start(action);
    }

    private PendingAction start(HostAction next) throws Exception {
        return switch (next.name()) {
            case "await-state" -> () -> awaitState(next.require("state"));
            case "snapshot" -> () -> snapshot();
            case "catalog-snapshot", "catalog-local-reload", "catalog-leases-acquire", "catalog-leases-use",
                "catalog-lease-release-first", "catalog-leases-close", "catalog-disconnect", "catalog-repeat-integrated" -> {
                var probe = new CatalogLinksClientProbe(next);
                yield probe::poll;
            }
            case "preview-read", "preview-state", "preview-runtime", "client-command", "preview-cost-start",
                "preview-cost", "preview-warm", "preview-release-warm", "preview-screen", "integrated-start",
                "integrated-command", "integrated-status" -> {
                var probe = new PreviewClientProbe(minecraft, next);
                yield probe::poll;
            }
            case "await-path" -> () -> awaitPath(next.require("path"));
            case "page-pack-cover" -> new PagePackCover(next.require("hierarchy"));
            case "select-path" -> () -> selectPath(next.require("path"));
            case "invalid-select" -> delayedConnectionCheck(() -> {
                NetworkHandler.sendToServer(SelectModelRequestProto.newBuilder().setTextureId("").build());
                return ClientModelService.instance().catalog().models().size();
            }, "invalidSelection");
            case "disconnect-reconnect" -> reconnect();
            case "late-old-owner" -> () -> lateOldOwner();
            case "set-slot" -> () -> setSlot(Integer.parseInt(next.require("slot")));
            case "molang-output" -> {
                var probe = new MolangClientProbe(minecraft, next.require("screenshot"));
                yield probe::poll;
            }
            case "molang-failure" -> {
                var probe = new MolangFailureProbe(minecraft, next.require("kind"));
                yield probe::poll;
            }
            case "entity-presentation" -> {
                var probe = new EntityPresentationProbe(minecraft, next);
                yield probe::poll;
            }
            case "entity-pending" -> {
                var probe = new EntityPendingProbe(minecraft, next);
                yield probe::poll;
            }
            case "catalog-progress" -> {
                var probe = new CatalogProgressPageProbe(minecraft, next);
                yield probe::poll;
            }
            case "entity-gui-purpose" -> {
                var probe = new GuiPurposePageProbe(minecraft, next);
                yield probe::poll;
            }
            case "entity-gui-owner" -> {
                var probe = new GuiOwnerProbe(minecraft, next);
                yield probe::poll;
            }
            case "entity-scoped" -> {
                var probe = new ScopedOwnerProbe(minecraft, next);
                yield probe::poll;
            }
            case "entity-optional-arms" -> {
                var probe = new OptionalArmProbe(minecraft);
                yield probe::poll;
            }
            case "entity-reload" -> {
                var reload = minecraft.reloadResourcePacks();
                yield () -> {
                    if (!reload.isDone())
                        return null;
                    reload.join();
                    return Map.of("resourceReloadCompleted", true, "minecraftConnected",
                        minecraft.getConnection() != null);
                };
            }
            case "entity-timeline" -> () -> EntityLifecycleProbe.timeline(minecraft);
            case "entity-worker" -> () -> EntityLifecycleProbe.worker(minecraft);
            case "entity-ownership" -> OwnershipHostObservation::poll;
            case "entity-page-close", "entity-undrawn-retire" -> {
                var probe = new OwnershipLifecycleHostProbe(minecraft, next.name().equals("entity-page-close"));
                yield probe::poll;
            }
            case "entity-audio" -> {
                var probe = new EntityAudioProbe(minecraft);
                yield probe::poll;
            }
            case "stop" -> () -> {
                stopAfterAck = true;
                return Map.of("normalStopRequested", true, "ownerThread", minecraft.isSameThread());
            };
            default -> throw new IllegalArgumentException("Unknown client action: " + next.name());
        };
    }

    private Map<String, ?> awaitState(String expected) {
        var state = ClientSessionRuntime.state().orElse(null);
        if (state == null || !state.name().equals(expected)) {
            return null;
        }
        return Map.of("connection", connectionId(currentConnection), "connectionOrdinal", connectionOrdinal,
            "minecraftConnected", minecraft.getConnection() != null, "state", state.name());
    }

    private Map<String, ?> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        var service = ClientModelService.instance();
        result.put("catalogCount", service.catalog().models().size());
        result.put("connection", currentConnection == null ? "none" : connectionId(currentConnection));
        result.put("connectionOrdinal", connectionOrdinal);
        result.put("minecraftConnected", minecraft.getConnection() != null);
        result.put("paths",
            service.catalog().models().values().stream().map(entry -> entry.displayPath()).sorted().toList());
        result.put("sessionState", ClientSessionRuntime.state().map(Enum::name).orElse("NONE"));
        return result;
    }

    private Map<String, ?> awaitPath(String path) {
        var service = ClientModelService.instance();
        var modelId = service.resolvePath(path).orElse(null);
        if (modelId == null) {
            return null;
        }
        var activation = ClientSessionRuntime.session().activationSnapshot().orElse(null);
        if (activation == null || !(activation.entries().get(modelId) instanceof ActivationSnapshot.Ready)) {
            return null;
        }
        var entry = service.catalog().find(modelId).orElseThrow();
        return Map.of("containerId", entry.displayRepresentation().containerId().toString(), "modelId",
            modelId.toString(), "origin", entry.origin().name(), "path", path);
    }

    private Map<String, ?> selectPath(String path) {
        var service = ClientModelService.instance();
        var modelId = service.resolvePath(path).orElse(null);
        if (modelId == null || currentConnection == null) {
            return null;
        }
        var entry = service.catalog().find(modelId).orElse(null);
        if (entry == null) {
            return null;
        }
        var texture =
            entry.displayRepresentation().view().getPlayer().getTextureNames().stream().findFirst().orElse("");
        ClientProtocolGateway.selectModel(modelId, texture);
        return Map.of("connection", connectionId(currentConnection), "modelId", modelId.toString(), "networkPath", true,
            "path", path, "texture", texture);
    }

    private PendingAction delayedConnectionCheck(IntSupplier starter, String classification) {
        var beforeConnection = currentConnection;
        var beforeCatalog = starter.getAsInt();
        var readyAt = ticks + 20;
        return () -> {
            if (ticks < readyAt) {
                return null;
            }
            var afterCatalog = ClientModelService.instance().catalog().models().size();
            if (currentConnection != beforeConnection || minecraft.getConnection() == null
                || beforeCatalog != afterCatalog) {
                throw new IllegalStateException("Ordinary YSM failure changed connection or current catalog");
            }
            return Map.of("catalogUnchanged", true, "classification", classification, "connection",
                connectionId(currentConnection), "minecraftConnected", true);
        };
    }

    private PendingAction reconnect() throws Exception {
        if (currentConnection == null || minecraft.getConnection() == null) {
            throw new IllegalStateException("Reconnect requires an active Minecraft connection");
        }
        var old = currentConnection;
        var ordinal = connectionOrdinal;
        var previousOwner = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        previousOwner.waitForAsyncUpdate();
        var previousLease = EntityUseProbe.watch(previousOwner);
        var renderer = com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
        var armField = com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer.class.getDeclaredField("armEntity");
        armField.setAccessible(true);
        var previousArm = (com.elfmcys.ysm.client.entity.AnimatableEntity<?>)armField.get(renderer);
        var previousArmLease = previousArm == null ? null : EntityUseProbe.watch(previousArm);
        reconnectRequested = true;
        old.disconnect(Component.literal("YSM mock reconnect"));
        return () -> {
            if (connectionOrdinal <= ordinal || currentConnection == null || currentConnection == old
                || ClientSessionRuntime.state().orElse(null) != ClientModelSession.State.ACTIVE) {
                return null;
            }
            if (previousOwner.isAssigned() || previousOwner.getExpressionRuntime().module().isPresent())
                throw new AssertionError("World unload retained its old player owner");
            if (previousLease != null && !previousLease.snapshot().get("leaseCloses").equals(1))
                throw new AssertionError("World unload did not close the old player lease once");
            if (previousArm != null && (previousArm.isAssigned()
                || previousArm.getExpressionRuntime().module().isPresent()))
                throw new AssertionError("Player replacement retained the old arm owner");
            if (previousArmLease != null && !previousArmLease.snapshot().get("leaseCloses").equals(1))
                throw new AssertionError("Player replacement did not close the old arm lease once");
            return Map.of("currentConnection", connectionId(currentConnection), "currentOrdinal", connectionOrdinal,
                "oldConnection", connectionId(old), "sameProcess", true,
                "oldPlayerAssigned", false, "oldPlayerRuntimeInstalled", false,
                "oldPlayerLease", previousLease == null ? Map.of() : previousLease.snapshot(),
                "oldArmObserved", previousArm != null,
                "oldArmLease", previousArmLease == null ? Map.of() : previousArmLease.snapshot());
        };
    }

    private Map<String, ?> lateOldOwner() {
        if (retiredConnection == null || currentConnection == null || retiredConnection == currentConnection) {
            return null;
        }
        var before = ClientModelService.instance().catalog().models().keySet();
        var effect = new boolean[1];
        var accepted = ClientSessionRuntime.runIfCurrent(retiredConnection, () -> effect[0] = true);
        var after = ClientModelService.instance().catalog().models().keySet();
        if (accepted || effect[0] || !before.equals(after)) {
            throw new IllegalStateException("Retired client owner produced a current effect");
        }
        return Map.of("currentConnection", connectionId(currentConnection), "oldConnection",
            connectionId(retiredConnection), "oldGateAccepted", false, "publicationUnchanged", true);
    }

    private Map<String, ?> setSlot(int slot) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return null;
        }
        minecraft.player.getInventory().selected = slot;
        minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        return Map.of("connection", connectionId(currentConnection), "networkPath", "vanilla-client-to-server",
            "selectedSlot", slot);
    }

    private void failAction(Throwable failure) {
        if (action == null) {
            throw failure instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("Client probe failed", failure);
        }
        try {
            io.fail(action, failure);
        } catch (IOException writeFailure) {
            failure.addSuppressed(writeFailure);
            throw new IllegalStateException("Client action failed without an acknowledgement", failure);
        } finally {
            action = null;
            pending = null;
        }
    }

    private static String connectionId(Connection connection) {
        if (connection == null) {
            throw new IllegalStateException("No exact client connection");
        }
        return Integer.toUnsignedString(System.identityHashCode(connection));
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface PendingAction {
        Map<String, ?> poll() throws Exception;
    }

    private final class PagePackCover implements PendingAction {
        private final String hierarchy;
        private ClientAssetBatch batch;
        private CompletableFuture<ImageSource> cover;

        private PagePackCover(String hierarchy) {
            this.hierarchy = hierarchy;
        }

        @Override
        public Map<String, ?> poll() {
            if (batch == null) {
                var service = ClientModelService.instance();
                var pack =
                    service.catalog().packs().stream().filter(candidate -> candidate.hierarchy().equals(hierarchy)
                        && candidate.rootKind() == CatalogRootKind.CUSTOM).findFirst().orElse(null);
                if (pack == null) {
                    return null;
                }
                batch = service.createAssetBatch();
                cover = batch.packCover(pack);
                batch.submit();
            }
            if (!cover.isDone()) {
                return null;
            }
            try {
                var source = cover.join();
                var texture = ClientModelService.instance().createTexture(source);
                texture.load(minecraft.getResourceManager());
                var failure = texture.failure().orElse(null);
                texture.close();
                if (failure != null) {
                    throw new IllegalStateException("Preview texture publication failed", failure);
                }
                return Map.of("batchClosed", true, "hierarchy", hierarchy, "nativeImageDecode", true, "pageAsset",
                    "remote-pack-cover", "renderThread", RenderSystem.isOnRenderThread(), "texturePublished", true,
                    "textureReleased", true);
            } catch (CompletionException failure) {
                throw new IllegalStateException("Page pack cover failed", failure.getCause());
            } finally {
                batch.close();
                batch = null;
                cover = null;
            }
        }
    }
}
