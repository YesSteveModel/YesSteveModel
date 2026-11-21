package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.PlayerPreferenceSession;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.PlayerPreferences;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import com.elfmcys.ysm.network.protocol.RoamingDictionary;
import com.elfmcys.ysm.model.storage.StorageQuery;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-thread composition of UUID preferences, selection authority and the sole local producer.
 *
 * <p>
 * Ownership: service close seals this owner before the shared storage buffer drains. Every asynchronous adoption
 * captures the UUID, authority incarnation and operation identity. Active remote selections and their preparation
 * belong to the demand coordinator.
 *
 * <p>
 * Seam: implements [client preference restoration](docs/architecture/network/player-state.md#表现报告与真实基线).
 */
public final class ClientPlayerPreferences implements AutoCloseable {
    private final ClientModelService models;
    private final PlayerPreferences store;
    private final PlayerPreferenceSession roaming;
    private final Set<ModelId> favourites = new HashSet<>();
    private UUID playerId;
    private Object authority;
    private Object binding;
    private boolean local;
    private boolean closed;
    private long localWindow;
    private PlayerPreferences.LocalSelection rememberedLocal;
    private PlayerPreferences.LocalSelection localTarget;
    private PlayerAnimatableCapability appliedPlayer;
    private StorageQuery<PlayerPreferences.Favourite> favouritePage;
    private CurrentSelection currentSelection;

    public record CurrentSelection(ClientDemandCoordinator.Target target, long intentId, RoamingDictionary dictionary,
        Object operation) {
    }

    public CurrentSelection currentSelection() {
        return currentSelection;
    }

    public CurrentSelection selection() {
        bind();
        return currentSelection;
    }

    public boolean canSwitchModel() {
        return ClientSessionRuntime.canSwitchModel();
    }

    public ClientPlayerPreferences(ClientModelService models, ModelStorageInfra storage) {
        this.models = models;
        store = storage.preferences();
        roaming = new PlayerPreferenceSession(store, storage.writes(), ClientPlayerPreferences::execute, this::failure,
            this::readFailure);
    }

    private static void execute(Runnable task) {
        Minecraft.getInstance().tell(task);
    }

    public PlayerPreferenceSession roaming() {
        return roaming;
    }

    public void tick() {
        bind();
        roaming.tick();
        readFavourites();
        if (currentSelection != null)
            applyCurrent();
        else if (local && rememberedLocal != null)
            applyLocal(rememberedLocal);
    }

    private void bind() {
        if (closed)
            return;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var state = ClientSessionRuntime.state().orElse(ClientModelSession.State.LOCAL);
        var business = ClientSessionRuntime.businessSession().orElse(null);
        var nextLocal = state == ClientModelSession.State.LOCAL;
        var nextAuthority = player == null ? null : nextLocal ? minecraft.level : business;
        var nextId = player == null ? null : player.getUUID();
        if (java.util.Objects.equals(playerId, nextId) && authority == nextAuthority && local == nextLocal)
            return;
        if (!java.util.Objects.equals(playerId, nextId)) {
            rememberedLocal = null;
            favourites.clear();
        }
        playerId = nextId;
        authority = nextAuthority;
        local = nextLocal;
        binding = new Object();
        roaming.retire();
        if (favouritePage != null)
            favouritePage.close();
        favouritePage = null;
        currentSelection = null;
        localTarget = null;
        appliedPlayer = null;
        if (playerId == null || authority == null)
            return;
        favouritePage = store.pageFavourites(playerId, "");
        if (local) {
            var fallback =
                new PlayerPreferences.LocalSelection(PublishedModelId.model(models.defaultRenderTarget().modelHash()),
                    models.defaultRenderTarget().info().getSettings().defaultTexture().orElse(""));
            roaming.restoreLocalSelection(playerId, fallback, selection -> {
                if (!selection.equals(rememberedLocal)) {
                    localTarget = null;
                    appliedPlayer = null;
                }
                rememberedLocal = selection;
                applyLocal(selection);
            });
        }
    }

    private PlayerAnimatableCapability player() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
    }

    public void commitSelection(ClientDemandCoordinator.SelectionContext context, ClientDemandCoordinator.Target target,
        long intentId) {
        bind();
        if (closed || authority == null || !context.matches(selectionContext()))
            throw new ClientDemandCoordinator.NotSubmitted("Selection owner retired");
        var association = models.demands().association(context, intentId, target);
        if (association == null)
            throw new IllegalStateException("Selection preparation is missing");
        var player = player();
        player.waitForAsyncUpdate();
        var initial = association.initial();
        roaming.select(playerId, authority, identity(target), intentId, initial == null ? null : initial.payload(),
            initial == null || initial.persistable());
        var operation = roaming.scope();
        currentSelection = new CurrentSelection(target, intentId, association.dictionary(), operation);
        appliedPlayer = null;
        applyCurrent();
        if (association.kind() == ClientDemandCoordinator.SelectionKind.CLEAR)
            roaming.clearStored(playerId, identity(target), this::clearCompleted);
        models.demands().selectionCommitted(context, intentId, target, operation);
        ClientProtocolGateway.selectionCommitted(currentSelection,
            initial == null ? new NamedRoamingSnapshot(new String[0], new float[0]) : initial.payload());
        if (com.elfmcys.ysm.network.NetworkDiagnostics.isEnabled())
            com.elfmcys.ysm.network.NetworkDiagnostics.selection("selection-committed", "client",
                player.getEntity().getId(), intentId,
                association.dictionary() == null ? 0 : association.dictionary().size(),
                initial == null ? 0 : initial.payload().values().length, local ? "local" : "optimistic");
        if (local) {
            rememberedLocal = new PlayerPreferences.LocalSelection(identity(target), target.texture());
            roaming.writeLocalSelection(playerId, rememberedLocal);
        }
    }

    private void applyCurrent() {
        var player = player();
        if (player == null || player == appliedPlayer || currentSelection == null)
            return;
        player.waitForAsyncUpdate();
        player.selectModel(currentSelection.target().modelId(), currentSelection.target().texture(),
            currentSelection.operation());
        appliedPlayer = player;
    }

    private void applyLocal(PlayerPreferences.LocalSelection selection) {
        if (currentSelection != null)
            return;
        var target = new ClientDemandCoordinator.Target(
            selection.identity() instanceof PublishedModelId.Model model ? model.hash() : null, selection.texture());
        var content = models.catalog().find(selection.identity()).orElse(null);
        if (content == null)
            target = new ClientDemandCoordinator.Target(null, "");
        roaming.select(playerId, authority, target.identity(), ++localWindow, null);
        currentSelection = new CurrentSelection(target, localWindow, null, roaming.scope());
        applyCurrent();
    }

    public boolean updateTexture(ClientDemandCoordinator.Target target) {
        bind();
        var previous = currentSelection;
        if (previous == null || !previous.target().identity().equals(target.identity()))
            return false;
        var player = player();
        if (player == null)
            return false;
        player.waitForAsyncUpdate();
        currentSelection =
            new CurrentSelection(target, previous.intentId(), previous.dictionary(), previous.operation());
        appliedPlayer = null;
        applyCurrent();
        if (local) {
            rememberedLocal = new PlayerPreferences.LocalSelection(target.identity(), target.texture());
            roaming.writeLocalSelection(playerId, rememberedLocal);
        } else
            ClientProtocolGateway.sendTexture(currentSelection);
        return true;
    }

    public void invalidateSelection() {
        var player = player();
        if (player != null) {
            player.waitForAsyncUpdate();
            roaming.retire();
            currentSelection = null;
            player.revokeAssignment();
            player.reset();
        }
        models.demands().reselect(new ClientDemandCoordinator.Target(null, ""));
    }

    public ClientDemandCoordinator.SelectionContext selectionContext() {
        bind();
        var minecraft = Minecraft.getInstance();
        var business = ClientSessionRuntime.businessSession().orElse(null);
        return new ClientDemandCoordinator.SelectionContext(business == null ? minecraft.level : business, playerId,
            binding, business != null,
            !closed && authority != null && playerId != null && player() != null && (local || business != null));
    }

    public ClientDemandCoordinator.InitialRoaming retainedInitial(ClientDemandCoordinator.SelectionContext context,
        ClientDemandCoordinator.Target target) {
        if (!context.matches(selectionContext()))
            return null;
        var scope = roaming.scope();
        var memory = scope != null && scope.player().equals(context.player()) && scope.authority() == authority
            && scope.identity().equals(target.identity()) ? roaming.snapshot(scope) : null;
        return memory == null ? null : new ClientDemandCoordinator.InitialRoaming(memory, roaming.persistable(scope));
    }

    public boolean favourite(ModelId model) {
        return favourites.contains(model);
    }

    public void favourite(ModelId model, boolean selected) {
        bind();
        if (playerId == null || closed)
            return;
        if (selected)
            favourites.add(model);
        else
            favourites.remove(model);
        store.writeFavourite(playerId, model, selected).result().whenComplete((result, failure) -> {
            if (failure != null || !result.succeeded())
                execute(() -> failure(
                    failure != null ? failure : new IllegalStateException("Favourite was not saved: " + result)));
        });
    }

    private void readFavourites() {
        if (favouritePage == null)
            return;
        var completion = favouritePage.poll();
        if (completion == null)
            return;
        favouritePage.close();
        favouritePage = null;
        if (completion.closed())
            return;
        var result = completion.result();
        if (completion.failure() != null || !result.succeeded()) {
            favourites.clear();
            readFailure(completion.failure() != null ? completion.failure()
                : new IllegalStateException("Favourite read failed: " + result));
            return;
        }
        for (var value : result.rows())
            if (value.selected())
                favourites.add(value.identity());
            else
                favourites.remove(value.identity());
        if (!result.rows().isEmpty())
            favouritePage =
                store.pageFavourites(playerId, result.rows().get(result.rows().size() - 1).identity().toString());
    }

    public void clear(PublishedModelId identity) {
        bind();
        if (playerId == null || authority == null || closed)
            return;
        var player = player();
        var current = player != null && identity.equals(
            identity(new ClientDemandCoordinator.Target(player.getModelHash(), player.getRequestedTextureName())));
        if (current) {
            models.demands().clearAndReselect(
                new ClientDemandCoordinator.Target(player.getModelHash(), player.getRequestedTextureName()));
            return;
        }
        models.demands().cancelSelection(identity);
        roaming.clear(playerId, identity, this::clearCompleted);
    }

    private void clearCompleted(com.elfmcys.ysm.natives.storage.StorageResult<NamedRoamingSnapshot> result) {
        if (!result.committed())
            YesSteveModel.LOGGER.warn("Roaming local clear persistence failed: {}", result);
        message(Component.translatable(result.committed() ? "message.yes_steve_model.preferences.clear.saved"
            : "message.yes_steve_model.preferences.clear.failed"));
    }

    private static PublishedModelId identity(ClientDemandCoordinator.Target target) {
        return target.modelId() == null ? PublishedModelId.INTRINSIC_DEFAULT : PublishedModelId.model(target.modelId());
    }

    private void failure(Throwable failure) {
        YesSteveModel.LOGGER.warn("Player preference operation failed", failure);
        message(Component.translatable("message.yes_steve_model.preferences.failure"));
    }

    public void readFailure(Throwable failure) {
        YesSteveModel.LOGGER.debug("Player preference read failed", failure);
        if (DebugAnimationScreen.isEnabled())
            message(Component.translatable("message.yes_steve_model.preferences.failure"));
    }

    private static void message(Component text) {
        var player = Minecraft.getInstance().player;
        if (player != null)
            player.sendSystemMessage(text);
    }

    @Override
    public void close() {
        closed = true;
        binding = null;
        roaming.retire();
        if (favouritePage != null)
            favouritePage.close();
        favouritePage = null;
    }
}
