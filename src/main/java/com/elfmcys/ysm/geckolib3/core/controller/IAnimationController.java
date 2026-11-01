package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.List;
import java.util.function.Consumer;

/**
 * Advances one owner's controller state and exposes its current pose queues.
 *
 * <p>Execution: all calls use the owning entity's exclusive execution or installation window.
 * Controller evaluation is synchronous and must not reenter the owner's public lifecycle.
 *
 * <p>Ownership: events, evaluation contexts, resource definitions, and queues are borrowed.
 * A controller must not share mutable playback state with another owner.
 *
 * <p>Seam: refines [playback state](docs/architecture/animation/controllers-and-playback.md#播放状态) and [resolution and playback lifetimes](docs/architecture/animation/controllers-and-playback.md#解析缓存与播放寿命).
 */
public interface IAnimationController<T extends AnimatableEntity<?>> {
    /**
     * 获取动画控制器名称
     */
    String getName();

    /**
     * 获取控制器当时所处状态
     */
    String getStateName();

    /**
     * 更新模型
     */
    void updateModel(List<BoneTopLevelSnapshot> modelRendererList,
        Object2ReferenceMap<String, List<EventProgram>> eventHandlers, java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions);

    /**
     * Prepares resource references for a later activation callback in the owner's installation window.
     *
     * <p>Ownership: the callback retains borrowed program and definition references from the candidate resource.
     * The caller must keep that resource alive through callback use.
     *
     * @return the synchronous binding callback; the default implementation changes no controller state
     */
    default Runnable prepareResourceBinding(Object2ReferenceMap<String, List<EventProgram>> eventHandlers,
        java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        return () -> {};
    }

    default void invalidateAnimationSource() {}

    /**
     * Advances this controller inside the caller's open animation evaluation.
     *
     * <p>Requires: the event and evaluation describe this controller's exact owner and installation.
     * executeEvents selects event dispatch; it does not grant ACTION permission to an expression.
     *
     * <p>Failure: a failed program can invalidate the whole effect batch even when a typed fallback lets Java evaluation continue.
     */
    void process(AnimationEvent<T> event, EntityExpressionRuntime.Evaluation evaluation, boolean executeEvents);

    /**
     * Visits the current mutable pose queues synchronously in the owner's execution window.
     *
     * <p>Ownership: the visitor must not retain queues beyond the current pose consumption or mutate the controller during traversal.
     * Empty traversal means that this controller contributes no current queues.
     */
    void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor);

    /**
     * 这个参数是为了处理一个 YSM 遗留问题
     * <p>
     * 曾经某个版本的 YSM 加入了并行动画的混合功能，但是仅混合动画旋转数值
     *
     * @return 如果是 CodedAnimationController，那么仅并行动画返回 true
     *         <p>
     *         如果使用动画控制器，那么将永远返回 false
     */
    @Deprecated
    default boolean blendRotation() {
        return false;
    }

    void clear();
}
