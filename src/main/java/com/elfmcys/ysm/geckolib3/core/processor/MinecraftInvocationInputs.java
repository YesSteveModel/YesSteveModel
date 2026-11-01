package com.elfmcys.ysm.geckolib3.core.processor;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.expression.host.AnimationExecutionState;
import com.elfmcys.ysm.expression.host.ControllerExecutionState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Direct invocation-scoped Minecraft adapter used by the persistent entity runtime. */
final class MinecraftInvocationInputs implements MinecraftRuntimeInputs<Entity> {
    private final AnimatableEntity<?> animatable;

    @Override
    public AnimatableEntity<?> animatable() {
        return animatable;
    }

    @Override
    public net.minecraft.world.entity.Entity entity() {
        return animatable.getEntity();
    }

    @Override
    public float partialTick() {
        return event.getPartialTick();
    }

    @Override
    public float renderTickDelta() {
        return animatable.getStateTracker().getRenderTickDelta();
    }

    private final AnimationEvent<?> event;
    private final RandomSource random;
    @Nullable
    private final ControllerExecutionState controller;
    @Nullable
    private final AnimationExecutionState animation;

    @Override
    public AnimationEvent<?> animationEvent() {
        return event;
    }

    @Override
    public RandomSource randomSource() {
        return random;
    }

    @Override
    public @Nullable ControllerExecutionState controllerState() {
        return controller;
    }

    @Override
    public @Nullable AnimationExecutionState animationState() {
        return animation;
    }

    MinecraftInvocationInputs(AnimatableEntity<?> animatable, AnimationEvent<?> event, RandomSource random,
        SoundInstanceManager sounds, @Nullable ControllerExecutionState controller,
        @Nullable AnimationExecutionState animation) {
        this.animatable = Objects.requireNonNull(animatable, "animatable");
        this.event = Objects.requireNonNull(event, "event");
        this.random = Objects.requireNonNull(random, "random");
        this.controller = controller;
        this.animation = animation;
    }

}
