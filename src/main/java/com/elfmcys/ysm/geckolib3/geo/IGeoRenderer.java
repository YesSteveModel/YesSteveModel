package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Adapts one prepared entity result to the current host draw.
 *
 * <p>Requires: data belongs to animatable's active consumption window for data.ctx.
 * The caller retains that owner and its resources through all hooks and vertex consumption.
 * Hooks must not retain the prepared data beyond that window.
 *
 * <p>Execution: callers invoke hooks and output synchronously on the host render thread.
 * Callers isolate pose-stack changes and obey NativeRenderer's global serial, non-reentrant contract.
 *
 * <p>Seam: implements the Java adapter in the
 * [host consumption contract](docs/architecture/rendering/overview.md#顶点输出与宿主接管).
 * RenderType selects host state; native context and vertex layout have separate authorities.
 */
public interface IGeoRenderer<T extends AnimatableEntity<?>> {
    /** The default hook scales the current pose; the caller owns restoration of that pose-stack frame. */
    default void preRender(GeoRenderData data, T animatable, PoseStack poseStack, MultiBufferSource bufferSource,
        int packedLight, int packedOverlay, Color color) {
        poseStack.scale(data.widthScale, data.heightScale, data.widthScale);
    }

    /**
     * <p>Failure: false means this call did not commit vertices. Owner revocation can reject data
     * after getBuffer() returns. NativeRenderer defines output rejection and publication damage.
     * Consumer exceptions propagate unchanged, including exceptions after a partially accepted fallback prefix.
     *
     * @return true only after the native output handoff succeeds, including a valid empty result
     */
    default boolean render(GeoRenderData data, T animatable, RenderType type, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay, Color color) {
        var buffer = bufferSource.getBuffer(type);
        if (!animatable.canConsume(data, data.ctx))
            return false;
        var modelState = data.modelState;
        return NativeRenderer.render(buffer, poseStack.last(), modelState.getNativeState(), modelState.getVertexCount(),
            packedLight, packedOverlay, color.getColor(), data.ctx.nativeType());
    }

    /** Records the render observation; this hook does not certify successful vertex consumption. */
    default void postRender(GeoRenderData data, T animatable, PoseStack poseStack, MultiBufferSource bufferSource,
        int packedLight, int packedOverlay, Color color) {
        animatable.countRender();
    }

    /** @return the host render state, or null when the subject is neither visible nor glowing */
    @Nullable
    default RenderType getRenderType(ResourceLocation texture, boolean visible, boolean glowing, boolean translucent) {
        if (visible) {
            return translucent ? CustomTranslucentRenderType.create(texture) : RenderType.entityCutoutNoCull(texture);
        }
        return glowing ? RenderType.outline(texture) : null;
    }
}
