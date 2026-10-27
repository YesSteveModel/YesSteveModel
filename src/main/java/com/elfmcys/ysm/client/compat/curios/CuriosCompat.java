package com.elfmcys.ysm.client.compat.curios;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.LoadingModList;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.List;

import static com.elfmcys.ysm.client.compat.curios.CuriosCompatInner.searchCuriosHandler;

// FIXME: 在异步动画更新时访问有潜在的并发错误，但是 x86 上应该不太会撞上
public class CuriosCompat {
    private static final String MOD_ID = "curios";
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = LoadingModList.get().getModFileById(MOD_ID) != null;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static boolean hasAnyItemEquipped(LivingEntity entity, String slotType, ReferenceOpenHashSet<Item> items) {
        return INSTALLED && CuriosApi.getCuriosInventory(entity).map(handler -> handler)
            .flatMap(inventory -> inventory.getStacksHandler(slotType))
            .map(curiosHandler -> searchCuriosHandler(curiosHandler,
                itemStack -> items.isEmpty() || items.contains(itemStack.getItem())) != null)
            .orElse(false);
    }

    public static boolean hasAnyItemEquippedWithAnyTag(LivingEntity entity, String slotType, List<TagKey<Item>> tags) {
        return INSTALLED && CuriosApi.getCuriosInventory(entity).map(handler -> handler)
            .flatMap(inventory -> inventory.getStacksHandler(slotType))
            .map(curiosHandler -> searchCuriosHandler(curiosHandler, itemStack -> {
                for (var tagKey : tags) {
                    if (itemStack.is(tagKey)) {
                        return true;
                    }
                }
                return false;
            }) != null).orElse(false);
    }

    public static boolean hasAnyItemEquippedWithAllTag(LivingEntity entity, String slotType, List<TagKey<Item>> tags) {
        return INSTALLED && CuriosApi.getCuriosInventory(entity).map(handler -> handler)
            .flatMap(inventory -> inventory.getStacksHandler(slotType))
            .map(curiosHandler -> searchCuriosHandler(curiosHandler, itemStack -> {
                for (var tagKey : tags) {
                    if (!itemStack.is(tagKey)) {
                        return false;
                    }
                }
                return true;
            }) != null).orElse(false);
    }

}
