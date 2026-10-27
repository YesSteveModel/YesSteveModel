package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

public class TACZCompat {
    private static final String MOD_ID = "tacz";
    private static boolean INSTALLED = false;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
        if (INSTALLED) {
            TacCompatInner.registerEvent();
        }
    }

    public static void renderOffsetHand(ItemStack offhandItem, GeoRenderData data, LivingEntity livingEntity,
        PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (isInstalled() && TacCompatInner.isGun(offhandItem)) {
            poseStack.pushPose();
            TacCompatInner.renderOffhandGun(offhandItem, data, livingEntity, poseStack, buffer, packedLight);
            poseStack.popPose();
        }
    }

    @Nullable
    public static PlayState playGunMainAnimation(LivingEntity livingEntity,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event, String animationName, LoopType loopType) {
        if (isInstalled() && TacCompatInner.isGun(livingEntity.getMainHandItem())) {
            return TacCompatInner.playGunMainAnimation(event, animationName, loopType);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunHoldAnimation(ItemStack mainHandItem,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            return TacCompatInner.playGunHoldAnimation(event, mainHandItem);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunOnceAnimation(ItemStack mainHandItem,
        AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            return TacCompatInner.playGunOnceAnimation(event, mainHandItem);
        }
        return null;
    }

    public static void openFlashShellRender(LivingEntity livingEntity, ItemStack mainHandItem) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.openFlashShellRender(livingEntity);
        }
    }

    public static void stopFlashShellRender(ItemStack mainHandItem) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.stopFlashShellRender();
        }
    }

    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        if (isInstalled()) {
            return TacCompatInner.getGunId(stack);
        }
        return null;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static boolean isMainhandHoldingGun(LivingEntity entity) {
        return INSTALLED && TacCompatInner.isMainhandHoldingGun(entity);
    }

    /**
     * Whether one borrowed entity holds a TACZ gun in its main hand, or {@code false} when this entity is not a living
     * entity or the adapter is unavailable.
     *
     * <p>
     * This is the capability-facing form: it owns the living-entity narrowing so a caller never has to reach the
     * optional adapter itself.
     */
    public static boolean mainhandHoldingGun(net.minecraft.world.entity.Entity entity) {
        return entity instanceof LivingEntity living && isMainhandHoldingGun(living);
    }
}
