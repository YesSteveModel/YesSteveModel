package com.elfmcys.ysm.expression.host.builtin;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.expression.annotation.BuiltinComponent;
import com.elfmcys.ysm.expression.annotation.BuiltinFunction;
import com.elfmcys.ysm.expression.host.EntityComponent;
import net.minecraft.world.entity.Entity;

@BuiltinComponent("query")
public final class AnimationBuiltins implements EntityComponent<Entity> {
    private final AnimatableEntity<?> owner;

    public AnimationBuiltins(AnimatableEntity<Entity> owner) {
        this.owner = owner;
    }

    @BuiltinFunction(value = "all_animations_finished", alwaysOrigin = true)
    public static boolean allAnimationsFinished(MolangContextView<Entity> context) {
        var input = context.inputs().controller();
        return input != null && input.allAnimationsFinished();
    }

    @BuiltinFunction(value = "anim_time", alwaysOrigin = true)
    public static float animTime(MolangContextView<Entity> context) {
        var input = context.inputs().animation();
        return input == null ? 0 : input.time();
    }

    @BuiltinFunction(value = "any_animation_finished", alwaysOrigin = true)
    public static boolean anyAnimationFinished(MolangContextView<Entity> context) {
        var input = context.inputs().controller();
        return input != null && input.anyAnimationFinished();
    }

    @BuiltinFunction(value = "life_time", alwaysOrigin = true)
    public float lifeTime(MolangContextView<Entity> context) {

        return owner.getSeekTime() / 20.0f;
    }
}
