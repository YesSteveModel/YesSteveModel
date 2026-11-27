package com.elfmcys.ysm.geckolib3.core.keyframe.point;

import com.elfmcys.ysm.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.joml.Vector3f;

public class KeyFramePoint extends AnimationPoint {
    public final BoneKeyFrame keyframe;

    public KeyFramePoint(float currentTick, BoneKeyFrame keyframe) {
        super(currentTick, keyframe.getTotalTick());
        this.keyframe = keyframe;
    }

    @Override
    public Vector3f getLerpPoint(EntityExpressionRuntime.Evaluation evaluation) {
        return remember(evaluation, keyframe.getLerpPoint(evaluation, getPercentCompleted()));
    }
}
