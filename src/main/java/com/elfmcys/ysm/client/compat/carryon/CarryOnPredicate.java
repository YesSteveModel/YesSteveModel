package com.elfmcys.ysm.client.compat.carryon;

import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import static com.elfmcys.ysm.client.compat.carryon.CarryOnInnerCompat.getCarryOnType;

public class CarryOnPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event) {
        Player player = event.getAnimatableEntity().getEntity();
        if (player == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        if (player.getPose() == Pose.SWIMMING) {
            return PlayState.STOP;
        }
        if (player.getPose() == Pose.FALL_FLYING && player.isFallFlying()) {
            return PlayState.STOP;
        }
        CarryOnInnerCompat.Type carryOnType = getCarryOnType(player);
        switch (carryOnType) {
            case ENTITY -> {
                return IAnimationPredicate.playLoopAnimation(event, "carryon:entity");
            }
            case BLOCK -> {
                return IAnimationPredicate.playLoopAnimation(event, "carryon:block");
            }
            case PLAYER -> {
                return IAnimationPredicate.playLoopAnimation(event, "carryon:player");
            }
        }
        return PlayState.STOP;
    }
}
