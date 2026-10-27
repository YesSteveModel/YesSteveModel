package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.lang.ref.Cleaner;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ModelRenderTarget implements AutoCloseable {
    private final Hash256 modelHash;
    private final String renderTargetId;
    private final RenderTargetResources targetResources;
    private final CommonAsset assets;
    private final RenderTargetExpressionModule expressions;
    private final RenderTargetExpressionModule armExpressions;
    private final CommonAsset armAssets;
    private final StateIdentity stateIdentity;
    private final java.util.Map<String,
        com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources;
    private final com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics sharedSemantics;

    private final ModelInfoView modelInfo;
    private final CleanupState cleanupState;
    private final Cleaner.Cleanable cleanable;
    private volatile ResourceLocation textureId;

    public ModelRenderTarget(Hash256 modelHash, String renderTargetId, RenderTargetResources targetResources,
        CommonAsset assets, RenderTargetExpressionModule expressions, ModelInfoView modelInfo) {
        this(modelHash, renderTargetId, targetResources, assets, expressions, modelInfo, null, null, expressions,
            java.util.Map.of());
    }

    public ModelRenderTarget(Hash256 modelHash, String renderTargetId, RenderTargetResources targetResources,
        CommonAsset assets, RenderTargetExpressionModule expressions, ModelInfoView modelInfo, CommonAsset armAssets,
        RenderTargetExpressionModule armExpressions, AutoCloseable preparedOwner, java.util.Map<String,
            com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources) {
        this(modelHash, renderTargetId, targetResources, assets, expressions, modelInfo, armAssets, armExpressions,
            preparedOwner, defaultSources, null);
    }

    public ModelRenderTarget(Hash256 modelHash, String renderTargetId, RenderTargetResources targetResources,
        CommonAsset assets, RenderTargetExpressionModule expressions, ModelInfoView modelInfo, CommonAsset armAssets,
        RenderTargetExpressionModule armExpressions, AutoCloseable preparedOwner, java.util.Map<String,
            com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources,
        StateIdentity stateIdentity) {
        this.modelHash = Objects.requireNonNull(modelHash, "modelHash");
        this.renderTargetId = Objects.requireNonNull(renderTargetId, "renderTargetId");
        this.targetResources = Objects.requireNonNull(targetResources, "targetResources");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.armExpressions = armExpressions;
        this.armAssets = armAssets;
        if (stateIdentity != null && (!stateIdentity.content().modelId().equals(modelHash)
            || !stateIdentity.key().targetId().equals(renderTargetId)))
            throw new IllegalArgumentException("Render target state identity mismatch");
        this.stateIdentity = stateIdentity;
        this.defaultSources = java.util.Map.copyOf(defaultSources);
        this.sharedSemantics = expressions.sharedSemantics(modelHash);
        this.modelInfo = Objects.requireNonNull(modelInfo, "modelInfo");
        cleanupState =
            new CleanupState(targetResources, assets, armAssets, preparedOwner, modelHash + "/" + renderTargetId);
        cleanable = CleanerUtil.ref(this, cleanupState, CleanupState::clean);
        ResourceDiagnostics.track(ResourceDiagnostics.Kind.RENDERTARGET, cleanupState,
            cleanupState.identity + " prepared");
        ResourceDiagnostics.log("render target load: {}", cleanupState.identity);
    }

    public Hash256 modelHash() {
        return modelHash;
    }

    public String renderTargetId() {
        return renderTargetId;
    }

    public boolean isStateCompatible(ModelRenderTarget next) {
        if (next == null || cleanupState.cleaned.get() || next.cleanupState.cleaned.get())
            return false;
        return this == next || stateIdentity != null && stateIdentity.isCompatible(next.stateIdentity)
            && expressions == next.expressions && armExpressions == next.armExpressions;
    }

    public record StateIdentity(ModelFileIdentity content, RenderTargetKey.StateKey key, Object preparation) {
        public StateIdentity {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(preparation, "preparation");
        }

        public boolean isCompatible(StateIdentity next) {
            return next != null && content.equals(next.content) && key.equals(next.key)
                && preparation == next.preparation;
        }
    }

    public PlayerModelResources playerResources() {
        return targetResources instanceof PlayerModelResources player ? player : null;
    }

    public CommonAsset assets() {
        return assets;
    }

    public RenderTargetExpressionModule expressions() {
        return expressions;
    }

    public RenderTargetExpressionModule armExpressions() {
        return Objects.requireNonNull(armExpressions, "target has no arm expression set");
    }

    public CommonAsset armAssets() {
        return Objects.requireNonNull(armAssets, "target has no arm assets");
    }

    public java.util.Map<String, com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source>
        defaultSources() {
        return defaultSources;
    }

    public com.elfmcys.ysm.expression.inventory.SharedExpressionSemantics sharedSemantics() {
        return sharedSemantics;
    }

    public ProjectileModelResources projectileResources() {
        return targetResources instanceof ProjectileModelResources projectile ? projectile : null;
    }

    public VehicleModelResources vehicleResources() {
        return targetResources instanceof VehicleModelResources vehicle ? vehicle : null;
    }

    public ModelInfoView info() {
        return modelInfo;
    }

    public ResourceLocation textureId() {
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
        var info = modelInfo.getMetadata();
        if (info != null) {
            return LanguageManager.getI18n(this, "metadata.name", info.name());
        }
        return defaultName;
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private static final class CleanupState {
        private final RenderTargetResources targetResources;
        private final String identity;
        private final CommonAsset assets;
        private final AutoCloseable preparedOwner;
        private final CommonAsset armAssets;
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
            RuntimeException expressionFailure = null;
            try {
                preparedOwner.close();
            } catch (Exception failure) {
                expressionFailure = failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("prepared target close failed", failure);
            }
            assets.close();
            if (armAssets != null)
                armAssets.close();
            var minecraft = Minecraft.getInstance();
            if (minecraft != null && !minecraft.isSameThread()) {
                minecraft.execute(this::destroy);
            } else {
                destroy();
            }
            if (expressionFailure != null) {
                throw expressionFailure;
            }
        }

        private void destroy() {
            RenderSystem.assertOnRenderThread();
            RuntimeException releaseFailure = null;
            var binding = textureBinding.getAndSet(null);
            if (binding != null) {
                try {
                    binding.close();
                } catch (Exception error) {
                    releaseFailure = new IllegalStateException("Failed to release model texture binding", error);
                }
            }
            if (targetResources instanceof PlayerModelResources player) {
                player.animations().close();
                player.fpArmAnimations().close();
            } else if (targetResources instanceof ProjectileModelResources projectile) {
                projectile.animations().close();
            } else if (targetResources instanceof VehicleModelResources vehicle) {
                vehicle.animations().close();
            }
            var models = Collections.newSetFromMap(new IdentityHashMap<GeoModel, Boolean>());
            if (targetResources instanceof PlayerModelResources player) {
                for (var variant : player.variants().values()) {
                    models.add(variant.mainModel());
                    if (variant.armModel() != null)
                        models.add(variant.armModel());
                }
            } else if (targetResources instanceof ProjectileModelResources projectile) {
                models.add(projectile.model());
            } else if (targetResources instanceof VehicleModelResources vehicle) {
                models.add(vehicle.model());
            }
            models.forEach(GeoModel::close);
            ResourceDiagnostics.untrack(ResourceDiagnostics.Kind.RENDERTARGET, this);
            ResourceDiagnostics.log("render target unload: {}", identity);
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }
}
