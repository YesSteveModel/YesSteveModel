package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.util.EquipmentUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class ArmorPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    private final EquipmentSlot slot;

    public ArmorPredicate(EquipmentSlot slot) {
        this.slot = slot;
    }

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        ItemStack itemBySlot = EquipmentUtil.getEquippedItem(entity, slot);
        if (itemBySlot.isEmpty()) {
            return PlayState.STOP;
        }

        ConditionArmor conditionArmor = event.getAnimatableEntity().getConditionManager().getArmor();
        if (conditionArmor != null) {
            String name = conditionArmor.doTest(entity, slot);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, LoopType.LOOP);
            }
        }

        String defaultName = slot.getName() + ":default";
        if (event.getAnimatableEntity().getAnimation(defaultName) != null) {
            return playAnimation(event, defaultName, LoopType.LOOP);
        }
        return PlayState.STOP;
    }
}
