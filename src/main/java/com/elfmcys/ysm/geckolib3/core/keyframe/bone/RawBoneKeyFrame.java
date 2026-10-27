package com.elfmcys.ysm.geckolib3.core.keyframe.bone;

import com.elfmcys.ysm.expression.aot.FloatExpression;

@SuppressWarnings("FieldMayBeFinal,unused")
public class RawBoneKeyFrame {
    public double startTick;
    public EasingType easingType;

    public double preX;
    public FloatExpression preXValue;
    public double preY;
    public FloatExpression preYValue;
    public double preZ;
    public FloatExpression preZValue;

    public double postX;
    public FloatExpression postXValue;
    public double postY;
    public FloatExpression postYValue;
    public double postZ;
    public FloatExpression postZValue;

    public boolean contiguous;

    public Vector3v preValue;
    public Vector3v postValue;

    public RawBoneKeyFrame() {}

    private FloatExpression getValue(FloatExpression value, double primitive, boolean isRotation, boolean flip) {
        if (value == null) {
            value = new FloatExpression.Literal((float)primitive);
        }
        if (isRotation) {
            return FloatExpression.scaled(value, (flip ? -1.0f : 1.0f) * ((float)Math.PI / 180.0f));
        }
        return value;
    }

    public void init(boolean isRotation) {
        if (preValue != null) {
            return;
        }

        preValue = new Vector3v(getValue(this.preXValue, this.preX, isRotation, true),
            getValue(this.preYValue, this.preY, isRotation, true),
            getValue(this.preZValue, this.preZ, isRotation, false));
        if (contiguous) {
            postValue = preValue;
        } else {
            postValue = new Vector3v(getValue(this.postXValue, this.postX, isRotation, true),
                getValue(this.postYValue, this.postY, isRotation, true),
                getValue(this.postZValue, this.postZ, isRotation, false));
        }
    }

    public float startTick() {
        return (float)startTick;
    }

    public EasingType easingType() {
        return easingType;
    }

    public Vector3v preValue() {
        return preValue;
    }

    public Vector3v postValue() {
        return postValue;
    }
}
