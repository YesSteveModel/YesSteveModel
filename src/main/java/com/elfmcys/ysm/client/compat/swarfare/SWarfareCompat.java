package com.elfmcys.ysm.client.compat.swarfare;

import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;
import java.util.function.Supplier;

public class SWarfareCompat {
    private static final String MOD_ID = "superbwarfare";
    private static boolean INSTALLED = false;

    public static void init() {
        ModFileInfo modFileById = LoadingModList.get().getModFileById(MOD_ID);
        if (modFileById != null) {
            DefaultArtifactVersion modVersion = new DefaultArtifactVersion(modFileById.versionString());
            INSTALLED = modVersion.compareTo(new DefaultArtifactVersion("0.8.7.1")) >= 0;
        }
        String handVersion =
            modFileById == null ? null : modFileById.getMods().stream().filter(mod -> MOD_ID.equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString()).findFirst().orElse(null);
        registerHandAdapter(handVersion, MinecraftForge.EVENT_BUS, () -> new ReplacePlayerArmRender());
    }

    static boolean registerHandAdapter(@Nullable String version, IEventBus bus, Supplier<?> factory) {
        if (!"0.8.7.1".equals(version))
            return false;
        Object handler = null;
        try {
            handler = factory.get();
            bus.register(handler);
            return true;
        } catch (LinkageError | RuntimeException registrationFailure) {
            if (handler != null) {
                try {
                    bus.unregister(handler);
                } catch (LinkageError | RuntimeException cleanupFailure) {
                    registrationFailure.addSuppressed(cleanupFailure);
                }
            }
            YesSteveModel.LOGGER.warn("Cannot register Superb Warfare hand adapter", registrationFailure);
            return false;
        }
    }

    public static boolean isGun(ItemStack stack) {
        if (INSTALLED) {
            return SWarfareCompatInner.isGun(stack);
        }
        return false;
    }

    public static boolean shouldHidePlayerRender(Player player) {
        if (INSTALLED) {
            return SWarfareCompatInner.shouldHidePlayerRender(player);
        }
        return false;
    }

    public static void renderOffsetHand(ItemStack offhandItem, GeoRenderData data, LivingEntity livingEntity,
        PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (INSTALLED && SWarfareCompatInner.isGun(offhandItem)) {
            poseStack.pushPose();
            SWarfareCompatInner.renderOffhandGun(offhandItem, data, livingEntity, poseStack, buffer, packedLight);
            poseStack.popPose();
        }
    }

    @Nullable
    public static PlayState playGunMainAnimation(LivingEntity livingEntity,
        AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, String animationName,
        LoopType loopType) {
        if (INSTALLED && SWarfareCompatInner.isGun(livingEntity.getMainHandItem())) {
            return SWarfareCompatInner.playGunMainAnimation(event, animationName, loopType);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunHoldAnimation(ItemStack mainHandItem,
        AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        if (INSTALLED) {
            return SWarfareCompatInner.playGunHoldAnimation(event, mainHandItem);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunOnceAnimation(ItemStack mainHandItem,
        AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        if (INSTALLED && SWarfareCompatInner.isGun(mainHandItem)) {
            return SWarfareCompatInner.playGunOnceAnimation(event, mainHandItem);
        }
        return null;
    }

    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        if (INSTALLED) {
            return ForgeRegistries.ITEMS.getKey(stack.getItem());
        }
        return null;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
}
