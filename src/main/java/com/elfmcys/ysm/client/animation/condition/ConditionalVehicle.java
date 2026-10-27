package com.elfmcys.ysm.client.animation.condition;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITagManager;

public class ConditionalVehicle {
    private static final String EMPTY = "";
    private final ObjectOpenHashSet<ResourceLocation> idTest = new ObjectOpenHashSet<>();
    private final ReferenceArrayList<TagKey<EntityType<?>>> tagTest = new ReferenceArrayList<>();
    private final String idPre;
    private final String tagPre;

    public ConditionalVehicle() {
        this.idPre = "vehicle$";
        this.tagPre = "vehicle#";
    }

    public void addTest(String name) {
        int preSize = this.idPre.length();
        if (name.length() <= preSize) {
            return;
        }
        String substring = name.substring(preSize);
        if (name.startsWith(idPre) && ResourceLocation.isValidResourceLocation(substring)) {
            idTest.add(new ResourceLocation(substring));
        }
        if (name.startsWith(tagPre) && ResourceLocation.isValidResourceLocation(substring)) {
            ITagManager<EntityType<?>> tags = ForgeRegistries.ENTITY_TYPES.tags();
            if (tags == null) {
                return;
            }
            TagKey<EntityType<?>> tagKey = tags.createTagKey(new ResourceLocation(substring));
            tagTest.add(tagKey);
        }
    }

    public String doTest(LivingEntity livingEntity) {
        Entity vehicle = livingEntity.getVehicle();
        if (vehicle == null || !vehicle.isAlive()) {
            return EMPTY;
        }
        String result = doIdTest(vehicle);
        if (result.isEmpty()) {
            return doTagTest(vehicle);
        }
        return result;
    }

    private String doIdTest(Entity vehicle) {
        if (idTest.isEmpty()) {
            return EMPTY;
        }
        ResourceLocation registryName = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        if (registryName == null) {
            return EMPTY;
        }
        if (idTest.contains(registryName)) {
            return idPre + registryName;
        }
        return EMPTY;
    }

    private String doTagTest(Entity vehicle) {
        if (tagTest.isEmpty()) {
            return EMPTY;
        }
        ITagManager<EntityType<?>> tags = ForgeRegistries.ENTITY_TYPES.tags();
        if (tags == null) {
            return EMPTY;
        }
        return tagTest.stream().filter(tag -> vehicle.getType().is(tag)).findFirst()
            .map(itemTagKey -> tagPre + itemTagKey.location()).orElse(EMPTY);
    }
}
