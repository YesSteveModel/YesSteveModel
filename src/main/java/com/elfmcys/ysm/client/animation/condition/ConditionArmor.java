package com.elfmcys.ysm.client.animation.condition;

import com.elfmcys.ysm.util.EnumUtil;
import com.elfmcys.ysm.util.EquipmentUtil;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITagManager;

import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("removal")
public class ConditionArmor {
    private static final Pattern ID_PRE_REG = Pattern.compile("^(.+?)\\$(.*?)$");
    private static final Pattern TAG_PRE_REG = Pattern.compile("^(.+?)#(.*?)$");
    private static final String EMPTY = "";

    private final Reference2ReferenceOpenHashMap<EquipmentSlot, ObjectOpenHashSet<ResourceLocation>> idTest =
        new Reference2ReferenceOpenHashMap<>();
    private final Reference2ReferenceOpenHashMap<EquipmentSlot, ReferenceArrayList<TagKey<Item>>> tagTest =
        new Reference2ReferenceOpenHashMap<>();

    public void addTest(String name) {
        Matcher matcherId = ID_PRE_REG.matcher(name);
        if (matcherId.find()) {
            EquipmentSlot type = getType(matcherId.group(1));
            if (type == null) {
                return;
            }
            String id = matcherId.group(2);
            if (!ResourceLocation.isValidResourceLocation(id)) {
                return;
            }
            idTest.computeIfAbsent(type, k -> new ObjectOpenHashSet<>()).add(new ResourceLocation(id));
        }

        Matcher matcherTag = TAG_PRE_REG.matcher(name);
        if (matcherTag.find()) {
            EquipmentSlot type = getType(matcherTag.group(1));
            if (type == null) {
                return;
            }
            String id = matcherTag.group(2);
            if (!ResourceLocation.isValidResourceLocation(id)) {
                return;
            }
            ITagManager<Item> tags = ForgeRegistries.ITEMS.tags();
            if (tags == null) {
                return;
            }
            TagKey<Item> tagKey = tags.createTagKey(new ResourceLocation(id));
            tagTest.computeIfAbsent(type, t -> new ReferenceArrayList<>()).add(tagKey);
        }
    }

    public String doTest(LivingEntity livingEntity, EquipmentSlot slot) {
        ItemStack item = EquipmentUtil.getEquippedItem(livingEntity, slot);
        if (item.isEmpty()) {
            return EMPTY;
        }
        String result = doIdTest(livingEntity, slot);
        if (result.isEmpty()) {
            return doTagTest(livingEntity, slot);
        }
        return result;
    }

    private String doIdTest(LivingEntity livingEntity, EquipmentSlot slot) {
        if (idTest.isEmpty()) {
            return EMPTY;
        }
        if (!idTest.containsKey(slot) || idTest.get(slot).isEmpty()) {
            return EMPTY;
        }
        Set<ResourceLocation> idListTest = idTest.get(slot);
        ItemStack item = EquipmentUtil.getEquippedItem(livingEntity, slot);
        ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(item.getItem());
        if (registryName == null) {
            return EMPTY;
        }
        if (idListTest.contains(registryName)) {
            return slot.getName() + "$" + registryName;
        }
        return EMPTY;
    }

    private String doTagTest(LivingEntity livingEntity, EquipmentSlot slot) {
        if (tagTest.isEmpty()) {
            return EMPTY;
        }
        if (!tagTest.containsKey(slot) || tagTest.get(slot).isEmpty()) {
            return EMPTY;
        }
        List<TagKey<Item>> tagListTest = tagTest.get(slot);
        ItemStack item = EquipmentUtil.getEquippedItem(livingEntity, slot);
        ITagManager<Item> tags = ForgeRegistries.ITEMS.tags();
        if (tags == null) {
            return EMPTY;
        }
        return tagListTest.stream().filter(item::is).findFirst()
            .map(itemTagKey -> slot.getName() + "#" + itemTagKey.location()).orElse(EMPTY);
    }

    public boolean hasTest(EquipmentSlot slot) {
        return tagTest.containsKey(slot) || idTest.containsKey(slot);
    }

    @Nullable
    public static EquipmentSlot getType(String type) {
        return EnumUtil.getEquipmentSlot(type).orElse(null);
    }
}
