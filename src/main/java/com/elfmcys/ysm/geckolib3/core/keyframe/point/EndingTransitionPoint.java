package com.elfmcys.ysm.geckolib3.core.keyframe.point;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.joml.Vector3f;

public class EndingTransitionPoint extends AnimationPoint {
    protected final Vector3f srcPoint;

    public EndingTransitionPoint(float currentTick, float transitionLength, Vector3f srcPoint) {
        super(currentTick, transitionLength);
        this.srcPoint = srcPoint;
    }

    @Override
    public float getPercentCompleted() {
        if (totalTick == 0) {
            return currentTick == 0 ? 0 : 1;
        }
        return currentTick / totalTick;
    }

    @Override
    public Vector3f getLerpPoint(EntityExpressionRuntime.Evaluation evaluation) {
        if (lastLerpResult == null) {
            lastLerpResult = new Vector3f(srcPoint);
        } else {
            lastLerpResult.set(srcPoint);
        }
        return srcPoint;
    }
}
