package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.elfmcys.ysm.natives.render.RenderContextType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Derived data belongs to the accepted resource, never to its logical name or wrapper. */
final class FirstPersonModelState {
    private ModelRenderTarget target;
    private GeoModel model;
    private float[] initialAttributes;
    private int leftRoot = -1;
    private int rightRoot = -1;
    private int backgroundRoot = -1;
    private final NativeModelState[] outputs = new NativeModelState[3];
    private final boolean[] prepared = new boolean[3];
    private final long[] revisions = new long[3];
    private float armSample = Float.NaN;
    private float backgroundSample = Float.NaN;

    void bind(ModelRenderTarget target, GeoModel model) {
        if (this.target == target && this.model == model) {
            return;
        }
        clear();
        if (target == null || model == null) {
            return;
        }
        var bones = model.sortedBones();
        var initial = new float[bones.size() * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
        for (int i = 0; i < bones.size(); i++) {
            var bone = bones.get(i);
            AnimatedGeoBone.initializeAttributes(bone, initial, i);
            switch (bone.name()) {
                case "LeftArm" -> leftRoot = i;
                case "RightArm" -> rightRoot = i;
                case "Background" -> backgroundRoot = i;
            }
        }
        this.target = target;
        this.model = model;
        initialAttributes = initial;
    }

    boolean hasPart(FirstPersonPart part) {
        return model != null && root(part) >= 0;
    }

    private int root(FirstPersonPart part) {
        return switch (part) {
            case LEFT_ARM -> leftRoot;
            case RIGHT_ARM -> rightRoot;
            case BACKGROUND -> backgroundRoot;
        };
    }

    void prepareArms(float sample, boolean poseUpdated) {
        if (armSample != sample || poseUpdated) {
            invalidateArms();
            armSample = sample;
        }
    }

    long revision(FirstPersonPart part) {
        return revisions[part.ordinal()];
    }

    void invalidateArms() {
        revisions[0]++;
        revisions[1]++;
        prepared[FirstPersonPart.LEFT_ARM.ordinal()] = false;
        prepared[FirstPersonPart.RIGHT_ARM.ordinal()] = false;
    }

    void prepareBackground(float sample) {
        if (backgroundSample != sample) {
            revisions[FirstPersonPart.BACKGROUND.ordinal()]++;
            prepared[FirstPersonPart.BACKGROUND.ordinal()] = false;
            backgroundSample = sample;
        }
    }

    boolean render(FirstPersonPart part, float[] animatedAttributes, VertexConsumer consumer, PoseStack.Pose pose,
        int light, int overlay, int color) {
        if (!hasPart(part)) {
            return false;
        }
        var index = part.ordinal();
        var output = outputs[index];
        if (output == null) {
            outputs[index] = output = NativeModelState.create();
        }
        if (!prepared[index] || !output.isValid()) {
            prepared[index] = false;
            var attributes = part == FirstPersonPart.BACKGROUND ? initialAttributes : animatedAttributes;
            if (!output.extractSubtree(model.bakedModel(), attributes, root(part))) {
                return false;
            }
            prepared[index] = true;
        }
        return NativeRenderer.render(consumer, pose, output, output.getTotalVertexCount(), light, overlay, color,
            RenderContextType.LEVEL);
    }

    void clear() {
        target = null;
        model = null;
        initialAttributes = null;
        leftRoot = rightRoot = backgroundRoot = -1;
        armSample = backgroundSample = Float.NaN;
        Throwable failure = null;
        for (int i = 0; i < outputs.length; i++) {
            revisions[i]++;
            prepared[i] = false;
            var previous = outputs[i];
            outputs[i] = null;
            if (previous != null) {
                try {
                    previous.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (failure == null)
                        failure = closeFailure;
                    else
                        failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }
}
