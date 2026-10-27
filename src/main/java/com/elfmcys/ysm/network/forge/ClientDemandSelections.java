package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Selection authority stays in the capability or the server, never in a GUI projection. */
public final class ClientDemandSelections implements ClientDemandCoordinator.Selections {
    private PlayerAnimatableCapability player() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
    }

    @Override
    public ClientDemandCoordinator.SelectionContext context() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return null;
        var state = ClientSessionRuntime.state().orElse(ClientModelSession.State.LOCAL);
        var business = ClientSessionRuntime.businessSession().orElse(null);
        return new ClientDemandCoordinator.SelectionContext(business == null ? minecraft.level : business.connection(),
            business != null, player() != null && (state == ClientModelSession.State.LOCAL || business != null));
    }

    @Override
    public ClientDemandCoordinator.Target authority() {
        var player = player();
        return player == null ? new ClientDemandCoordinator.Target(null, "")
            : new ClientDemandCoordinator.Target(player.getModelHash(), player.getRequestedTextureName());
    }

    @Override
    public boolean installed(ClientDemandCoordinator.Target target) {
        var player = player();
        return player != null && player.matchesSelection(target);
    }

    @Override
    public Throwable failure(ClientDemandCoordinator.Target target) {
        var player = player();
        return player != null && target.equals(authority()) ? player.modelLoadFailure() : null;
    }

    @Override
    public void send(ClientDemandCoordinator.Target target, long intentId) {
        var context = context();
        if (context == null || !context.available())
            throw new IllegalStateException("Model selection owner is unavailable");
        if (context.remote())
            ClientProtocolGateway.sendModelSelection(target.modelId(), target.texture(), intentId);
        else
            player().updateModelAndTexture(target.modelId(), target.texture());
    }

    @Override
    public void reportFailure(Throwable failure) {
        var player = Minecraft.getInstance().player;
        if (player != null)
            player.sendSystemMessage(Component.translatable("message.yes_steve_model.model.failure.fallback", 1));
        com.elfmcys.ysm.YesSteveModel.LOGGER.debug("Model selection ended without installation", failure);
    }
}
