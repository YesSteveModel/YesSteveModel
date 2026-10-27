package com.elfmcys.ysm.client.animation.condition;

import com.elfmcys.ysm.util.EnumUtil;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITagManager;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public class ConditionalSwing {
    private static final String EMPTY = "";
    private final int preSize;
    private final String idPre;
    private final String tagPre;
    private final String extraPre;
    private final ObjectOpenHashSet<ResourceLocation> idTest = new ObjectOpenHashSet<>();
    private final ReferenceArrayList<TagKey<Item>> tagTest = new ReferenceArrayList<>();
    private final ObjectOpenHashSet<UseAnim> extraTest = new ObjectOpenHashSet<>();
    private final ObjectOpenHashSet<String> innerTest = new ObjectOpenHashSet<>();

    public ConditionalSwing(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            idPre = "swing$";
            tagPre = "swing#";
            extraPre = "swing:";
            preSize = 6;
        } else {
            idPre = "swing_offhand$";
            tagPre = "swing_offhand#";
            extraPre = "swing_offhand:";
            preSize = 14;
        }
    }

    public void addTest(String name) {
        if (name.length() <= preSize) {
            return;
        }
        String substring = name.substring(preSize);
        if (name.startsWith(idPre) && ResourceLocation.isValidResourceLocation(substring)) {
            idTest.add(new ResourceLocation(substring));
        }
        if (name.startsWith(tagPre) && ResourceLocation.isValidResourceLocation(substring)) {
            ITagManager<Item> tags = ForgeRegistries.ITEMS.tags();
            if (tags == null) {
                return;
            }
            TagKey<Item> tagKey = tags.createTagKey(new ResourceLocation(substring));
            tagTest.add(tagKey);
        }
        if (name.startsWith(extraPre)) {
            if (substring.equals(UseAnim.NONE.name().toLowerCase(Locale.US))) {
                return;
            }
            EnumUtil.getUseAnim(substring).ifPresent(extraTest::add);
            innerTest.add(name);
        }
    }

    public String doTest(LivingEntity livingEntity, InteractionHand hand) {
        if (livingEntity.getItemInHand(hand).isEmpty()) {
            return EMPTY;
        }
        String result = doIdTest(livingEntity, hand);
        if (result.isEmpty()) {
            result = doTagTest(livingEntity, hand);
            if (result.isEmpty()) {
                return doExtraTest(livingEntity, hand);
            }
            return result;
        }
        return result;
    }

    private String doIdTest(LivingEntity livingEntity, InteractionHand hand) {
        if (idTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = livingEntity.getItemInHand(hand);
        ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(itemInHand.getItem());
        if (registryName == null) {
            return EMPTY;
        }
        if (idTest.contains(registryName)) {
            return idPre + registryName;
        }
        return EMPTY;
    }

    private String doTagTest(LivingEntity livingEntity, InteractionHand hand) {
        if (tagTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = livingEntity.getItemInHand(hand);
        ITagManager<Item> tags = ForgeRegistries.ITEMS.tags();
        if (tags == null) {
            return EMPTY;
        }
        return tagTest.stream().filter(itemInHand::is).findFirst().map(itemTagKey -> tagPre + itemTagKey.location())
            .orElse(EMPTY);
    }

    private String doExtraTest(LivingEntity livingEntity, InteractionHand hand) {
        if (extraTest.isEmpty() && innerTest.isEmpty()) {
            return EMPTY;
        }
        String innerName = InnerClassify.doClassifyTest(extraPre, livingEntity, hand);
        if (StringUtils.isNotBlank(innerName) && this.innerTest.contains(innerName)) {
            return innerName;
        }
        UseAnim anim = livingEntity.getItemInHand(hand).getUseAnimation();
        if (this.extraTest.contains(anim)) {
            return extraPre + anim.name().toLowerCase(Locale.US);
        }
        return EMPTY;
    }
}
