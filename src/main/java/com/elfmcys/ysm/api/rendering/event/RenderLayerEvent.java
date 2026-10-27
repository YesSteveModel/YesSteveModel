package com.elfmcys.ysm.api.rendering.event;

import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

@Cancelable
public class RenderLayerEvent extends Event implements IModBusEvent {
    private final Object target;
    private final GeoRenderData renderData;
    private final PoseStack poseStack;
    private final MultiBufferSource buffer;
    private final int packedLight;
    private final int overlay;

    public RenderLayerEvent(Object target, GeoRenderData renderData, PoseStack poseStack, MultiBufferSource buffer,
        int packedLight, int overlay) {
        this.target = target;
        this.renderData = renderData;
        this.poseStack = poseStack;
        this.buffer = buffer;
        this.packedLight = packedLight;
        this.overlay = overlay;
    }

    public Object target() {
        return target;
    }

    public GeoRenderData renderData() {
        return renderData;
    }

    public PoseStack poseStack() {
        return poseStack;
    }

    public MultiBufferSource buffer() {
        return buffer;
    }

    public int packedLight() {
        return packedLight;
    }

    public int overlay() {
        return overlay;
    }
}
