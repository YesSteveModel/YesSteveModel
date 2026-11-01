package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.List;
import java.util.function.Consumer;

public class HybridAnimationController<T extends AnimatableEntity<?>> implements IAnimationController<T> {
    private final String name;
    private final T animatableEntity;
    private final CodedAnimationController<T> codedAnimationController;
    private final BedrockAnimationController<T> bedrockAnimationController;

    private boolean isBedrock;
    private IAnimationController<T> activeController;

    public HybridAnimationController(T animatableEntity, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate) {
        this(animatableEntity, name, transitionLengthTicks, animationPredicate, false);
    }

    @Deprecated
    public HybridAnimationController(T animatableEntity, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate, boolean blendRotation) {
        this.name = name;
        this.animatableEntity = animatableEntity;
        this.codedAnimationController = new CodedAnimationController<>(animatableEntity, name, transitionLengthTicks,
            animationPredicate, blendRotation);
        this.bedrockAnimationController =
            new BedrockAnimationController<>(animatableEntity, name, transitionLengthTicks);
        this.activeController = this.codedAnimationController;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getStateName() {
        if (isBedrock) {
            return this.bedrockAnimationController.isBuiltinState()
                ? ("[builtin] " + this.codedAnimationController.getStateName())
                : this.bedrockAnimationController.getStateName();
        } else {
            return this.codedAnimationController.getStateName();
        }
    }

    @Override
    public void updateModel(List<BoneTopLevelSnapshot> modelBones,
        Object2ReferenceMap<String, List<EventProgram>> eventHandlers, java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        var animationControllerData = definitions.apply(this.name);
        if (animationControllerData != null) {
            this.isBedrock = true;
            this.bedrockAnimationController.updateModel(modelBones, animationControllerData);
            this.codedAnimationController.updateModel(modelBones, eventHandlers, definitions);
            this.activeController = this.bedrockAnimationController;
        } else {
            this.isBedrock = false;
            this.codedAnimationController.updateModel(modelBones, eventHandlers, definitions);
            this.bedrockAnimationController.clear();
            this.activeController = this.codedAnimationController;
        }
    }

    @Override
    public Runnable prepareResourceBinding(Object2ReferenceMap<String, List<EventProgram>> eventHandlers,
        java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        var coded = codedAnimationController.prepareResourceBinding(eventHandlers, definitions);
        var bedrock = bedrockAnimationController.prepareResourceBinding(eventHandlers, definitions);
        return () -> {
            coded.run();
            bedrock.run();
        };
    }

    @Override
    public void process(AnimationEvent<T> event, EntityExpressionRuntime.Evaluation evaluation, boolean executeEvents) {
        if (this.isBedrock) {
            this.bedrockAnimationController.process(event, evaluation, executeEvents);
            if (this.bedrockAnimationController.isBuiltinState()) {
                if (this.activeController != this.codedAnimationController) {
                    this.codedAnimationController.setBeginningTransition(
                        this.bedrockAnimationController.getStateData().blendTransition().startNew());
                    this.activeController = this.codedAnimationController;
                }
                this.codedAnimationController.process(event, evaluation, executeEvents);
            } else if (this.activeController != this.bedrockAnimationController) {
                this.codedAnimationController.reset();
                this.activeController = this.bedrockAnimationController;
            }
        } else {
            this.codedAnimationController.process(event, evaluation, executeEvents);
        }
    }

    @Override
    public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {
        this.activeController.visitBoneAnimationQueues(visitor);
    }

    @Override
    public boolean blendRotation() {
        return this.activeController.blendRotation();
    }

    @Override
    public void clear() {
        if (isBedrock) {
            this.bedrockAnimationController.clear();
            this.codedAnimationController.clear();
        } else {
            this.codedAnimationController.clear();
        }
    }

    @Override
    public void invalidateAnimationSource() {
        codedAnimationController.invalidateAnimationSource();
        bedrockAnimationController.invalidateAnimationSource();
    }
}
