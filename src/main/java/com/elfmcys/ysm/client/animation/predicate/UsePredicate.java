package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.EntityTickStates;
import com.elfmcys.ysm.client.animation.condition.ConditionalUse;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playCompatAnimation;

public class UsePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        if (entity.isUsingItem() && !entity.isSleeping()) {
            if (entity.getTicksUsingItem() == 1
                && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.USING_ITEM)) {
                event.getCodedController().indicateReload();
            }
            var conditionManager = event.getAnimatableEntity().getConditionManager();
            if (entity.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                ConditionalUse conditionalUse = conditionManager.getUseMainhand();
                if (conditionalUse != null) {
                    String name = conditionalUse.doTest(entity, InteractionHand.MAIN_HAND);
                    if (StringUtils.isNoneBlank(name)) {
                        return playCompatAnimation(event, name, LoopType.LOOP);
                    }
                }
                return playCompatAnimation(event, "use_mainhand", LoopType.LOOP);
            } else {
                ConditionalUse conditionalUse = conditionManager.getUseOffhand();
                if (conditionalUse != null) {
                    String name = conditionalUse.doTest(entity, InteractionHand.OFF_HAND);
                    if (StringUtils.isNoneBlank(name)) {
                        return playCompatAnimation(event, name, LoopType.LOOP);
                    }
                }
                return playCompatAnimation(event, "use_offhand", LoopType.LOOP);
            }
        }
        return PlayState.STOP;
    }
}
