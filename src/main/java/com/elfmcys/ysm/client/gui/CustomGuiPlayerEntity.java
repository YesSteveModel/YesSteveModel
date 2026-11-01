package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.animation.physics.PhysicsManager;
import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.client.event.ClientTickEvent;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.client.demand.ModelResourceDemand;
import org.jetbrains.annotations.Nullable;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.animation.debug.DebugSource;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.Objects;

public final class CustomGuiPlayerEntity extends CustomPlayerEntity implements IPreviewEntity {
    private final PreviewAnimationInfo guiAnimationInfo;
    private final ModelResourcePurpose purpose;
    private boolean allowEmitting;
    private boolean suppliedResource;

    public CustomGuiPlayerEntity() {
        this(ModelResourcePurpose.GUI);
    }

    public CustomGuiPlayerEntity(ModelResourcePurpose purpose) {
        this(new FakePlayer(), purpose);
    }

    CustomGuiPlayerEntity(Player subject) {
        this(subject, ModelResourcePurpose.GUI);
    }

    private CustomGuiPlayerEntity(Player subject, ModelResourcePurpose purpose) {
        super(subject, false, false);
        this.purpose = Objects.requireNonNull(purpose);
        guiAnimationInfo = new PreviewAnimationInfo();
        if (ResourceDiagnostics.isEnabled()) {
            ResourceDiagnostics.track(ResourceDiagnostics.Kind.GUI_ENTITY, this,
                "GUI entity " + purpose + " world=" + System.identityHashCode(subject.level()));
            ResourceDiagnostics.log("GUI entity created: owner={} world={} purpose={}", System.identityHashCode(this),
                System.identityHashCode(subject.level()), purpose);
        }
    }

    private ModelResourceDemand.Mode demandMode = ModelResourceDemand.Mode.ONLINE;

    public void setResourceDemandMode(ModelResourceDemand.Mode mode) {
        assertPublicMutationAccess();
        demandMode = mode;
    }

    @Override
    protected ModelResourceDemand.Mode resourceDemandMode() {
        return demandMode;
    }

    @Override
    protected ModelResourcePurpose resourcePurpose() {
        return purpose;
    }

    @Override
    protected Class<?> resourceSubjectType() {
        return purpose == ModelResourcePurpose.FULL ? AbstractClientPlayer.class : FakePlayer.class;
    }

    @Override
    protected String fallbackRenderTargetId() {
        return null;
    }

    @Override
    public void checkModelUpdate() {
        // Supplied preview resources already completed the same installation seam.
        if (!suppliedResource)
            super.checkModelUpdate();
    }

    public @Nullable ModelRenderTarget displayedModel() {
        return hasMatchingInstallation() ? getModelRenderTarget() : null;
    }

    void installPreviewResource(ResourceRequest request, ResourceLease lease) {
        super.installReadyForPreview(request, lease);
        suppliedResource = true;
    }

    /** BUSY leaves the independent receiving lease with the caller. */
    ClientDemandCoordinator.InstallationResult receiveReady(ResourceRequest request, ResourceLease lease) {
        setResourceDemandMode(ModelResourceDemand.Mode.READY);
        updateModelAndTexture(request.modelId(), request.requestedTexture());
        var result = install(request, lease, false, purpose == ModelResourcePurpose.FULL);
        if (result == ClientDemandCoordinator.InstallationResult.INSTALLED)
            suppliedResource = true;
        return result;
    }

    @Override
    protected void onModelEnded() {
        guiAnimationInfo.setFocus("");
        guiAnimationInfo.setPreview("");
        guiAnimationInfo.setHover("");
        allowEmitting = false;
        suppliedResource = false;
        super.onModelEnded();
    }

    @Override
    public boolean determineImmutableContext(RenderContext context) {
        return true;
    }

    @Override
    public @NotNull PreviewAnimationInfo getPreviewInfo() {
        assertExecutionStateAccess();
        return guiAnimationInfo;
    }

    @Override
    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        assertExecutionStateAccess();
        return physicsManager;
    }

    @Override
    public void setAllowEmitting(boolean value) {
        assertExecutionStateAccess();
        this.allowEmitting = value;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    protected boolean allowEmitting() {
        return allowEmitting;
    }

    @Override
    public int getFrameRateLimit() {
        return ClientTickEvent.getRefreshRate();
    }

    @Override
    public boolean isFakePlayer() {
        return true;
    }

    @Override
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        if (entity.level() != Minecraft.getInstance().level) {
            return null;
        }
        return super.update(partialTicks, context);
    }

    public static boolean isFakePlayer(Player player) {
        return player instanceof FakePlayer;
    }

    @Override
    public DebugSource getDebugSource() {
        return null;
    }

    public static final class FakePlayer extends AbstractClientPlayer {
        public FakePlayer() {
            super(Objects.requireNonNull(Minecraft.getInstance().level, "A preview requires a client level"),
                createRandomGameProfile());
        }

        private static GameProfile createRandomGameProfile() {
            var uuid = UUID.randomUUID();
            return new GameProfile(uuid, "ysm_" + uuid.toString().replace('-', '_'));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

    }
}
