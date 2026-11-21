package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner;
import com.elfmcys.ysm.model.storage.NamedRoamingSnapshot;
import dev.semaweave.RoamingPayload;
import net.minecraft.client.player.LocalPlayer;

final class LocalPlayerStateReporter {
    private final PlayerStateReportPlanner planner = new PlayerStateReportPlanner();
    private ClientSessionRuntime.BusinessSession boundSession;

    synchronized void setAnimation(String animation) {
        planner.setAnimation(animation);
    }

    synchronized void selectionCommitted(ClientPlayerPreferences.CurrentSelection selection,
        NamedRoamingSnapshot initial) {
        if (bindCurrentSession())
            planner.selectionCommitted(selection.intentId(), selection.dictionary(),
                initial.toPayload(java.util.Arrays.asList(selection.dictionary().names())));
    }

    synchronized void tick(LocalPlayer player, PlayerAnimatableCapability capability) {
        if (!bindCurrentSession())
            return;
        var selected = ClientModelService.instance().preferences().selection();
        if (selected == null || selected.intentId() <= 0)
            return;
        var sample = capability.roamingSnapshot(selected.target().identity());
        if (!sample.available() || sample.operation() != selected.operation())
            return;
        submitOpportunity(planner, System.nanoTime(), player.getId(), sample.values(), sample.installation(),
            report -> NetworkHandler.sendToServer(boundSession.connection(), report));
    }

    static void submitOpportunity(PlayerStateReportPlanner planner, long now, int entityId, RoamingPayload sample,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Installation installation,
        java.util.function.Consumer<com.elfmcys.ysm.proto.network.SelectionStateSyncProto> sender) {
        Runnable submit = () -> planner.observe(now, entityId, sample).ifPresent(report -> {
            try {
                sender.accept(report);
            } catch (RuntimeException ignored) {
            }
        });
        if (installation == null)
            submit.run();
        else
            installation.commit(submit);
    }

    synchronized void resetSession() {
        boundSession = null;
        planner.endSession();
    }

    private boolean bindCurrentSession() {
        var current = ClientSessionRuntime.businessSession().orElse(null);
        if (current == null) {
            resetSession();
            return false;
        }
        if (boundSession != current) {
            boundSession = current;
            planner.beginSession(current.policy().stateReportPolicy());
        }
        return true;
    }
}
