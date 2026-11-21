package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Selection authority stays in the capability or the server, never in a GUI projection. */
public final class ClientDemandSelections implements ClientDemandCoordinator.Selections {
    private final ClientPlayerPreferences preferences;

    public ClientDemandSelections(ClientPlayerPreferences preferences) {
        this.preferences = java.util.Objects.requireNonNull(preferences);
    }

    private PlayerAnimatableCapability player() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
    }

    @Override
    public ClientDemandCoordinator.SelectionContext context() {
        if (Minecraft.getInstance().level == null)
            return null;
        return preferences.selectionContext();
    }

    @Override
    public ClientDemandCoordinator.Target authority() {
        var current = preferences.selection();
        if (current != null)
            return current.target();
        var player = player();
        return player == null ? new ClientDemandCoordinator.Target(null, "")
            : new ClientDemandCoordinator.Target(player.getModelHash(), player.getRequestedTextureName());
    }

    @Override
    public boolean installed(ClientDemandCoordinator.Target target, Object application) {
        var player = player();
        return player != null && (application == null || player.installedForSelection(application))
            && player.matchesSelection(target);
    }

    @Override
    public Throwable failure(ClientDemandCoordinator.Target target) {
        var player = player();
        return player != null && target.equals(authority()) ? player.modelLoadFailure() : null;
    }

    @Override
    public ClientDemandCoordinator.InitialRoaming retainedInitial(ClientDemandCoordinator.SelectionContext context,
        ClientDemandCoordinator.Target target) {
        return preferences.retainedInitial(context, target);
    }

    @Override
    public void send(ClientDemandCoordinator.SelectionContext captured, ClientDemandCoordinator.Target target,
        long intentId, NamedRoamingSnapshot initial, boolean reinitialize) {
        var current = context();
        if (!captured.matches(current) || !current.available())
            throw new ClientDemandCoordinator.NotSubmitted("Model selection owner is unavailable");
        if (captured.remote()) {
            if (target.modelId() != null) {
                var entry = com.elfmcys.ysm.model.service.ClientModelService.instance().catalog().find(target.modelId())
                    .orElseThrow(() -> new ClientDemandCoordinator.NotSubmitted("Selection target is unavailable"));
                if (entry.authorizationRequired() && !ClientSessionRuntime.authoritativeGrants()
                    .orElseGet(java.util.Set::of).contains(target.modelId()))
                    throw new ClientDemandCoordinator.NotSubmitted("Selection permission was revoked");
            }
        }
        var association = com.elfmcys.ysm.model.service.ClientModelService.instance().demands().association(captured,
            intentId, target);
        var dictionary = association.dictionary();
        var message = captured.remote() ? ClientProtocolGateway.prepareSelection(
            java.util.Objects.requireNonNull(Minecraft.getInstance().player).getId(), target, intentId, initial,
            dictionary) : null;
        preferences.commitSelection(captured, target, intentId);
        if (captured.remote())
            ClientProtocolGateway
                .sendModelSelection(((ClientSessionRuntime.BusinessSession)captured.owner()).connection(), message);
    }

    @Override
    public boolean updateTexture(ClientDemandCoordinator.Target target) {
        return preferences.updateTexture(target);
    }

    @Override
    public void reportFailure(Throwable failure) {
        var player = Minecraft.getInstance().player;
        var stage = failure instanceof ClientDemandCoordinator.SelectionFailure selection ? selection.stage()
            : ClientDemandCoordinator.FailureStage.INSTALLATION;
        if (player != null)
            player.sendSystemMessage(Component.translatable(
                "message.yes_steve_model.selection.failure." + stage.name().toLowerCase(java.util.Locale.ROOT)));
        com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Model selection failed during {}", stage, failure);
    }

    @Override
    public void reportPreferenceReadFailure(Throwable failure) {
        preferences.readFailure(failure);
    }
}
