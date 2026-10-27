package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionArmor;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.util.EquipmentUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class FPArmArmorPredicate implements IAnimationPredicate<CustomFirstPersonArmEntity> {
    private final EquipmentSlot slot;

    public FPArmArmorPredicate(EquipmentSlot slot) {
        this.slot = slot;
    }

    @Override
    public PlayState test(AnimationEvent<CustomFirstPersonArmEntity> event) {
        LocalPlayer localPlayer = event.getAnimatableEntity().getEntity();
        if (localPlayer == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        ItemStack itemBySlot = EquipmentUtil.getEquippedItem(localPlayer, slot);
        if (itemBySlot.isEmpty()) {
            return PlayState.STOP;
        }

        ConditionArmor conditionArmor = event.getAnimatableEntity().getFPArmConditionManager().getArmor();
        if (conditionArmor != null) {
            String name = conditionArmor.doTest(localPlayer, slot);
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
