package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.geckolib3.core.keyframe.AnimationVec3;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;

import java.util.Optional;

public interface IBoneAnimationQueue {
    BoneTopLevelSnapshot getSnapshot();

    Optional<AnimationVec3> pollRotationPoint(EntityExpressionRuntime.Evaluation evaluation);

    Optional<AnimationVec3> pollPositionPoint(EntityExpressionRuntime.Evaluation evaluation);

    Optional<AnimationVec3> pollScalePoint(EntityExpressionRuntime.Evaluation evaluation);
}
