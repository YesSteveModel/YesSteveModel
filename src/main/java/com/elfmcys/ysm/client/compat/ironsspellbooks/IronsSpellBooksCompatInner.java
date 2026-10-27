package com.elfmcys.ysm.client.compat.ironsspellbooks;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import io.redspace.ironsspellbooks.api.spells.SpellAnimations;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

public class IronsSpellBooksCompatInner {
    private static final String PREFIX = "iss:";

    static String getAnimation(LivingEntity entity, @Nullable AnimationEvent<CustomHumanoidEntity<?>> event) {
        if (entity instanceof AbstractClientPlayer player) {
            var layer = (ModifierLayer<IAnimation>)PlayerAnimationAccess.getPlayerAssociatedData(player)
                .get(SpellAnimations.ANIMATION_RESOURCE);
            if (layer != null && layer.isActive() && layer.getAnimation() instanceof KeyframeAnimationPlayer keyframe) {
                // 每次动画开始时重置动画状态
                if (event != null && keyframe.getTick() == 0) {
                    event.getCodedController().indicateReload();
                }
                var result = keyframe.getData().extraData.getOrDefault("name", StringUtils.EMPTY);
                if (result instanceof String str) {
                    return str;
                }
                return StringUtils.EMPTY;
            }
        }
        return StringUtils.EMPTY;
    }

    @Nullable
    static PlayState playAnimation(AnimationEvent<CustomHumanoidEntity<?>> event, LivingEntity entity) {
        String animation = getAnimation(entity, event);
        if (StringUtils.isBlank(animation)) {
            return null;
        }
        String animationName = PREFIX + animation;
        if (event.getAnimatableEntity().getAnimation(animationName) != null) {
            return IAnimationPredicate.playAnimation(event, animationName);
        }
        YesSteveModel.LOGGER.error(animationName);
        return null;
    }
}
