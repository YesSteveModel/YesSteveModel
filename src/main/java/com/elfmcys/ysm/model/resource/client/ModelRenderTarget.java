package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.natives.physics.NativePhysicsAsset;
import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Cleaner;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ModelRenderTarget implements AutoCloseable {
    private final ModelId modelHash;
    private final String renderTargetId;
    private volatile Resources resources;
    private final StateIdentity stateIdentity;
    private final CleanupState cleanupState;
    private final Cleaner.Cleanable cleanable;
    private volatile ResourceLocation textureId;

    public com.elfmcys.ysm.model.domain.PublishedModelId publishedId() {
        return stateIdentity != null
            && stateIdentity.key().role() == com.elfmcys.ysm.model.domain.PublishedModelId.Role.INTRINSIC_DEFAULT
                ? com.elfmcys.ysm.model.domain.PublishedModelId.INTRINSIC_DEFAULT
                : com.elfmcys.ysm.model.domain.PublishedModelId.model(modelHash);
    }

    public ModelRenderTarget(ModelId modelHash, String renderTargetId, RenderTargetResources targetResources,
        CommonAsset assets, AnimationStore animationStore, ModelInfoView modelInfo) {
        this(modelHash, renderTargetId, targetResources, assets, animationStore, modelInfo, null, null, animationStore,
            java.util.Map.of());
    }

    public ModelRenderTarget(ModelId modelHash, String renderTargetId, RenderTargetResources targetResources,
        CommonAsset assets, AnimationStore animationStore, ModelInfoView modelInfo, CommonAsset armAssets,
        AnimationStore armAnimationStore, AutoCloseable preparedOwner, java.util.Map<String,
            com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources) {
        this(modelHash, renderTargetId, targetResources, assets, animationStore, modelInfo, armAssets,
            armAnimationStore, preparedOwner, defaultSources, null, null, null);
    }

    public ModelRenderTarget(ModelId modelHash, String renderTargetId, RenderTargetResources targetResources,
        CommonAsset assets, AnimationStore animationStore, ModelInfoView modelInfo, CommonAsset armAssets,
        AnimationStore armAnimationStore, AutoCloseable preparedOwner,
        java.util.Map<String,
            com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources,
        StateIdentity stateIdentity, @Nullable NativePhysicsAsset physicsAsset,
        @Nullable PhysicsAssetMetadata physicsMetadata) {
        this.modelHash = Objects.requireNonNull(modelHash, "modelHash");
        this.renderTargetId = Objects.requireNonNull(renderTargetId, "renderTargetId");
        Objects.requireNonNull(targetResources, "targetResources");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(animationStore, "animationStore");
        if (stateIdentity != null && (!stateIdentity.content().modelId().equals(modelHash)
            || !stateIdentity.key().targetId().equals(renderTargetId)))
            throw new IllegalArgumentException("Render target state identity mismatch");
        this.stateIdentity = stateIdentity;
        resources = new Resources(targetResources, assets, animationStore, armAnimationStore, armAssets,
            java.util.Map.copyOf(defaultSources), animationStore.sharedSemantics(modelHash),
            Objects.requireNonNull(modelInfo, "modelInfo"), physicsAsset, physicsMetadata);
        cleanupState =
            new CleanupState(targetResources, assets, armAssets, preparedOwner, modelHash + "/" + renderTargetId);
        cleanable = CleanerUtil.ref(this, cleanupState, CleanupState::clean);
        ResourceDiagnostics.track(ResourceDiagnostics.Kind.RENDERTARGET, cleanupState,
            cleanupState.identity + " prepared");
        ResourceDiagnostics.log("render target load: {}", cleanupState.identity);
    }

    public ModelId modelHash() {
        return modelHash;
    }

    public String renderTargetId() {
        return renderTargetId;
    }

    public @Nullable StateIdentity stateIdentity() {
        return stateIdentity;
    }

    public boolean isStateCompatible(ModelRenderTarget next) {
        if (next == null || cleanupState.cleaned.get() || next.cleanupState.cleaned.get())
            return false;
        var current = resources;
        var candidate = next.resources;
        if (current == null || candidate == null)
            return false;
        return this == next || stateIdentity != null && stateIdentity.isCompatible(next.stateIdentity)
            && current.animationStore == candidate.animationStore
            && current.armAnimationStore == candidate.armAnimationStore;
    }

    public record StateIdentity(ModelFileIdentity content, RenderTargetKey.StateKey key, Object preparation) {
        public StateIdentity {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(preparation, "preparation");
        }

        public boolean isCompatible(StateIdentity next) {
            return hasSameInputs(next) && preparation == next.preparation;
        }

        public boolean hasSameInputs(@Nullable StateIdentity next) {
            return next != null && content.equals(next.content) && key.equals(next.key);
        }
    }

    public PlayerModelResources playerResources() {
        return requireResources().targetResources instanceof PlayerModelResources player ? player : null;
    }

    public CommonAsset assets() {
        return requireResources().assets;
    }

    /** Borrows the shared asset while retaining this target's lease. The preparation owns its close. */
    public @Nullable NativePhysicsAsset physicsAsset() {
        return requireResources().physicsAsset;
    }

    public @Nullable PhysicsAssetMetadata physicsMetadata() {
        return requireResources().physicsMetadata;
    }

    public AnimationStore animationStore() {
        return requireResources().animationStore;
    }

    public AnimationStore.View animations() {
        var targetResources = requireResources().targetResources;
        if (targetResources instanceof PlayerModelResources player)
            return player.animations();
        if (targetResources instanceof ProjectileModelResources projectile)
            return projectile.animations();
        return ((VehicleModelResources)targetResources).animations();
    }

    public AnimationStore armAnimationStore() {
        return Objects.requireNonNull(requireResources().armAnimationStore, "target has no arm animation store");
    }

    public AnimationStore.View armAnimations() {
        return Objects.requireNonNull(playerResources(), "target is not a player").fpArmAnimations();
    }

    public CommonAsset armAssets() {
        return Objects.requireNonNull(requireResources().armAssets, "target has no arm assets");
    }

    public java.util.Map<String, com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source>
        defaultSources() {
        return requireResources().defaultSources;
    }

    public com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics sharedSemantics() {
        return requireResources().sharedSemantics;
    }

    public ProjectileModelResources projectileResources() {
        return requireResources().targetResources instanceof ProjectileModelResources projectile ? projectile : null;
    }

    public VehicleModelResources vehicleResources() {
        return requireResources().targetResources instanceof VehicleModelResources vehicle ? vehicle : null;
    }

    public ModelInfoView info() {
        return requireResources().modelInfo;
    }

    public ResourceLocation textureId() {
        requireResources();
        var current = textureId;
        if (current == null) {
            throw new IllegalStateException("Model render target is not published");
        }
        return current;
    }

    public void adoptTexture(ResourceLocation textureId, AutoCloseable binding) {
        Objects.requireNonNull(textureId, "textureId");
        cleanupState.adopt(binding);
        this.textureId = textureId;
        ResourceDiagnostics.track(ResourceDiagnostics.Kind.RENDERTARGET, cleanupState,
            cleanupState.identity + " texture=" + textureId);
        ResourceDiagnostics.log("render target publish: {} texture={}", cleanupState.identity, textureId);
    }

    public String getDisplayName(String defaultName) {
        var info = info().getMetadata();
        if (info != null) {
            return LanguageManager.getI18n(this, "metadata.name", info.name());
        }
        return defaultName;
    }

    @Override
    public void close() {
        try {
            cleanable.clean();
        } finally {
            resources = null;
        }
    }

    private Resources requireResources() {
        var current = resources;
        if (current == null || cleanupState.cleaned.get())
            throw new IllegalStateException("Model render target is closed");
        return current;
    }

    private record Resources(RenderTargetResources targetResources, CommonAsset assets, AnimationStore animationStore,
        AnimationStore armAnimationStore, CommonAsset armAssets,
        java.util.Map<String,
            com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources,
        com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics sharedSemantics, ModelInfoView modelInfo,
        @Nullable NativePhysicsAsset physicsAsset, @Nullable PhysicsAssetMetadata physicsMetadata) {
    }

    private static final class CleanupState {
        private RenderTargetResources targetResources;
        private final String identity;
        private CommonAsset assets;
        private AutoCloseable preparedOwner;
        private CommonAsset armAssets;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicReference<AutoCloseable> textureBinding = new AtomicReference<>();

        private CleanupState(RenderTargetResources targetResources, CommonAsset assets, CommonAsset armAssets,
            AutoCloseable preparedOwner, String identity) {
            this.targetResources = targetResources;
            this.assets = assets;
            this.preparedOwner = Objects.requireNonNull(preparedOwner, "preparedOwner");
            this.armAssets = armAssets;
            this.identity = identity;
        }

        private void adopt(AutoCloseable binding) {
            Objects.requireNonNull(binding, "binding");
            if (cleaned.get() || !textureBinding.compareAndSet(null, binding)) {
                throw new IllegalStateException("Model render target cannot adopt a texture binding");
            }
        }

        private void clean() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            var prepared = preparedOwner;
            preparedOwner = null;
            var mainSounds = assets;
            assets = null;
            var armSounds = armAssets;
            armAssets = null;
            var geometry = targetResources;
            targetResources = null;
            var binding = textureBinding.getAndSet(null);
            var failure = closeResource(null, prepared);
            failure = closeResource(failure, mainSounds);
            if (armSounds != mainSounds)
                failure = closeResource(failure, armSounds);
            try {
                var models = Collections.newSetFromMap(new IdentityHashMap<GeoModel, Boolean>());
                if (geometry instanceof PlayerModelResources player) {
                    for (var variant : player.variants().values()) {
                        models.add(variant.mainModel());
                        if (variant.armModel() != null)
                            models.add(variant.armModel());
                    }
                } else if (geometry instanceof ProjectileModelResources projectile) {
                    models.add(projectile.model());
                } else if (geometry instanceof VehicleModelResources vehicle) {
                    models.add(vehicle.model());
                }
                var minecraft = Minecraft.getInstance();
                if (minecraft != null && !minecraft.isSameThread()) {
                    // Host cleanup must not retain animation views or their failure owners.
                    minecraft.execute(() -> destroy(models, binding));
                } else {
                    destroy(models, binding);
                }
            } catch (RuntimeException | Error next) {
                failure = appendFailure(failure, next);
            }
            throwFailure(failure);
        }

        private void destroy(Set<GeoModel> models, AutoCloseable binding) {
            Throwable failure = null;
            try {
                if (binding != null || !models.isEmpty())
                    RenderSystem.assertOnRenderThread();
                failure = closeResource(failure, binding);
                for (var model : models)
                    failure = closeResource(failure, model);
            } catch (RuntimeException | Error next) {
                failure = appendFailure(failure, next);
            } finally {
                ResourceDiagnostics.untrack(ResourceDiagnostics.Kind.RENDERTARGET, this);
                ResourceDiagnostics.log("render target unload: {}", identity);
            }
            throwFailure(failure);
        }

        private static Throwable closeResource(Throwable failure, AutoCloseable resource) {
            if (resource != null)
                try {
                    resource.close();
                } catch (Throwable next) {
                    return appendFailure(failure, next);
                }
            return failure;
        }

        private static Throwable appendFailure(Throwable failure, Throwable next) {
            if (failure == null)
                return next;
            if (failure != next)
                failure.addSuppressed(next);
            return failure;
        }

        private static void throwFailure(Throwable failure) {
            if (failure instanceof RuntimeException error)
                throw error;
            if (failure instanceof Error error)
                throw error;
            if (failure != null)
                throw new IllegalStateException("Failed to close model render target", failure);
        }
    }
}
