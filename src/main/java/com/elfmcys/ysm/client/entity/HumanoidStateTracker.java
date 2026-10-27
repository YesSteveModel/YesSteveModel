package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.geckolib3.model.EntityStateTracker;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public class HumanoidStateTracker<T extends LivingEntity> extends EntityStateTracker<T> {
    private ItemStack mainhandItemStack = ItemStack.EMPTY;
    private ItemStack offhandItemStack = ItemStack.EMPTY;

    public HumanoidStateTracker(T entity) {
        super(entity);
    }

    @Override
    public void reset() {
        mainhandItemStack = ItemStack.EMPTY;
        offhandItemStack = ItemStack.EMPTY;
        super.reset();
    }

    public ItemStack getHandItem(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return mainhandItemStack;
        } else {
            return offhandItemStack;
        }
    }

    public void setHandItem(ItemStack stack, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            this.mainhandItemStack = stack;
        } else {
            this.offhandItemStack = stack;
        }
    }

}
