/*
 * Copyright (c) 2020. Author: Bernie G. (Gecko)
 */

package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.controller.transition.IBlendTransition;
import com.elfmcys.ysm.geckolib3.core.controller.transition.LinearBlendTransition;
import com.elfmcys.ysm.geckolib3.core.event.InstructionKeyFrameExecutor;
import com.elfmcys.ysm.geckolib3.core.event.SoundKeyframeExecutor;
import com.elfmcys.ysm.geckolib3.core.keyframe.*;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.TransitionKeyFrame;
import com.elfmcys.ysm.geckolib3.core.keyframe.point.*;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.expression.host.AnimationExecutionState;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneSnapshot;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.util.OrderedSegmentSearcher;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.*;

public class AnimationPlayer {
    /**
     * 模型所有骨骼
     */
    private final Int2ReferenceOpenHashMap<BoneAnimationQueue> boneAnimQueues = new Int2ReferenceOpenHashMap<>();
    /**
     * 当前有动画的骨骼
     */
    private final ReferenceArrayList<BoneAnimationQueue> activeBoneAnimQueues = new ReferenceArrayList<>();
    /**
     * 与动画播放相关的 molang 上下文
     */
    private final AnimationExecutionState animationContext = new AnimationExecutionState();
    /**
     * 实体对象
     */
    private final AnimatableEntity<?> animatableEntity;
    private final ControllerAnimationCache animationCache;
    /**
     * 是否对缩放帧禁用起始过渡
     */
    private boolean disableBeginningTransitionScale;
    /**
     * 尾过渡动画长度
     * <p>
     * 一定不能小于 1
     */
    private final float endingTransitionLength = AnimationData.DEFAULT_ENDING_TRANSITION_LENGTH;

    private AnimationState state = AnimationState.IDLE;
    private float animTickOffset;
    private IBlendTransition beginningTransition;
    private float endingTransitionSrcTick;

    private Pair<@Nullable LoopType, String> lastSetAnim = null;
    private boolean stableRequest;
    private Animation lastResolvedAnimation;
    private Pair<LoopType, Animation> nextAnim = null;

    private Animation currentAnim;
    private LoopType currentLoopType;
    private InstructionKeyFrameExecutor instructionKeyFrameExecutor;
    private SoundKeyframeExecutor soundKeyFrameExecutor;
    private boolean currentAnimFinished = true;

    /**
     * 实例化动画播放器，每个播放器同一时间只能播放一个动画
     *
     * @param animatableEntity 实体
     * @param transitionLengthTicks 动画过渡时间（tick）
     */
    AnimationPlayer(AnimatableEntity<?> animatableEntity, float transitionLengthTicks,
        ControllerAnimationCache animationCache) {
        this(animatableEntity, transitionLengthTicks, false, animationCache);
    }

    /**
     * 实例化动画播放器，每个播放器同一时间只能播放一个动画
     *
     * @param animatableEntity 实体
     * @param transitionLengthTicks 动画过渡时间（tick）
     * @param disableBeginningTransitionScale 是否对缩放帧禁用起始过渡
     */
    AnimationPlayer(AnimatableEntity<?> animatableEntity, float transitionLengthTicks,
        boolean disableBeginningTransitionScale, ControllerAnimationCache animationCache) {
        this.animatableEntity = animatableEntity;
        this.animationCache = animationCache;
        this.beginningTransition = new LinearBlendTransition(transitionLengthTicks);
        this.disableBeginningTransitionScale = disableBeginningTransitionScale;
        this.animTickOffset = 0.0f;
    }

    /**
     * 切换模型，重置所有状态
     */
    public void updateModel(List<BoneTopLevelSnapshot> boneList) {
        clear();
        for (BoneTopLevelSnapshot bone : boneList) {
            this.boneAnimQueues.put(bone.name, new BoneAnimationQueue(bone));
        }
    }

    public void setAnimation(@Nullable String animationName) {
        setAnimation(animationName, null);
    }

    /**
     * 此方法设置当前动画，
     * <p>
     * 你可以每帧运行此方法，如果每次都传入相同的动画，它将不会重新启动，
     * <p>
     * 如果需要重新启动，在该调用之前额外调用 indicateReload 即可。
     * <p>
     * 此外，它还可以在动画状态之间平滑过渡。
     */
    public void setAnimation(@Nullable String animationName, @Nullable LoopType loopTypeOverride) {
        if (animationName == null) {
            reset();
            return;
        }

        var sameRequest = lastSetAnim != null && lastSetAnim.getSecond().equals(animationName)
            && lastSetAnim.getFirst() == loopTypeOverride;
        if (sameRequest && stableRequest) {
            return;
        }

        var resolution = animationCache.resolve(animationName);
        var animation = resolution.animation();
        this.lastSetAnim = new Pair<>(loopTypeOverride, animationName);
        this.stableRequest = resolution.cacheable();
        if (sameRequest && animation != null && animation == lastResolvedAnimation)
            return;
        this.lastResolvedAnimation = animation;
        this.nextAnim = null;
        resetToIdle();
        if (animation == null) {
            return;
        }

        this.nextAnim = new Pair<>(loopTypeOverride != null ? loopTypeOverride : animation.loop, animation);
    }

    /**
     * 此方法每帧调用一次，以便填充动画点队列并处理动画状态逻辑。
     *
     * @param renderTicks 当前 tick + 插值 tick
     */
    public void process(final float renderTicks, EntityExpressionRuntime.Evaluation evaluation, boolean executeEvents) {
        retryAnimation();
        evaluation.setAnimationContext(animationContext);
        try {
            var animTicks = getAnimTicks(renderTicks);

            // 尾过渡结束后回到待机状态
            if (this.state == AnimationState.ENDING_TRANSITION && animTicks >= endingTransitionLength) {
                resetToIdle();
            }

            /*  PLAY_ONCE 结束后转入尾过渡状态
             *  为了最后一个指令帧得以执行，以及基岩版控制器下一个状态的过渡动画能够正确衔接，需要延迟一帧停止动画，
             *  所以这块判断要放在上一块的后面
             */
            if (this.state == AnimationState.RUNNING && currentLoopType == LoopType.PLAY_ONCE
                && animTicks >= currentAnim.animationLength) {
                resetEventKeyframes(evaluation, executeEvents);
                setupEndingTransition(renderTicks);
                animTicks = getAnimTicks(renderTicks);
            }

            if (this.state == AnimationState.IDLE) {
                // 没有动画正在播放时，尝试切换下一个动画
                if (!loadNextAnim()) {
                    return;
                }

                this.animTickOffset = renderTicks;
                animTicks = 0;

                if (this.beginningTransition.length() > 0) {
                    this.state = AnimationState.BEGINNING_TRANSITION;
                } else {
                    // 如果过渡长度为 0，直接开始播放
                    this.state = AnimationState.RUNNING;
                }
            }

            resetBoneAnimationQueues();

            if (this.state == AnimationState.BEGINNING_TRANSITION) {
                if (animTicks < this.beginningTransition.length()) {
                    // 更新起始过渡动画
                    animationContext.setAnimTime(0);
                    runBeginningTransition(evaluation, animTicks);
                    return;
                } else {
                    // 如果当前时间超过了过渡时长，则正式开始播放
                    animTicks = animTicks - this.beginningTransition.length();
                    this.animTickOffset = renderTicks - animTicks;
                    this.state = AnimationState.RUNNING;
                }
            }

            // 播放中
            if (this.state == AnimationState.RUNNING) {
                if (animTicks > this.currentAnim.animationLength) {
                    this.currentAnimFinished = true;

                    if (currentLoopType == LoopType.LOOP) {
                        // 对于循环动画，本轮播放结束后重置 tick offset，开始下一轮循环
                        if (currentAnim.animationLength > 0) {
                            animTicks = animTicks % currentAnim.animationLength;
                        } else {
                            animTicks = 0;
                        }
                        resetEventKeyframes(evaluation, executeEvents);
                        animTickOffset = renderTicks - animTicks;
                    } else if (currentLoopType == LoopType.HOLD_ON_LAST_FRAME) {
                        // 对于停在最后一帧的动画，播放完成后 anim ticks 锁定在最后一帧的时间
                        animTicks = currentAnim.animationLength;
                    } else {
                        // PLAY_ONCE 类型在上面就已经处理过了
                    }
                }

                animationContext.setAnimTime(animTicks / 20f);
                // 更新事件关键帧（指令、音效、粒子等）
                executeEventKeyframes(evaluation, animTicks, executeEvents);
                // 更新动画
                runAnimation(evaluation, animTicks);
                return;
            }

            if (this.state == AnimationState.ENDING_TRANSITION) {
                if (animTicks > endingTransitionLength) {
                    animTicks = endingTransitionLength;
                }

                animationContext.setAnimTime(endingTransitionSrcTick / 20f);
                // 更新结尾过渡动画
                runEndingTransition(evaluation, animTicks);
            }
        } finally {
            evaluation.endAnimationContext();
        }
    }

    public AnimationExecutionState getAnimationContext() {
        return this.animationContext;
    }

    /**
     * 执行剩余的事件关键帧，并重置到初始状态
     */
    private void resetEventKeyframes(EntityExpressionRuntime.Evaluation evaluation, boolean executeEvents) {
        animationContext.setAnimTime(currentAnim.animationLength / 20f);
        if (executeEvents && this.instructionKeyFrameExecutor != null) {
            this.instructionKeyFrameExecutor.executeRemaining(evaluation);
            this.instructionKeyFrameExecutor.reset();
        }
        if (this.soundKeyFrameExecutor != null) {
            this.soundKeyFrameExecutor.reset();
        }
    }

    /**
     * 执行时间关键帧指指定时间点
     */
    private void executeEventKeyframes(EntityExpressionRuntime.Evaluation evaluation, float animTicks,
        boolean executeEvents) {
        if (soundKeyFrameExecutor != null) {
            soundKeyFrameExecutor.executeTo(animatableEntity, animTicks, executeEvents);
        }
        if (executeEvents && instructionKeyFrameExecutor != null) {
            instructionKeyFrameExecutor.executeTo(evaluation, animTicks);
        }
    }

    /**
     * 保存当前姿态作为起始点，准备开始尾过渡； 必须在 resetBoneAnimationQueues 之前调用
     */
    private void setupEndingTransition(float renderTicks) {
        if (state == AnimationState.RUNNING || state == AnimationState.BEGINNING_TRANSITION) {
            var animTick = getAnimTicks(renderTicks);
            for (var queue : activeBoneAnimQueues) {
                if (queue.rotation != null && queue.rotation.lastLerpResult != null) {
                    queue.rotationOffset = new Vector3f(queue.rotation.lastLerpResult);
                }

                if (queue.position != null && queue.position.lastLerpResult != null) {
                    queue.positionOffset = new Vector3f(queue.position.lastLerpResult);
                }

                if (queue.scale != null && queue.scale.lastLerpResult != null) {
                    queue.scaleOffset = new Vector3f(queue.scale.lastLerpResult);
                }
            }

            this.animTickOffset = renderTicks;
            if (state == AnimationState.RUNNING) {
                if (animTick > currentAnim.animationLength) {
                    animTick = currentAnim.animationLength;
                }
                this.endingTransitionSrcTick = animTick;
            } else {
                this.endingTransitionSrcTick = 0;
            }
            this.currentAnimFinished = true;
            this.state = AnimationState.ENDING_TRANSITION;
        }
    }

    private void runBeginningTransition(EntityExpressionRuntime.Evaluation evaluation, float transitionTicks) {
        var blendWeight = currentAnim.blendWeight != null ? evaluation.evaluate(currentAnim.blendWeight) : 1;
        var percentProgress = this.beginningTransition.get(transitionTicks);

        for (BoneAnimationQueue boneAnimationQueue : activeBoneAnimQueues) {
            boneAnimationQueue.setBlendWeight(blendWeight);
            BoneSnapshot transitionOffset = boneAnimationQueue.transitionOffset();

            // 添加即将出现的动画的初始位置，以便模型转换到新动画的初始状态
            if (boneAnimationQueue.rotationKeyFrames != null) {
                boneAnimationQueue.rotation = getBeginningTransitionPointAtTick(boneAnimationQueue.rotationKeyFrames,
                    transitionTicks, percentProgress, transitionOffset.rotation);
            }

            if (boneAnimationQueue.positionKeyFrames != null) {
                boneAnimationQueue.position = getBeginningTransitionPointAtTick(boneAnimationQueue.positionKeyFrames,
                    transitionTicks, percentProgress, transitionOffset.position);
            }

            if (boneAnimationQueue.scaleKeyFrames != null) {
                boneAnimationQueue.scale = disableBeginningTransitionScale
                    ? getBeginningTransitionPointAtTick(boneAnimationQueue.scaleKeyFrames,
                        this.beginningTransition.length(), 1.0f, transitionOffset.scale)
                    : getBeginningTransitionPointAtTick(boneAnimationQueue.scaleKeyFrames, transitionTicks,
                        percentProgress, transitionOffset.scale);
            }
        }
    }

    private void runAnimation(EntityExpressionRuntime.Evaluation evaluation, float animTicks) {
        // 循环遍历当前动画中的每个骨骼动画并处理值
        var blendWeight = currentAnim.blendWeight != null ? evaluation.evaluate(currentAnim.blendWeight) : 1;
        for (BoneAnimationQueue boneAnimationQueue : activeBoneAnimQueues) {
            boneAnimationQueue.setBlendWeight(blendWeight);

            if (boneAnimationQueue.rotationKeyFrames != null) {
                boneAnimationQueue.rotation = getKeyFramePointAtTick(boneAnimationQueue.rotationKeyFrames, animTicks);
            }

            if (boneAnimationQueue.positionKeyFrames != null) {
                boneAnimationQueue.position = getKeyFramePointAtTick(boneAnimationQueue.positionKeyFrames, animTicks);
            }

            if (boneAnimationQueue.scaleKeyFrames != null) {
                boneAnimationQueue.scale = getKeyFramePointAtTick(boneAnimationQueue.scaleKeyFrames, animTicks);
            }
        }
    }

    private void runEndingTransition(EntityExpressionRuntime.Evaluation evaluation, float transitionTicks) {
        var blendWeight = currentAnim.blendWeight != null ? evaluation.evaluate(currentAnim.blendWeight) : 1;

        for (BoneAnimationQueue boneAnimationQueue : activeBoneAnimQueues) {
            boneAnimationQueue.setBlendWeight(blendWeight);

            if (boneAnimationQueue.rotationOffset != null) {
                boneAnimationQueue.rotation = getEndingTransitionPointAtTick(transitionTicks,
                    boneAnimationQueue.rotationOffset, boneAnimationQueue.disableEndingTransition);
            }

            if (boneAnimationQueue.positionOffset != null) {
                boneAnimationQueue.position = getEndingTransitionPointAtTick(transitionTicks,
                    boneAnimationQueue.positionOffset, boneAnimationQueue.disableEndingTransition);
            }

            if (boneAnimationQueue.scaleOffset != null) {
                boneAnimationQueue.scale = getEndingTransitionPointAtTick(transitionTicks,
                    boneAnimationQueue.scaleOffset, boneAnimationQueue.disableEndingTransition);
            }
        }
    }

    public void resetBoneAnimationQueues() {
        if (this.state != AnimationState.IDLE) {
            for (BoneAnimationQueue queue : activeBoneAnimQueues) {
                queue.resetQueues();
            }
        }
    }

    /**
     * 返回当前关键帧进度点
     */
    private KeyFramePoint getKeyFramePointAtTick(OrderedSegmentSearcher<BoneKeyFrame> frames, float tick) {
        var frame = frames.search(tick);
        return new KeyFramePoint(tick - frame.getStartTick(), frame);
    }

    /**
     * 返回起始过渡点； 由于自定义过渡曲线的原因， 过渡进度需要单独传，而不能用 tick / length
     */
    private BeginningTransitionPoint getBeginningTransitionPointAtTick(OrderedSegmentSearcher<BoneKeyFrame> frames,
        float tick, float transitionPercentProgress, Vector3f offsetPoint) {
        var dstFrame = frames.search(0);
        return new BeginningTransitionPoint(tick, transitionPercentProgress, this.beginningTransition.length(),
            offsetPoint, (TransitionKeyFrame)dstFrame);
    }

    /**
     * 返回结尾过渡点
     */
    private EndingTransitionPoint getEndingTransitionPointAtTick(float tick, Vector3f offsetPoint,
        boolean disableEndingTransition) {
        return new EndingTransitionPoint(tick, disableEndingTransition ? 0f : endingTransitionLength, offsetPoint);
    }

    /**
     * 尝试加载下个动画
     */
    private boolean loadNextAnim() {
        var next = this.nextAnim;
        if (next == null) {
            return false;
        }
        this.nextAnim = null;

        this.currentAnim = next.getSecond();
        this.currentLoopType = next.getFirst();
        this.currentAnimFinished = false;

        for (BoneAnimation animation : currentAnim.boneAnimations) {
            BoneAnimationQueue queue = boneAnimQueues.get(animation.bonePooledName);
            if (queue == null) {
                continue;
            }
            queue.setActive(animation, !animation.scaleKeyFrames.isEmpty());
            activeBoneAnimQueues.add(queue);
        }
        instructionKeyFrameExecutor = new InstructionKeyFrameExecutor(currentAnim.customInstructionKeyframes);
        soundKeyFrameExecutor = new SoundKeyframeExecutor(currentAnim.soundKeyFrames, animationContext.soundManager());

        return true;
    }

    /**
     * 停止播放动画并进入 IDLE 状态，注意下次 setAnimation 不可播放相同的动画。
     */
    private void resetToIdle() {
        if (this.state != AnimationState.IDLE) {
            this.state = AnimationState.IDLE;
            if (soundKeyFrameExecutor != null) {
                soundKeyFrameExecutor.reset();
            }
            soundKeyFrameExecutor = null;
            instructionKeyFrameExecutor = null;

            for (var queue : this.activeBoneAnimQueues) {
                queue.setInactive();
            }
            this.activeBoneAnimQueues.clear();

            this.currentAnim = null;
            this.currentAnimFinished = true;
        }
    }

    /**
     * 当前动画，仅在 IDLE 状态下为 null
     */
    @Nullable
    public Animation getCurrentAnim() {
        return this.currentAnim;
    }

    /**
     * 当前动画播放器状态
     */
    public AnimationState getState() {
        return this.state;
    }

    /**
     * 当前模型所有骨骼动画队列
     */
    public Int2ReferenceOpenHashMap<BoneAnimationQueue> getAllBoneAnimQueues() {
        return this.boneAnimQueues;
    }

    /**
     * 当前模型有动画的骨骼动画队列
     */
    public ReferenceArrayList<BoneAnimationQueue> getActiveBoneAnimQueues() {
        return this.activeBoneAnimQueues;
    }

    /**
     * 检查当前时间下，是否已完成动画的播放。
     * <p>
     * 循环动画第一次结束后，停在最后一帧的动画停止是，都会为 true。
     * <p>
     * Transition 状态下恒为 false，Idle 状态下恒为 true。
     */
    public boolean currentAnimFinished() {
        return currentAnimFinished;
    }

    public void setBeginningTransition(IBlendTransition beginningTransition) {
        this.beginningTransition = beginningTransition;
    }

    public void setDisableBeginningTransitionScale(boolean value) {
        this.disableBeginningTransitionScale = value;
    }

    public float getBeginningTransitionLength() {
        return this.beginningTransition.length() * 20f;
    }

    public float getAnimTicks(float renderTicks) {
        return Math.max(renderTicks - this.animTickOffset, 0.0f);
    }

    /**
     * 停止所有正在播放的音频。
     */
    public void stopPlayingSounds() {
        if (this.soundKeyFrameExecutor != null) {
            this.soundKeyFrameExecutor.stopAll();
        }
    }

    /**
     * 重置至初始状态
     */
    public void reset() {
        this.lastSetAnim = null;
        this.stableRequest = false;
        this.lastResolvedAnimation = null;
        this.nextAnim = null;
        resetToIdle();
    }

    /**
     * 清空所有状态
     */
    public void clear() {
        // Committed playback belongs to its sound owner after this controller ends.
        soundKeyFrameExecutor = null;
        reset();
        this.boneAnimQueues.clear();
    }

    /**
     * 指示下次 setAnimation 可传入相同的动画以实现重载
     */
    public void indicateReload() {
        this.lastSetAnim = null;
    }

    void invalidateAnimationSource() {
        // Verified playback survives resource rebinding; a previous absence does not.
        if (lastResolvedAnimation == null)
            stableRequest = false;
    }

    void retryAnimation() {
        if (lastSetAnim != null && !stableRequest)
            setAnimation(lastSetAnim.getSecond(), lastSetAnim.getFirst());
    }

    /**
     * 以当前点为起始点进入尾过渡状态，随后停止播放
     */
    public void stop(float renderTicks) {
        setupEndingTransition(renderTicks);
    }
}
