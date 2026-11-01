package com.elfmcys.ysm.geckolib3.core.controller;

import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.List;
import java.util.function.Consumer;

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

    default Runnable prepareResourceBinding(Object2ReferenceMap<String, List<EventProgram>> eventHandlers,
        java.util.function.Function<String,
            com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData> definitions) {
        return () -> {};
    }

    default void invalidateAnimationSource() {}

    void process(AnimationEvent<T> event, EntityExpressionRuntime.Evaluation evaluation, boolean executeEvents);

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
