package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.config.ClientConfig;

import com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection;
import dev.semaweave.RoamingRole;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

public abstract class CustomPlayerEntity extends CustomHumanoidEntity<Player> {
    protected final boolean localPlayer;

    protected boolean isPlayingExtraAnimation = false;
    protected String extraAnimationName = "idle";
    protected boolean isExtraAnimationDirty = false;

    public CustomPlayerEntity(Player player, boolean localPlayer, boolean asyncUpdate) {
        super(player, asyncUpdate);
        this.localPlayer = localPlayer;
        if (player instanceof LocalPlayer) {
            updateModelHash(null);
        }
    }

    @Override
    protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {
        target.playerResources().playerControllerFactory().accept(this, animations);
    }

    @Override
    protected boolean isProducer() {
        return localPlayer;
    }

    @Override
    protected Class<?> resourceSubjectType() {
        return net.minecraft.client.player.AbstractClientPlayer.class;
    }

    @Override
    protected boolean presentationPermission() {
        return super.presentationPermission() && (isFakePlayer()
            || !(localPlayer ? ClientConfig.DISABLE_SELF_MODEL.get() : ClientConfig.DISABLE_OTHER_MODEL.get()));
    }

    @Override
    public boolean determineImmutableContext(RenderContext context) {
        if (!super.determineImmutableContext(context)) {
            return false;
        }
        // 例外：local player 渲染 iris 阴影时应恒为第三人称，不能视为 immutable
        return !context.irisShadow() || !localPlayer
            || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON;
    }

    public boolean isLocalPlayer() {
        return localPlayer;
    }

    @Override
    protected void onModelEnded() {
        super.onModelEnded();
        if (hasInstalledModel())
            return;
        isPlayingExtraAnimation = false;
        extraAnimationName = "idle";
        isExtraAnimationDirty = false;
    }

    @Override
    protected Runnable prepareRoleInstallation(ModelRenderTarget target,
        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
        var humanoid = super.prepareRoleInstallation(target, prepared);
        return () -> {
            humanoid.run();
            isPlayingExtraAnimation = false;
            extraAnimationName = "idle";
            isExtraAnimationDirty = false;
        };
    }

    public void playExtraAnimation(String animationName) {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        if (!canAcceptPlayback())
            return;
        if (getAnimation(animationName) != null) {
            this.extraAnimationName = animationName;
            this.isPlayingExtraAnimation = true;
            this.isExtraAnimationDirty = true;
        } else {
            this.isPlayingExtraAnimation = false;
        }
    }

    public void clearExtraAnimationDirty() {
        this.isExtraAnimationDirty = false;
    }

    public boolean isPlayingExtraAnimation() {
        return isPlayingExtraAnimation;
    }

    public boolean shouldResetExtraAnimation() {
        return isExtraAnimationDirty;
    }

    public String getExtraAnimationName() {
        return this.extraAnimationName;
    }

    public void stopExtraAnimation() {
        rejectCandidateMutation();
        waitForAsyncUpdate();
        this.isPlayingExtraAnimation = false;
    }

    @Override
    protected RoamingRole roamingRole() {
        return localPlayer ? RoamingRole.PRODUCER : RoamingRole.CONSUMER;
    }

    @Override
    protected void postAnimationSetup(float seekTime, boolean shouldTick) {
        super.postAnimationSetup(seekTime, shouldTick);
        if (localPlayer && shouldTick) {
            if (isPlayingExtraAnimation()
                && getCodedAnimationStates(PlayerControllerCollection.CAP_CONTROLLER) == AnimationState.IDLE) {
                isPlayingExtraAnimation = false;
                if (NetworkHandler.isRemoteChannelPresent()) {
                    ClientProtocolGateway.stopSelfAnimation();
                }
            }
        }
    }

}
