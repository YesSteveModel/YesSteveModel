package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Entity;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class VehicleMovePredicate implements IAnimationPredicate<CustomVehicleEntity> {
    public static final String[] ANIM_LIST = new String[] {"forward", "idle"};

    @Override
    public PlayState test(AnimationEvent<CustomVehicleEntity> event) {
        Entity entity = event.getAnimatableEntity().getEntity();
        if (entity == null) {
            return PlayState.STOP;
        }
        var motion = entity.getDeltaMovement();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (speed > 0.05) {
            return playAnimation(event, "forward");
        }
        return playAnimation(event, "idle");
    }
}
