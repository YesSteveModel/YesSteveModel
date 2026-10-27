package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;
import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class CapPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatableEntity();
        if (animatable instanceof IPreviewEntity guiEntity) {
            if (guiEntity.getPreviewInfo().hasPreview()) {
                return playLoopAnimation(event, guiEntity.getPreviewInfo().getPreview());
            }
            return PlayState.STOP;
        }

        if (animatable.isPlayingExtraAnimation()) {
            if (animatable.shouldResetExtraAnimation()) {
                animatable.clearExtraAnimationDirty();
                event.getCodedController().indicateReload();
            }
            return playAnimation(event, animatable.getExtraAnimationName());
        }
        return PlayState.STOP;
    }
}
