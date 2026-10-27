/*
 * Copyright (c) 2020. Author: Bernie G. (Gecko)
 */
package com.elfmcys.ysm.geckolib3.core.builder;

import com.elfmcys.ysm.geckolib3.core.keyframe.BoneAnimation;
import com.elfmcys.ysm.geckolib3.core.keyframe.event.EventKeyFrame;
import com.elfmcys.ysm.geckolib3.core.keyframe.event.ParticleEventKeyFrame;
import com.elfmcys.ysm.expression.aot.FloatExpression;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import it.unimi.dsi.fastutil.objects.ReferenceLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class Animation {
    public final String name;
    public final float animationLength;
    public final LoopType loop;
    @Nullable
    public final FloatExpression startDelay;
    @Nullable
    public final FloatExpression loopDelay;
    @Nullable
    public final FloatExpression blendWeight;
    @Nullable
    public final Boolean overridePreviousAnimation;
    public final List<BoneAnimation> boneAnimations;
    public final List<EventKeyFrame<String>> soundKeyFrames;
    public final List<ParticleEventKeyFrame> particleKeyFrames;
    public final List<EventKeyFrame<VoidProgram[]>> customInstructionKeyframes;

    // 是否是复制自默认模型的动画，用来纠正 2.4.1 及以前手部动画播放错误的问题
    public boolean isCopiedFromDefaultModel = false;

    public Animation(String name, double animationLength, LoopType loop, @Nullable FloatExpression blendWeight,
        List<BoneAnimation> boneAnimations, List<EventKeyFrame<String>> soundKeyFrames,
        List<EventKeyFrame<VoidProgram[]>> customInstructionKeyframes) {
        this.name = name;
        this.animationLength = (float)animationLength;
        this.loop = loop;
        this.startDelay = new FloatExpression.Literal(0.0f);
        this.loopDelay = new FloatExpression.Literal(0.0f);
        this.blendWeight = blendWeight;
        this.overridePreviousAnimation = true;
        this.boneAnimations = boneAnimations;
        this.soundKeyFrames = soundKeyFrames;
        this.particleKeyFrames = ReferenceLists.emptyList();
        this.customInstructionKeyframes = customInstructionKeyframes;
    }

    public boolean isEmpty() {
        return boneAnimations.isEmpty() && soundKeyFrames.isEmpty() && particleKeyFrames.isEmpty()
            && customInstructionKeyframes.isEmpty();
    }
}
