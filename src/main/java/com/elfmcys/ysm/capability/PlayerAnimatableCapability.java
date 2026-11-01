package com.elfmcys.ysm.capability;

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
        roamingSession = new ClientRoamingSession(player, player instanceof LocalPlayer, this::reloadGeoModel);
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
        var localOnly = com.elfmcys.ysm.network.forge.ClientSessionRuntime.state().orElse(null)
            == com.elfmcys.ysm.model.session.client.ClientModelSession.State.LOCAL;
        var roaming = roamingSession.prepare(target.modelHash().roamingHash(), target.expressions(), prepared, localOnly);
        return () -> {
            player.run();
            roaming.run();
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

    public void resetRoamingVars(int modelHashShort, Int2FloatOpenHashMap vars) {
        waitForAsyncUpdate();
        roamingSession.resetFromServer(getExpressionRuntime(), modelHashShort, vars);
    }

    public void clearRoamingVars() {
        waitForAsyncUpdate();
        roamingSession.clearFromServer(getExpressionRuntime());
    }

    public void clearRoamingVars(int modelHashShort) {
        waitForAsyncUpdate();
        roamingSession.clearFromServer(getExpressionRuntime(), modelHashShort);
    }

    public boolean hasRoamingStorage(int hashShort) {
        return roamingSession.hasStorage(hashShort);
    }

    public void updateRemoteRoamingVars(int modelHashShort, Int2FloatMap vars) {
        waitForAsyncUpdate();
        roamingSession.updateRemote(getExpressionRuntime(), modelHashShort, vars);
    }

    @Override
    protected com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.ProjectionSink prepareLocalProjection() {
        return roamingSession.prepareLocalProjection();
    }

    public RoamingSnapshot roamingSnapshot(int authoritativeModelKey) {
        waitForAsyncUpdate();
        return roamingSession.snapshot(getExpressionRuntime(), authoritativeModelKey);
    }

    /** The roaming namespace of the applied model, or null before one is loaded. */
    public Integer localRoamingKey() {
        return roamingSession.currentRoamingKey();
    }

    public record RoamingSnapshot(int modelKey, Int2FloatOpenHashMap values,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Installation installation, boolean available) {
        public RoamingSnapshot(int modelKey, Int2FloatOpenHashMap values,
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Installation installation) {
            this(modelKey, values, installation, true);
        }

        public static RoamingSnapshot unavailable(int modelKey) {
            return new RoamingSnapshot(modelKey, new Int2FloatOpenHashMap(), null, false);
        }
    }

    public void moveFrom(PlayerAnimatableCapability source) {
        waitForAsyncUpdate();
        source.waitForAsyncUpdate();
        roamingSession.moveFrom(source.roamingSession);
        if (source.isAssigned())
            updateModelAndTexture(source.getModelHash(), source.textureName);
        else
            revokeAssignment();
        setDisabled(source.isDisabled());
    }
}
