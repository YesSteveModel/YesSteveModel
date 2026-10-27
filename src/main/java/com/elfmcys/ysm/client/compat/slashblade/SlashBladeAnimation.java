package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.init.ModItemTags;
import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.SlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

public class SlashBladeAnimation {
    static boolean isSlashBlade(ItemStack stack) {
        return stack.getItem() instanceof ItemSlashBlade || stack.is(ModItemTags.SLASH_BLADE);
    }

    static String getAnimationName(AnimationEvent<? extends AnimatableEntity<? extends LivingEntity>> event) {
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        return getCombName(livingEntity.getMainHandItem(), livingEntity);
    }

    static String getAnimationName(LivingEntity livingEntity) {
        return getCombName(livingEntity.getMainHandItem(), livingEntity);
    }

    /**
     * slashblade:idle slashblade:run slashblade:walk
     */
    static PlayState playMainAnimation(AnimationEvent<? extends AnimatableEntity<? extends LivingEntity>> event,
        String animationName, LoopType loopType) {
        String name = "slashblade:" + animationName;
        if (event.getAnimatableEntity().getAnimation(name) != null) {
            return playAnimation(event, name, loopType);
        }
        return playAnimation(event, animationName, loopType);
    }

    @NotNull
    private static String getCombName(ItemStack mainHandItem, LivingEntity entity) {
        if (!SlashBladeCompat.isSlashBladeItem(mainHandItem)) {
            return StringUtils.EMPTY;
        }
        return mainHandItem.getCapability(CapabilitySlashBlade.BLADESTATE).map(bladeState -> {
            long time = (entity.level().getGameTime() - bladeState.getLastActionTime()) * 50;
            if (SlashBladeCompat.isResharped()) {
                // 重锋兼容
                return SlashBladeResharped.getResharpedComboStateName(bladeState, time, entity);
            } else if (bladeState instanceof SlashBladeState slashBladeState) {
                // 旧版拔刀兼容
                return SlashBladeUnsafe.getOldComboStateName(slashBladeState, time);
            }
            return StringUtils.EMPTY;
        }).orElse(StringUtils.EMPTY);
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }
}
