package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.bettercombat.BetterCombatCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

@OnlyIn(Dist.CLIENT)
public final class PlayerAnimatableCapability extends CustomPlayerEntity {
    private final ClientRoamingSession roamingSession;

    public PlayerAnimatableCapability(Player player) {
        super(player, player instanceof LocalPlayer, true);
        roamingSession = new ClientRoamingSession(player, player instanceof LocalPlayer, this::reloadGeoModel,
            this::selectionOperation);
    }

    @Override
    protected boolean worldSubjectSamplePublisher(Player subject) {
        return true;
    }

    @Override
    protected PlayerStateTracker createStateTracker(Player entity) {
        return new PlayerStateTracker(entity, entity instanceof LocalPlayer);
    }

    public PlayerStateTracker getStateTracker() {
        return (PlayerStateTracker)super.getStateTracker();
    }

    @Override
    protected Runnable prepareRoleInstallation(ModelRenderTarget target,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
        var player = super.prepareRoleInstallation(target, prepared);
        var roaming = roamingSession.prepare(target.modelHash(), prepared, target.publishedId());
        return () -> {
            player.run();
            roaming.run();
            var selected = roamingSession.remoteSelection();
            if (selected != null && selected.model().equals(target.publishedId()))
                setRemoteAnimation(selected.animation());
        };
    }

    @Override
    protected void onModelEnded() {
        super.onModelEnded();
        if (roamingSession != null && !hasInstalledModel())
            roamingSession.modelReset();
    }

    @Override
    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<Player>> animationEvent, boolean update) {
        super.codeAnimation(animationEvent, update);

        // 更新第一人称相机偏移与头部隐藏
        AnimatedGeoModel model = getLoadedGeoModel();
        if (model != null && isLocalPlayer()) {
            var ctx = animationEvent.getRenderContext();
            if (ctx.firstPersonMod()) {
                if (model.getFirstPersonAllHead() != null) {
                    model.getFirstPersonAllHead().setHidden(true);
                }
                if (model.getFirstPersonViewLocator() != null) {
                    FirstPersonCompat.setHeadPos(model.getFirstPersonViewLocator().getPivot().y * getHeightScale());
                } else if (update) {
                    var head = model.getFirstPersonHead();
                    if (head != null) {
                        FirstPersonCompat.setHeadPos(head.getPivotY() * getHeightScale());
                    }
                }
            }
        }
    }

    @Override
    protected void recoverLastCodedAnimation() {
        super.recoverLastCodedAnimation();

        AnimatedGeoModel model = getLoadedGeoModel();
        if (model != null && isLocalPlayer()) {
            if ((FirstPersonCompat.isInstalled() || BetterCombatCompat.isInstalled())
                && model.getFirstPersonAllHead() != null) {
                model.getFirstPersonAllHead().setHidden(false);
            }
        }
    }

    public void applyRemoteAnimation(String animation) {
        waitForAsyncUpdate();
        if (installedForSelection(roamingSession.remoteOperation()))
            setRemoteAnimation(animation);
    }

    private void setRemoteAnimation(String animation) {
        extraAnimationName = animation.isEmpty() ? "idle" : animation;
        isPlayingExtraAnimation = !animation.isEmpty();
        isExtraAnimationDirty = true;
    }

    public CommittedSelection remoteSelection() {
        return roamingSession.remoteSelection();
    }

    public Object remoteSelectionOperation() {
        return roamingSession.remoteOperation();
    }

    public void acceptRemoteSelection(CommittedSelection selected, Object operation, RemoteSyncBudget budget) {
        waitForAsyncUpdate();
        roamingSession.acceptSelection(getExpressionRuntime(), selected, operation, budget);
        prepareStateInputs();
    }

    public void updateRemoteRoamingVars(com.elfmcys.ysm.network.protocol.RoamingValues values) {
        waitForAsyncUpdate();
        roamingSession.updateRemote(getExpressionRuntime(), values);
        prepareStateInputs();
    }

    public void acceptRemoteSync(com.elfmcys.ysm.expression.host.EventArguments arguments) {
        waitForAsyncUpdate();
        roamingSession.acceptSync(getExpressionRuntime(), arguments, this::dispatchSync);
    }

    public void clearRemoteSelection() {
        try {
            waitForAsyncUpdate();
        } finally {
            roamingSession.clearRemote();
        }
    }

    @Override
    protected void onEntityRetired() {
        roamingSession.clearRemote();
    }

    @Override
    protected void prepareStateInputs() {
        roamingSession.drain(getExpressionRuntime(), this::dispatchSync);
    }

    @Override
    protected com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.ProjectionSink prepareLocalProjection() {
        return roamingSession.prepareLocalProjection();
    }

    public RoamingSnapshot roamingSnapshot(com.elfmcys.ysm.model.domain.PublishedModelId authoritativeModelKey) {
        waitForAsyncUpdate();
        return roamingSession.snapshot(getExpressionRuntime(), authoritativeModelKey);
    }

    /** The roaming namespace of the applied model, or null before one is loaded. */
    public ModelId localRoamingKey() {
        return roamingSession.currentRoamingKey();
    }

    public record RoamingSnapshot(com.elfmcys.ysm.model.domain.PublishedModelId modelKey,
        dev.semaweave.RoamingPayload values,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Installation installation, Object operation,
        boolean available) {
        public static RoamingSnapshot unavailable(com.elfmcys.ysm.model.domain.PublishedModelId key) {
            return new RoamingSnapshot(key, new dev.semaweave.RoamingPayload(new short[0], new float[0]), null, null,
                false);
        }
    }

    public void moveFrom(PlayerAnimatableCapability source) {
        waitForAsyncUpdate();
        source.waitForAsyncUpdate();
        roamingSession.moveFrom(source.roamingSession);
        if (source.isAssigned())
            selectModel(source.getModelHash(), source.getRequestedTextureName(), source.selectionOperation());
        else
            revokeAssignment();
        setDisabled(source.isDisabled());
    }
}
