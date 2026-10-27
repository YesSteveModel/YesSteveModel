package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.accessor.IArrowExtraInfo;
import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.projectile.Projectile;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class ProjectileMainPredicate implements IAnimationPredicate<CustomProjectileEntity> {
    public static final String[] ANIM_LIST = new String[] {"water", "ground", "fly", "fire"};

    @Override
    public PlayState test(AnimationEvent<CustomProjectileEntity> event) {
        Projectile projectile = event.getAnimatableEntity().getEntity();
        if (projectile == null) {
            return PlayState.STOP;
        }
        if (projectile.isInWater()) {
            return playAnimation(event, "water");
        }
        if (projectile.isOnFire()) {
            return playAnimation(event, "fire");
        }
        if (projectile instanceof IArrowExtraInfo info && info.isInGround()) {
            return playAnimation(event, "ground");
        } else {
            return playAnimation(event, "air");
        }
    }
}
