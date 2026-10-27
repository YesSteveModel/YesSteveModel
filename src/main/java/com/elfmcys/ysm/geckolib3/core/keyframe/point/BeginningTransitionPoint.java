package com.elfmcys.ysm.geckolib3.core.keyframe.point;

import com.elfmcys.ysm.geckolib3.core.keyframe.bone.TransitionKeyFrame;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import org.joml.Vector3f;

public class BeginningTransitionPoint extends AnimationPoint {
    protected final float transitionPercentProgress;
    protected final Vector3f offsetPoint;
    protected final TransitionKeyFrame dstKeyframe;

    public BeginningTransitionPoint(float currentTick, float transitionPercentProgress, float transitionLength,
        Vector3f offsetPoint, TransitionKeyFrame dstKeyframe) {
        super(currentTick, transitionLength);
        this.transitionPercentProgress = transitionPercentProgress;
        this.offsetPoint = offsetPoint;
        this.dstKeyframe = dstKeyframe;
    }

    @Override
    public Vector3f getLerpPoint(EntityExpressionRuntime.Evaluation evaluation) {
        var dst = dstKeyframe.getTransitionDst(evaluation);
        MathUtil.lerpValues(transitionPercentProgress, offsetPoint, dst, dst);
        if (lastLerpResult == null) {
            lastLerpResult = new Vector3f(dst);
        } else {
            lastLerpResult.set(dst);
        }
        return dst;
    }

    public Vector3f getTransitionDst(EntityExpressionRuntime.Evaluation evaluation) {
        return dstKeyframe.getTransitionDst(evaluation);
    }

    public Vector3f getTransitionOffset() {
        return offsetPoint;
    }

    public float getTransitionPercentProgress() {
        return transitionPercentProgress;
    }
}
