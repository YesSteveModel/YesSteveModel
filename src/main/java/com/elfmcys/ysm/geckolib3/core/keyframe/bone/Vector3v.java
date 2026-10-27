package com.elfmcys.ysm.geckolib3.core.keyframe.bone;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.FloatExpression;
import org.joml.Vector3f;

public class Vector3v {
    private final FloatExpression x;
    private final FloatExpression y;
    private final FloatExpression z;

    public Vector3v(FloatExpression x, FloatExpression y, FloatExpression z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f eval(EntityExpressionRuntime.Evaluation evaluation) {
        return new Vector3f(evaluation.evaluate(x), evaluation.evaluate(y), evaluation.evaluate(z));
    }
}
