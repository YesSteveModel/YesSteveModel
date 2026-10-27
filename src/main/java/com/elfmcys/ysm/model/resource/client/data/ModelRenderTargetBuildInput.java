package com.elfmcys.ysm.model.resource.client.data;

import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record ModelRenderTargetBuildInput(String renderTargetId, RenderTargetData target, CommonAssetData assets,
    RenderTargetExpressionModule expressions, @NotNull ModelInfoView info, CommonAssetData armAssets,
    RenderTargetExpressionModule armExpressions, AutoCloseable preparedOwner,
    java.util.Map<String, com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime.Source> defaultSources,
    com.elfmcys.ysm.model.resource.client.ModelRenderTarget.StateIdentity stateIdentity) {
    public ModelRenderTargetBuildInput(String targetId, RenderTargetData target, CommonAssetData assets,
        RenderTargetExpressionModule expressions, ModelInfoView info) {
        this(targetId, target, assets, expressions, info, null, null, expressions, java.util.Map.of(), null);
    }

    public ModelRenderTargetBuildInput {
        if (Objects.requireNonNull(renderTargetId, "renderTargetId").isBlank()) {
            throw new IllegalArgumentException("Render target id cannot be blank");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(expressions, "expressions");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(preparedOwner, "preparedOwner");
        defaultSources = java.util.Map.copyOf(defaultSources);
    }
}
