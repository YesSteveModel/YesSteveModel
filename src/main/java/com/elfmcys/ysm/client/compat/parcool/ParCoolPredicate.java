package com.elfmcys.ysm.client.compat.parcool;

import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.player.Player;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class ParCoolPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event) {
        Player player = event.getAnimatableEntity().getEntity();
        if (player == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return null;
        }
        String parCoolAnimation = ParCoolCompat.getAnimation(player);
        if (parCoolAnimation != null) {
            if (event.getAnimatableEntity().getAnimation(parCoolAnimation) != null) {
                return playAnimation(event, parCoolAnimation);
            }
        }
        return PlayState.STOP;
    }
}
