package com.elfmcys.ysm.client.compat.curios;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public class MolangCache {
    public static final ThreadLocal<ReferenceOpenHashSet<Item>> ITEM_SET =
        ThreadLocal.withInitial(() -> new ReferenceOpenHashSet<>(16));
    public static final ThreadLocal<ReferenceArrayList<TagKey<Item>>> TAG_LIST =
        ThreadLocal.withInitial(() -> new ReferenceArrayList<>(16));
}
