package com.elfmcys.ysm.client.compat.simplehat;

import com.elfmcys.ysm.client.compat.curios.CuriosCompatInner;
import fonnymunkey.simplehats.common.item.HatItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;

public class HatCuriosCompat {
    private static final String SLOT_TYPE = "head";

    @Nullable
    public static ItemStack getCuriosHead(LivingEntity livingEntity) {
        return CuriosApi.getCuriosInventory(livingEntity).map(handler -> handler)
            .flatMap(handler -> handler.getStacksHandler(SLOT_TYPE)).map(curiosHandler -> CuriosCompatInner
                .searchCuriosHandler(curiosHandler, itemStack -> itemStack.getItem() instanceof HatItem))
            .orElse(null);
    }
}
