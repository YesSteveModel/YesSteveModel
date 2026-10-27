package com.elfmcys.ysm.geckolib3.core.keyframe.bone;

import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.joml.Vector3f;

public class LinearKeyFrame extends BoneKeyFrame {
    private final Vector3v endPoint;
    private final Vector3v postPoint;

    public LinearKeyFrame(float startTick, float totalTick, Vector3v beginPoint, Vector3v endPoint,
        Vector3v postPoint) {
        super(startTick, totalTick, beginPoint);
        this.endPoint = endPoint;
        this.postPoint = postPoint;
    }

    @Override
    public Vector3f getLerpPoint(EntityExpressionRuntime.Evaluation evaluation, float percentCompleted) {
        if (isBegin(percentCompleted)) {
            return beginPoint.eval(evaluation);
        }
        if (isEnd(percentCompleted)) {
            return postPoint.eval(evaluation);
        }
        Vector3f begin = beginPoint.eval(evaluation);
        Vector3f end = endPoint.eval(evaluation);
        return MathUtil.lerpValues(percentCompleted, begin, end);
    }
}
