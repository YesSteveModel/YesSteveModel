package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionalHold;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.HumanoidStateTracker;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playCompatAnimation;

public class OffhandPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        if (!checkSwingAndUse(entity, InteractionHand.OFF_HAND)) {
            return PlayState.PAUSE;
        }

        ItemStack offhandItem = entity.getItemInHand(InteractionHand.OFF_HAND);
        if (offhandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(offhandItem)) {
            return playCompatAnimation(event, "hold_offhand:charged_crossbow", LoopType.LOOP);
        }

        var tracker = event.getAnimatableEntity().getStateTracker();
        if (!isSameItem(offhandItem, tracker, InteractionHand.OFF_HAND)) {
            tracker.setHandItem(offhandItem, InteractionHand.OFF_HAND);
            event.getCodedController().indicateReload();
        }

        ConditionalHold conditionalHold = event.getAnimatableEntity().getConditionManager().getHoldOffhand();
        if (conditionalHold != null) {
            String name = conditionalHold.doTest(entity, InteractionHand.OFF_HAND);
            if (StringUtils.isNoneBlank(name)) {
                return playCompatAnimation(event, name, LoopType.LOOP);
            }
        }

        return PlayState.STOP;
    }

    private boolean isSameItem(ItemStack playerItem, HumanoidStateTracker<?> tracker, InteractionHand hand) {
        ItemStack preItem = tracker.getHandItem(hand);
        if (preItem.isDamaged()) {
            return ItemStack.isSameItem(playerItem, preItem);
        }
        return ItemStack.matches(playerItem, preItem);
    }

    private boolean checkSwingAndUse(LivingEntity entity, InteractionHand hand) {
        if (entity.swinging && entity.swingingArm == hand) {
            return false;
        }
        return !entity.isUsingItem() || entity.getUsedItemHand() != hand;
    }
}
