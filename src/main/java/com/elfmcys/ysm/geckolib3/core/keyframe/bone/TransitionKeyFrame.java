package com.elfmcys.ysm.geckolib3.core.keyframe.bone;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.joml.Vector3f;

public class TransitionKeyFrame extends BoneKeyFrame {
    private final Vector3v postPoint;

    public TransitionKeyFrame(float firstStartTick, Vector3v firstPoint, Vector3v postPoint) {
        super(0, firstStartTick, firstPoint);
        this.postPoint = postPoint;
    }

    @Override
    public Vector3f getLerpPoint(EntityExpressionRuntime.Evaluation evaluation, float percentCompleted) {
        if (!isEnd(percentCompleted)) {
            return beginPoint.eval(evaluation);
        } else {
            return postPoint.eval(evaluation);
        }
    }

    public Vector3f getTransitionDst(EntityExpressionRuntime.Evaluation evaluation) {
        return this.beginPoint.eval(evaluation);
    }
}
