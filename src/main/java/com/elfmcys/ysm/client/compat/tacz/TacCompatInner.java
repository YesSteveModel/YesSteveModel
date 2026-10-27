package com.elfmcys.ysm.client.compat.tacz;

import com.elfmcys.ysm.client.animation.condition.ConditionTAC;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.functional.MuzzleFlashRender;
import com.tacz.guns.client.model.functional.ShellRender;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

class TacCompatInner {
    static boolean isMainhandHoldingGun(LivingEntity entity) {
        return IGun.mainhandHoldGun(entity);
    }

    static boolean isGun(ItemStack itemStack) {
        return itemStack.getItem() instanceof IGun;
    }

    static void registerEvent() {
        MinecraftForge.EVENT_BUS.register(new TacEvent());
    }

    static boolean isGrenade(ItemStack itemStack) {
        // TODO 手雷还没有
        return false;
    }

    static void renderOffhandGun(ItemStack heldItem, GeoRenderData data, LivingEntity player, PoseStack poseStack,
        MultiBufferSource buffer, int packedLight) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return;
        }

        TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem)).ifPresent(index -> {
            String weaponType = index.getType();
            ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
            if (isType(weaponType, GunTabType.PISTOL)) {
                data.modelState.visitLocatorGroup(PlayerLocator.get().pistol, poseStack, locatorPose -> {
                    locatorPose.translate(0, -0.125, 0);
                    locatorPose.scale(0.65f, 0.65f, 0.65f);
                    locatorPose.mulPose(Axis.YP.rotationDegrees(-90.0F));
                    locatorPose.mulPose(Axis.ZP.rotationDegrees(90.0F));
                    renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY,
                        locatorPose, buffer, player.level(), player.getId());
                });
            }
            if (!isType(weaponType, GunTabType.PISTOL)) {
                data.modelState.visitLocatorGroup(PlayerLocator.get().rifle, poseStack, locatorPose -> {
                    locatorPose.scale(0.65f, 0.65f, 0.65f);
                    locatorPose.mulPose(Axis.YP.rotationDegrees(-180.0F));
                    renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY,
                        locatorPose, buffer, player.level(), player.getId());
                });
            }
        });
    }

    static PlayState playGrenadeAnimation(AnimationEvent<? extends AnimatableEntity<? extends LivingEntity>> event,
        InteractionHand hand) {
        // TODO 手雷还没有
        if (hand == InteractionHand.MAIN_HAND) {
            return playLoopAnimation(event, "tac:mainhand:grenade");
        }
        return playLoopAnimation(event, "tac:offhand:grenade");
    }

    /**
     * tac:idle tac:run tac:walk
     */
    static PlayState playGunMainAnimation(AnimationEvent<? extends AnimatableEntity<? extends LivingEntity>> event,
        String animationName, LoopType loopType) {
        String tacName = "tac:" + animationName;
        if (event.getAnimatableEntity().getAnimation(tacName) != null) {
            return playAnimation(event, tacName, loopType);
        }
        return playAnimation(event, animationName, loopType);
    }

    /**
     * tac:hold:pistol tac:aim:pistol tac:reload:pistol tac:aim_shoot:pistol tac:hold_shoot:pistol tac:run:pistol
     */
    static PlayState playGunHoldAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, ItemStack heldItem) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return PlayState.STOP;
        }
        Optional<CommonGunIndex> indexOptional = TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem));
        if (indexOptional.isEmpty()) {
            return PlayState.STOP;
        }
        CommonGunIndex gunIndex = indexOptional.get();
        String weaponType = gunIndex.getType();
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        IGunOperator operator = IGunOperator.fromLivingEntity(livingEntity);

        if (!livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING) {
            if (Math.abs(event.getLimbSwingAmount()) > 0.05) {
                return getGunTypeAnimation(event, weaponType, "tac:climb:");
            } else {
                return getGunTypeAnimation(event, weaponType, "tac:climbing:");
            }
        }

        float aimProgress = operator.getSynAimingProgress();
        if (aimProgress > 0) {
            return getGunTypeAnimation(event, weaponType, "tac:aim:");
        } else {
            if (livingEntity.onGround() && livingEntity.isSprinting()) {
                return getGunTypeAnimation(event, weaponType, "tac:run:");
            }
            return getGunTypeAnimation(event, weaponType, "tac:hold:");
        }
    }

    /**
     * 这些动画可能是带有后摇的动画，故需要单独分一个频道来播放，从而才能超过时长进行播放
     */
    static PlayState playGunOnceAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, ItemStack heldItem) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return PlayState.STOP;
        }
        Optional<CommonGunIndex> indexOptional = TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem));
        if (indexOptional.isEmpty()) {
            return PlayState.STOP;
        }

        CommonGunIndex gunIndex = indexOptional.get();
        String weaponType = gunIndex.getType();
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        IGunOperator operator = IGunOperator.fromLivingEntity(livingEntity);
        long fireTick = operator.getSynShootCoolDown();

        if (livingEntity instanceof IClientPlayerGunOperator clientOperator) {
            fireTick = Math.max(fireTick, clientOperator.getClientShootCoolDown());
        }

        if (event.getAnimatableEntity().isTacGunAnimationNeedReload()) {
            playLoopAnimation(event, "empty");
        }
        event.getAnimatableEntity().setTacGunAnimationNeedReload(false);

        float reloadProgress = operator.getSynReloadState().getCountDown();
        if (reloadProgress > 0) {
            return getGunTypeAnimation(event, weaponType, "tac:reload:", LoopType.PLAY_ONCE);
        }

        long synMeleeCoolDown = operator.getSynMeleeCoolDown();
        if (synMeleeCoolDown > 0) {
            return getGunTypeAnimation(event, weaponType, "tac:melee:", LoopType.PLAY_ONCE);
        }

        if (fireTick > 0) {
            float aimProgress = operator.getSynAimingProgress();
            boolean isClimbing = !livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING
                && Math.abs(event.getLimbSwingAmount()) <= 0.05;

            if (isClimbing) {
                return getGunTypeAnimation(event, weaponType, "tac:climbing:fire:", LoopType.PLAY_ONCE);
            }
            if (aimProgress > 0) {
                return getGunTypeAnimation(event, weaponType, "tac:aim:fire:", LoopType.PLAY_ONCE);
            } else {
                return getGunTypeAnimation(event, weaponType, "tac:hold:fire:", LoopType.PLAY_ONCE);
            }
        }
        return PlayState.CONTINUE;
    }

    static void openFlashShellRender(LivingEntity livingEntity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (livingEntity.equals(player)) {
            MuzzleFlashRender.isSelf = true;
            ShellRender.isSelf = true;
        }
    }

    static void stopFlashShellRender() {
        MuzzleFlashRender.isSelf = false;
        ShellRender.isSelf = false;
    }

    @Nullable
    static ResourceLocation getGunId(ItemStack itemInHand) {
        IGun iGun = IGun.getIGunOrNull(itemInHand);
        if (iGun == null) {
            return null;
        }
        return iGun.getGunId(itemInHand);
    }

    @NotNull
    private static PlayState getGunTypeAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event,
        String weaponType, String prefix) {
        return getGunTypeAnimation(event, weaponType, prefix, LoopType.LOOP);
    }

    @NotNull
    private static PlayState getGunTypeAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event,
        String weaponType, String prefix, LoopType loopType) {
        ConditionTAC conditionTAC = event.getAnimatableEntity().getConditionManager().getTAC();
        if (conditionTAC != null) {
            ItemStack stack = event.getAnimatableEntity().getEntity().getMainHandItem();
            String name = conditionTAC.doTest(stack, prefix);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, loopType);
            }
        }
        if (isType(weaponType, GunTabType.PISTOL)) {
            return playAnimation(event, prefix + "pistol", loopType);
        }
        if (isType(weaponType, GunTabType.RPG)) {
            return playAnimation(event, prefix + "rpg", loopType);
        }
        return playAnimation(event, prefix + "rifle", loopType);
    }

    @NotNull
    private static PlayState playLoopAnimation(AnimationEvent<?> event, String animationName) {
        return playAnimation(event, animationName, LoopType.LOOP);
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }

    private static boolean isType(String type, GunTabType tabType) {
        return type.equals(tabType.name().toLowerCase(Locale.ENGLISH));
    }
}
