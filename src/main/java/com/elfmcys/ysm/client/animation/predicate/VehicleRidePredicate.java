package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Entity;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class VehicleRidePredicate implements IAnimationPredicate<CustomVehicleEntity> {
    public static final String[] ANIM_LIST = new String[] {"has_ride", "not_ride"};

    @Override
    public PlayState test(AnimationEvent<CustomVehicleEntity> event) {
        Entity entity = event.getAnimatableEntity().getEntity();
        if (entity == null) {
            return PlayState.STOP;
        }
        if (!entity.getPassengers().isEmpty()) {
            return playAnimation(event, "has_ride");
        }
        return playAnimation(event, "not_ride");
    }
}
