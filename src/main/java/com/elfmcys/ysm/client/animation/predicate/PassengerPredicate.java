package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionalPassenger;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class PassengerPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        Entity passenger = entity.getFirstPassenger();
        if (passenger == null || !passenger.isAlive()) {
            return PlayState.STOP;
        }

        ConditionalPassenger conditionalPassenger = event.getAnimatableEntity().getConditionManager().getPassenger();
        if (conditionalPassenger != null) {
            String name = conditionalPassenger.doTest(entity);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, LoopType.LOOP);
            }
        }
        return PlayState.STOP;
    }
}
