package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/** A per-card camera; the delegate exists only during a synchronous GUI draw. */
public final class GuiModelFraming implements MultiBufferSource {
    private final GuiModelView view = new GuiModelView();
    private final GuiModelView.Bounds bounds = new GuiModelView.Bounds();
    private final Matrix4f bonePose = new Matrix4f();
    private final Matrix4d transform = new Matrix4d();
    private final Matrix4d boneTransform = new Matrix4d();
    private Object installation;
    private MultiBufferSource delegate;
    private float width, height;

    public void begin(MultiBufferSource delegate, float width, float height) {
        this.delegate = delegate;
        this.width = width;
        this.height = height;
    }

    public void end() {
        delegate = null;
    }

    public boolean apply(Object installation, GeoModelState state, PoseStack poseStack) {
        if (this.installation != installation) {
            this.installation = installation;
            view.reset();
        }
        if (!state.isValid() || state.getVertexCount() == 0)
            return false;
        var geometry = state.getModel().guiBoneBounds();
        var nativeState = state.getNativeState();
        var bones = nativeState.getRenderBoneIndices();
        var poses = nativeState.getBonePoses();
        bounds.clear();
        for (var i = 0; i < bones.capacity(); i++) {
            var bone = Short.toUnsignedInt(bones.get(i));
            var offset = bone * 6;
            if (geometry[offset] > geometry[offset + 3])
                continue;
            poses.getPose(bone, bonePose);
            transform.set(poseStack.last().pose()).mul(boneTransform.set(bonePose));
            for (var corner = 0; corner < 8; corner++) {
                double x = geometry[offset + ((corner & 1) == 0 ? 0 : 3)];
                double y = geometry[offset + ((corner & 2) == 0 ? 1 : 4)];
                double z = geometry[offset + ((corner & 4) == 0 ? 2 : 5)];
                bounds.include(transform.m00() * x + transform.m10() * y + transform.m20() * z + transform.m30(),
                    transform.m01() * x + transform.m11() * y + transform.m21() * z + transform.m31(),
                    transform.m02() * x + transform.m12() * y + transform.m22() * z + transform.m32());
            }
        }
        var camera = view.fit(bounds, width, height);
        if (camera == null)
            return false;
        var scale = camera.scale();
        transform.translation(-scale * camera.centerX(), -scale * camera.centerY(), 1_000 - scale * camera.centerZ())
            .scale(scale).mul(boneTransform.set(poseStack.last().pose()));
        poseStack.last().pose().set(transform);
        // A positive uniform camera scale preserves the existing normal directions.
        return poseStack.last().pose().isFinite();
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return delegate.getBuffer(type);
    }
}
