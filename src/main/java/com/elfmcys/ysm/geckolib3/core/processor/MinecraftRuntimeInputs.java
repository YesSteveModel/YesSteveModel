package com.elfmcys.ysm.geckolib3.core.processor;

import com.elfmcys.ysm.expression.host.AnimationExecutionState;
import com.elfmcys.ysm.expression.host.ControllerExecutionState;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** Invocation-local Minecraft facts borrowed by built-in component methods. */
public interface MinecraftRuntimeInputs<E extends Entity> extends InvocationInputs<E> {
    AnimatableEntity<?> animatable();

    AnimationEvent<?> animationEvent();

    RandomSource randomSource();

    @Nullable
    ControllerExecutionState controllerState();

    @Nullable
    AnimationExecutionState animationState();
}
