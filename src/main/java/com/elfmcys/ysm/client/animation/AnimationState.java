package com.elfmcys.ysm.client.animation;

import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.BiPredicate;

public class AnimationState<TE extends LivingEntity, AE extends AnimatableEntity<TE>> {
    private final String animationName;
    private final LoopType loopType;
    private final int priority;
    private final BiPredicate<TE, AnimationEvent<AE>> predicate;

    public AnimationState(String animationName, LoopType loopType, int priority,
        BiPredicate<TE, AnimationEvent<AE>> predicate) {
        this.animationName = animationName;
        this.loopType = loopType;
        this.priority = Mth.clamp(priority, Priority.HIGHEST, Priority.LOWEST);
        this.predicate = predicate;
    }

    public BiPredicate<TE, AnimationEvent<AE>> getPredicate() {
        return predicate;
    }

    public String getAnimationName() {
        return animationName;
    }

    public LoopType getLoopType() {
        return loopType;
    }

    public int getPriority() {
        return priority;
    }
}
