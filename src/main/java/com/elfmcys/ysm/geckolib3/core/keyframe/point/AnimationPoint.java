/*
 * Copyright (c) 2020. Author: Bernie G. (Gecko)
 */

package com.elfmcys.ysm.geckolib3.core.keyframe.point;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import org.joml.Vector3f;

public abstract class AnimationPoint {
    /**
     * 当前关键帧播放进度
     */
    public final float currentTick;
    /**
     * 当前关键帧总长度
     */
    public final float totalTick;
    public Vector3f lastLerpResult;

    public AnimationPoint(float currentTick, float totalTick) {
        this.currentTick = currentTick;
        this.totalTick = totalTick;
    }

    public float getPercentCompleted() {
        return totalTick == 0 ? 1 : (currentTick / totalTick);
    }

    public abstract Vector3f getLerpPoint(EntityExpressionRuntime.Evaluation evaluation);
}
