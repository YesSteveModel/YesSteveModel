package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.EntityTickStates;
import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.ConditionalSwing;
import com.elfmcys.ysm.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playCompatAnimation;

public class SwingPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        // 铁魔法兼容
        PlayState playState = IronsSpellBooksCompat.playAnimation(event, entity);
        if (playState != null) {
            return playState;
        }

        // 拔刀剑兼容，拔刀剑的使用不受 swing 限制
        if (!entity.isSleeping()
            && SlashBladeCompat.isSlashBladeItem(entity.getItemInHand(InteractionHand.MAIN_HAND))) {
            // 起手阻止后续原挥剑动画
            if (event.getCodedController().isAnimFinished()) {
                // 重置动画
                event.getCodedController().indicateReload();
            }
            String animationName = SlashBladeCompat.getAnimationName(event);
            if (StringUtils.isNoneBlank(animationName)) {
                if (event.getAnimatableEntity().getAnimation(animationName) != null) {
                    return playCompatAnimation(event, animationName, LoopType.PLAY_ONCE);
                }
                return PlayState.CONTINUE;
            }
        }

        // 其他情况
        if (entity.swinging && !entity.isSleeping()) {
            if (entity.swingTime == 0
                && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.SWING)) {
                // swing 开始时重置动画
                event.getCodedController().indicateReload();
            }
            ConditionManager conditionManager = event.getAnimatableEntity().getConditionManager();
            ConditionalSwing conditionalSwing = entity.swingingArm == InteractionHand.MAIN_HAND
                ? conditionManager.getSwingMainhand() : conditionManager.getSwingOffhand();
            if (conditionalSwing != null) {
                String name = conditionalSwing.doTest(entity, entity.swingingArm);
                if (StringUtils.isNoneBlank(name)) {
                    return playCompatAnimation(event, name, LoopType.PLAY_ONCE);
                }
            }
            String defaultSwing = (entity.swingingArm == InteractionHand.MAIN_HAND) ? "swing_hand" : "swing_offhand";
            return playCompatAnimation(event, defaultSwing, LoopType.PLAY_ONCE);
        }
        return PlayState.CONTINUE;
    }
}
