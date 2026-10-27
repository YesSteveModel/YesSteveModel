package com.elfmcys.ysm.client.compat.curios;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.function.Predicate;

public class CuriosCompatInner {
    /**
     * 搜索整个饰品栏，性能很差。
     */
    @Nullable
    public static ItemStack searchCuriosInventory(ICuriosItemHandler curiosInventory,
        Predicate<ItemStack> itemPredicate) {
        for (var curiosHandler : curiosInventory.getCurios().values()) {
            var itemStack = searchCuriosHandler(curiosHandler, itemPredicate);
            if (itemStack != null) {
                return itemStack;
            }
        }
        return null;
    }

    /**
     * 仅搜索一类饰品槽位
     */
    @Nullable
    public static ItemStack searchCuriosHandler(ICurioStacksHandler curiosHandler, Predicate<ItemStack> itemPredicate) {
        var rendersToggle = curiosHandler.getRenders();
        var cosmeticItemHandler = curiosHandler.hasCosmetic() ? curiosHandler.getCosmeticStacks() : null;
        var itemHandler = curiosHandler.getStacks();
        for (int i = 0; i < itemHandler.getSlots() && i < rendersToggle.size(); i++) {
            if (rendersToggle.get(i)) {
                if (cosmeticItemHandler != null) {
                    var itemStack = cosmeticItemHandler.getStackInSlot(i);
                    if (itemStack != null && !itemStack.isEmpty()) {
                        if (itemPredicate.test(itemStack)) {
                            return itemStack;
                        }
                        continue;
                    }
                }

                var itemStack = itemHandler.getStackInSlot(i);
                if (!itemStack.isEmpty() && itemPredicate.test(itemStack)) {
                    return itemStack;
                }
            }
        }
        return null;
    }

}
