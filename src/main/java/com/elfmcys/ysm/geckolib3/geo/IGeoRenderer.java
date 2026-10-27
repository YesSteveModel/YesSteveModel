package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public interface IGeoRenderer<T extends AnimatableEntity<?>> {
    default void preRender(GeoRenderData data, T animatable, PoseStack poseStack, MultiBufferSource bufferSource,
        int packedLight, int packedOverlay, Color color) {
        poseStack.scale(data.widthScale, data.heightScale, data.widthScale);
    }

    default boolean render(GeoRenderData data, T animatable, RenderType type, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay, Color color) {
        var buffer = bufferSource.getBuffer(type);
        if (!animatable.canConsume(data, data.ctx))
            return false;
        var modelState = data.modelState;
        return NativeRenderer.render(buffer, poseStack.last(), modelState.getNativeState(), modelState.getVertexCount(),
            packedLight, packedOverlay, color.getColor(), data.ctx.nativeType());
    }

    default void postRender(GeoRenderData data, T animatable, PoseStack poseStack, MultiBufferSource bufferSource,
        int packedLight, int packedOverlay, Color color) {
        animatable.countRender();
    }

    @Nullable
    default RenderType getRenderType(ResourceLocation texture, boolean visible, boolean glowing, boolean translucent) {
        if (visible) {
            return translucent ? CustomTranslucentRenderType.create(texture) : RenderType.entityCutoutNoCull(texture);
        }
        return glowing ? RenderType.outline(texture) : null;
    }
}
