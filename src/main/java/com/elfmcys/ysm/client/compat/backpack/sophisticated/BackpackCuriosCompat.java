package com.elfmcys.ysm.client.compat.backpack.sophisticated;

import com.elfmcys.ysm.client.compat.curios.CuriosCompatInner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;

public class BackpackCuriosCompat {
    private static final String SLOT_TYPE = "back";

    @Nullable
    public static ItemStack getCuriosBackpack(LivingEntity livingEntity) {
        return CuriosApi.getCuriosInventory(livingEntity).map(handler -> handler)
            .flatMap(handler -> handler.getStacksHandler(SLOT_TYPE)).map(curiosHandler -> CuriosCompatInner
                .searchCuriosHandler(curiosHandler, itemStack -> itemStack.getItem() instanceof BackpackItem))
            .orElse(null);
    }
}
