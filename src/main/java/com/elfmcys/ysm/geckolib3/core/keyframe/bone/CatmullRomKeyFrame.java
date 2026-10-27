package com.elfmcys.ysm.geckolib3.core.keyframe.bone;

import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.joml.Vector3f;

public class CatmullRomKeyFrame extends BoneKeyFrame {
    private final Vector3v leftPoint;
    private final Vector3v endPoint;
    private final Vector3v rightPoint;
    private final Vector3v postPoint;

    public CatmullRomKeyFrame(float startTick, float totalTick, Vector3v leftPoint, Vector3v current, Vector3v endPoint,
        Vector3v postRight, Vector3v postPoint) {
        super(startTick, totalTick, current);
        this.leftPoint = leftPoint;
        this.endPoint = endPoint;
        this.rightPoint = postRight;
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
        Vector3f left = leftPoint.eval(evaluation);
        Vector3f begin = beginPoint.eval(evaluation);
        Vector3f end = endPoint.eval(evaluation);
        Vector3f right = rightPoint.eval(evaluation);
        return MathUtil.catmullRom(percentCompleted, left, begin, end, right);
    }
}
