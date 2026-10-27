package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;

public abstract class GeoLayerRenderer<T extends AnimatableEntity<?>> {
    public GeoLayerRenderer() {}

    public abstract void render(PoseStack poseStack, MultiBufferSource buffer, T animatable, GeoRenderData renderData,
        int packedLight, int overlay);
}
