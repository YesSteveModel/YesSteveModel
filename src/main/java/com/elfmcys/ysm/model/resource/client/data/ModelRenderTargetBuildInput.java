package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record ModelRenderTargetBuildInput(String renderTargetId, RenderTargetData target, CommonAssetData assets,
    AnimationStore animationStore, @NotNull ModelInfoView info, CommonAssetData armAssets,
    AnimationStore armAnimationStore, AutoCloseable preparedOwner,
    java.util.Map<String, com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources,
    com.elfmcys.ysm.model.resource.client.ModelRenderTarget.StateIdentity stateIdentity) {
    public ModelRenderTargetBuildInput(String targetId, RenderTargetData target, CommonAssetData assets,
        AnimationStore animationStore, ModelInfoView info) {
        this(targetId, target, assets, animationStore, info, null, null, animationStore, java.util.Map.of(), null);
    }

    public ModelRenderTargetBuildInput {
        if (Objects.requireNonNull(renderTargetId, "renderTargetId").isBlank()) {
            throw new IllegalArgumentException("Render target id cannot be blank");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(animationStore, "animationStore");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(preparedOwner, "preparedOwner");
        defaultSources = java.util.Map.copyOf(defaultSources);
    }
}
