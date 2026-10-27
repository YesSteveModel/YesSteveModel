package com.elfmcys.ysm.client.animation;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.animation.predicate.PlayerMainPredicate;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiPredicate;

public class AnimationRegister {
    public static final String IDLE = "idle";
    public static final String HOVER = "hover";
    public static final String HOVER_FADEOUT = "hover_fadeout";
    public static final String FOCUS = "focus";
    public static final String EMPTY = "empty";

    private static final float MIN_SPEED = 0.05f;

    public static void registerAnimationState() {
        register("death", LoopType.PLAY_ONCE, Priority.HIGHEST, (player, event) -> player.isDeadOrDying());
        register("riptide", Priority.HIGHEST, (player, event) -> player.isAutoSpinAttack());
        register("sleep", Priority.HIGHEST, (player, event) -> player.getPose() == Pose.SLEEPING);
        register("swim", Priority.HIGHEST, (player, event) -> player.isSwimming());
        register("climb", Priority.HIGHEST,
            (player, event) -> player.getPose() == Pose.SWIMMING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("climbing", Priority.HIGHEST, (player, event) -> player.getPose() == Pose.SWIMMING);

        register("ladder_up", Priority.HIGHEST,
            (player, event) -> player.onClimbable() && getVerticalSpeed(player) > 0);
        register("ladder_stillness", Priority.HIGHEST,
            (player, event) -> player.onClimbable() && getVerticalSpeed(player) == 0);
        register("ladder_down", Priority.HIGHEST,
            (player, event) -> player.onClimbable() && getVerticalSpeed(player) < 0);

        register("fly", Priority.HIGH, (player, event) -> {
            if (event.getAnimatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
                return cap.getStateTracker().isFlying();
            }
            return player.getAbilities().flying;
        });
        register("elytra_fly", Priority.HIGH,
            (player, event) -> player.getPose() == Pose.FALL_FLYING && player.isFallFlying());

        register("swim_stand", Priority.NORMAL, (player, event) -> player.isInWater() && !player.onGround());
        register("attacked", LoopType.PLAY_ONCE, Priority.NORMAL, (player, event) -> player.hurtTime > 0);
        register("jump", Priority.NORMAL, (player, event) -> !player.onGround() && !player.isInWater());
        register("sneak", Priority.NORMAL, (player, event) -> player.onGround() && player.getPose() == Pose.CROUCHING
            && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("sneaking", Priority.NORMAL,
            (player, event) -> player.onGround() && player.getPose() == Pose.CROUCHING);

        register("run", Priority.LOW, (player, event) -> player.onGround() && player.isSprinting());
        register("walk", Priority.LOW, (player, event) -> player.onGround() && event.getLimbSwingAmount() > MIN_SPEED);

        register(IDLE, Priority.LOWEST, (player, event) -> true);
    }

    private static void register(String animationName, LoopType loopType, int priority,
        BiPredicate<Player, AnimationEvent<CustomPlayerEntity>> predicate) {
        PlayerMainPredicate.register(new AnimationState<>(animationName, loopType, priority, predicate));
    }

    private static void register(String animationName, int priority,
        BiPredicate<Player, AnimationEvent<CustomPlayerEntity>> predicate) {
        register(animationName, LoopType.LOOP, priority, predicate);
    }

    private static float getVerticalSpeed(Player player) {
        return 20 * (float)(player.position().y - player.yo);
    }
}
