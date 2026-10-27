package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class FocusPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event) {
        String focusAnimation = ((IPreviewEntity)event.getAnimatableEntity()).getPreviewInfo().getFocus();
        if (StringUtils.isNoneBlank(focusAnimation)) {
            return playLoopAnimation(event, focusAnimation);
        }
        return PlayState.STOP;
    }
}
