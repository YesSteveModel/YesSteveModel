package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.controller.transition.IBlendTransition;
import com.elfmcys.ysm.geckolib3.core.controller.transition.LinearBlendTransition;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.keyframe.AnimationVec3;
import com.elfmcys.ysm.geckolib3.core.keyframe.BoneAnimationQueue;
import com.elfmcys.ysm.geckolib3.core.keyframe.point.BeginningTransitionPoint;
import com.elfmcys.ysm.geckolib3.core.keyframe.point.EndingTransitionPoint;
import com.elfmcys.ysm.expression.host.ControllerExecutionState;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.expression.aot.FloatExpression;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class CodedAnimationController<T extends AnimatableEntity<?>> implements IAnimationController<T> {
    private final ControllerAnimationCache animationCache;
    private final String name;
    private final IAnimationPredicate<T> animationPredicate;
    private final AnimationPlayer animationPlayer;
    private final boolean blendRotation;
    private final ControllerExecutionState ctx;
    private final float defaultTransitionTicks;
    @Nullable
    private FloatExpression.Program molangPredicate;
    private boolean pause;

    /**
     * 实例化硬编码动画控制器，每个控制器同一时间只能播放一个动画 <br>
     * 你可以为一个实体附加多个动画控制器 <br>
     * 比如一个控制器控制实体大小，另一个控制移动，攻击等等
     *
     * @param animatableEntity 实体
     * @param name 动画控制器名称
     * @param transitionLengthTicks 动画过渡时间（tick）
     */
    public CodedAnimationController(T animatableEntity, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate) {
        this(animatableEntity, name, transitionLengthTicks, animationPredicate, false);
    }

    @Deprecated
    public CodedAnimationController(T animatableEntity, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate, boolean blendRotation) {
        this.name = name;
        this.animationPredicate = animationPredicate;
        this.animationCache = new ControllerAnimationCache(animatableEntity);
        this.animationPlayer = new AnimationPlayer(animatableEntity, transitionLengthTicks, true, animationCache);
        this.defaultTransitionTicks = transitionLengthTicks;
        this.blendRotation = blendRotation;
        this.ctx = new ControllerExecutionState(false, this);
    }

    @Override
    public void process(AnimationEvent<T> event, EntityExpressionRuntime.Evaluation evaluation, boolean executeEvents) {
        event.setCodedAnimationController(this);
        evaluation.setControllerContext(ctx);
        evaluation.setAnimationContext(this.animationPlayer.getAnimationContext());
        try {
            PlayState playState = evalMolangPredicate(evaluation);
            if (playState == null) {
                playState = this.animationPredicate.test(event);
            }
            event.setCodedAnimationController(null);

            if (playState == PlayState.CONTINUE) {
                this.animationPlayer.process(event.renderTicks, evaluation, executeEvents);
                this.pause = false;
            } else if (playState == PlayState.STOP) {
                var state = this.animationPlayer.getState();
                if (state == AnimationState.BEGINNING_TRANSITION || state == AnimationState.RUNNING) {
                    this.animationPlayer.stop(event.renderTicks);
                    this.animationPlayer.indicateReload();
                }
                if (state == AnimationState.ENDING_TRANSITION) {
                    this.animationPlayer.process(event.renderTicks, evaluation, executeEvents);
                }
                this.pause = false;
            } else if (playState == PlayState.PAUSE) {
                this.animationPlayer.process(event.renderTicks, evaluation, false);
                this.animationPlayer.resetBoneAnimationQueues();
                this.pause = true;
            }

            // 最后给 MoLang 上下文设置当前动画状态
            event.getAnimatableEntity().setCodedAnimationStates(this.name, this.animationPlayer.getState());
        } finally {
            event.setCodedAnimationController(null);
            evaluation.endAnimationContext();
        }
    }

    @Nullable
    private PlayState evalMolangPredicate(EntityExpressionRuntime.Evaluation evaluation) {
        if (this.molangPredicate == null) {
            return null;
        }

        this.ctx.setAnyAnimationFinished(this.animationPlayer.currentAnimFinished());
        this.ctx.setAllAnimationsFinished(this.animationPlayer.currentAnimFinished());

        return evaluation.codedController(this.molangPredicate);
    }

    @Override
    public void updateModel(List<BoneTopLevelSnapshot> modelBones,
        Object2ReferenceMap<String, List<EventProgram>> eventHandlers, java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        this.animationPlayer.updateModel(modelBones);
        this.animationCache.clear();
        this.animationPlayer.setBeginningTransition(new LinearBlendTransition(defaultTransitionTicks));
        prepareResourceBinding(eventHandlers, definitions).run();
    }

    @Override
    public Runnable prepareResourceBinding(Object2ReferenceMap<String, List<EventProgram>> eventHandlers,
        java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        FloatExpression.Program predicate = null;
        var predictEventName = this.name.replace(".", "_ctrl_");
        var handlers = eventHandlers.get(predictEventName);
        if (handlers != null && !handlers.isEmpty()) {
            var first = handlers.get(0);
            if (first instanceof FloatExpression.Program program) {
                predicate = program;
            }
        }
        var prepared = predicate;
        return () -> this.molangPredicate = prepared;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public String getStateName() {
        // 硬编码控制器没有状态，返回自己名称+正在播放的动画
        if (animationPlayer.getState() == AnimationState.IDLE) {
            return "Coded";
        } else {
            return "Coded -> " + animationPlayer.getCurrentAnim().name;
        }
    }

    public void setAnimation(String animationName) {
        this.animationPlayer.setAnimation(animationName, null);
    }

    public void setAnimation(String animationName, @Nullable LoopType loopType) {
        this.animationPlayer.setAnimation(animationName, loopType);
    }

    public void setBeginningTransitionLength(float sec) {
        if (this.animationPlayer.getBeginningTransitionLength() != sec) {
            this.animationPlayer.setBeginningTransition(new LinearBlendTransition(sec));
        }
    }

    public void setBeginningTransition(IBlendTransition blendTransition) {
        this.animationPlayer.setBeginningTransition(blendTransition);
    }

    @Override
    public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {
        if (!this.pause) {
            for (var queue : this.animationPlayer.getActiveBoneAnimQueues()) {
                visitor.accept(new SingleBoneAnimationQueue(queue));
            }
        }
    }

    public void reset() {
        this.animationPlayer.reset();
    }

    public void clear() {
        this.animationCache.clear();
        this.animationPlayer.clear();
        this.molangPredicate = null;
    }

    @Override
    public void invalidateAnimationSource() {
        animationCache.clear();
        animationPlayer.invalidateAnimationSource();
    }

    public void indicateReload() {
        this.animationPlayer.indicateReload();
    }

    public void stop(float renderTicks) {
        this.animationPlayer.stop(renderTicks);
    }

    public boolean isAnimFinished() {
        return this.animationPlayer.currentAnimFinished();
    }

    public void stopPlayingSounds() {
        this.animationPlayer.stopPlayingSounds();
    }

    @Override
    @Deprecated
    public boolean blendRotation() {
        // TODO: 仅临时缓解，未完全修复过渡动画混合问题。
        return blendRotation && animationPlayer.getState() == AnimationState.RUNNING;
    }

    private record SingleBoneAnimationQueue(BoneAnimationQueue queue) implements IBoneAnimationQueue {
        @Override
        public BoneTopLevelSnapshot getSnapshot() {
            return queue.topLevelSnapshot;
        }

        @Override
        public Optional<AnimationVec3> pollRotationPoint(EntityExpressionRuntime.Evaluation evaluation) {
            var point = this.queue.rotation;

            if (point == null) {
                return Optional.empty();
            }

            AnimationVec3 pointValue;
            if (point instanceof EndingTransitionPoint endingPoint) {
                pointValue = new AnimationVec3(point.getLerpPoint(evaluation));
                pointValue.setEndingTransitionPercentProgressIfLess(endingPoint.getPercentCompleted());
                var weight = queue.getBlendWeight();
                if (weight != 1) {
                    pointValue.mul(weight);
                }
            } else if (point instanceof BeginningTransitionPoint beginningPoint) {
                var dst = beginningPoint.getTransitionDst(evaluation).mul(queue.getBlendWeight());
                MathUtil.lerpRotationValues(beginningPoint.getTransitionPercentProgress(),
                    beginningPoint.getTransitionOffset(), dst, queue.topLevelSnapshot.bone.getInitialRotation(), dst);
                pointValue = new AnimationVec3(dst);
                pointValue.setEndingTransitionPercentProgressIfLess(0);
            } else {
                pointValue = new AnimationVec3(point.getLerpPoint(evaluation));
                var weight = queue.getBlendWeight();
                if (weight != 1) {
                    pointValue.mul(weight);
                }
                pointValue.setEndingTransitionPercentProgressIfLess(0);
            }

            return Optional.of(pointValue);
        }

        @Override
        public Optional<AnimationVec3> pollPositionPoint(EntityExpressionRuntime.Evaluation evaluation) {
            var point = this.queue.position;

            if (point == null) {
                return Optional.empty();
            }

            var pointValue = new AnimationVec3(point.getLerpPoint(evaluation));
            var weight = queue.getBlendWeight();
            if (point instanceof EndingTransitionPoint endingPoint) {
                pointValue.setEndingTransitionPercentProgressIfLess(endingPoint.getPercentCompleted());
            } else {
                if (point instanceof BeginningTransitionPoint beginningPoint) {
                    weight = MathUtil.lerpValues(beginningPoint.getTransitionPercentProgress(), 1, weight);
                }
                pointValue.setEndingTransitionPercentProgressIfLess(0);
            }

            if (weight != 1) {
                pointValue.mul(weight);
            }
            return Optional.of(pointValue);
        }

        @Override
        public Optional<AnimationVec3> pollScalePoint(EntityExpressionRuntime.Evaluation evaluation) {
            var point = this.queue.scale;

            if (point == null) {
                return Optional.empty();
            }

            var pointValue = new AnimationVec3(point.getLerpPoint(evaluation));
            var weight = queue.getBlendWeight();
            if (point instanceof EndingTransitionPoint endingPoint) {
                pointValue.setEndingTransitionPercentProgressIfLess(endingPoint.getPercentCompleted());
            } else {
                if (point instanceof BeginningTransitionPoint beginningPoint) {
                    weight = MathUtil.lerpValues(beginningPoint.getTransitionPercentProgress(), 1, weight);
                }
                pointValue.setEndingTransitionPercentProgressIfLess(0);
            }

            if (weight != 1) {
                MathUtil.computeWeightedScale(pointValue, weight, pointValue);
            }
            return Optional.of(pointValue);
        }
    }
}
