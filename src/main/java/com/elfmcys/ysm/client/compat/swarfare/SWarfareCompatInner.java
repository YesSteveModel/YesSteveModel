package com.elfmcys.ysm.client.compat.swarfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.item.LungeMine;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@SuppressWarnings("removal")
public class SWarfareCompatInner {
    private static final TagKey<Item> PISTOL =
        TagKey.create(Registries.ITEM, new ResourceLocation("superbwarfare:animated/pistol"));
    private static final TagKey<Item> RPG =
        TagKey.create(Registries.ITEM, new ResourceLocation("superbwarfare:animated/rpg"));

    static boolean isGun(ItemStack stack) {
        return stack.getItem() instanceof GunItem;
    }

    static boolean shouldHidePlayerRender(Player player) {
        if (player.getVehicle() instanceof VehicleEntity vehicle) {
            return vehicle.hidePassenger(player);
        }
        return false;
    }

    static void renderOffhandGun(ItemStack heldItem, GeoRenderData data, LivingEntity player, PoseStack poseStack,
        MultiBufferSource buffer, int packedLight) {
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        if (heldItem.is(PISTOL)) {
            data.modelState.visitLocatorGroup(PlayerLocator.get().pistol, poseStack, locatorPose -> {
                locatorPose.translate(0, -0.125, 0);
                locatorPose.scale(0.65f, 0.65f, 0.65f);
                locatorPose.mulPose(Axis.YP.rotationDegrees(90));
                locatorPose.mulPose(Axis.ZP.rotationDegrees(-90.0F));
                renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY,
                    locatorPose, buffer, player.level(), player.getId());
            });
        }
        if (!heldItem.is(PISTOL)) {
            data.modelState.visitLocatorGroup(PlayerLocator.get().rifle, poseStack, locatorPose -> {
                locatorPose.scale(0.65f, 0.65f, 0.65f);
                locatorPose.mulPose(Axis.YP.rotationDegrees(-180.0F));
                renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY,
                    locatorPose, buffer, player.level(), player.getId());
            });
        }
    }

    @Nullable
    static PlayState
        playLungeMineAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        if (!Objects.equals(Minecraft.getInstance().player, livingEntity)) {
            return null;
        }
        if (!(livingEntity.getMainHandItem().getItem() instanceof LungeMine)) {
            return null;
        }
        if (ClientEventHandler.lungeSprint > 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_sprint");
        } else if (ClientEventHandler.lungeDraw > 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_draw");
        } else if (ClientEventHandler.lungeAttack > 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_fire");
        } else if (livingEntity.isSprinting() && livingEntity.onGround() && ClientEventHandler.lungeDraw == 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_run");
        } else {
            return playAnimation(event, "superbwarfare:lunge_mine_idle");
        }
    }

    /**
     * tac:idle tac:run tac:walk
     */
    static PlayState playGunMainAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event,
        String animationName, LoopType loopType) {
        String tacName = "tac:" + animationName;
        var playerAnimation = event.getAnimatableEntity().getAnimation(tacName);
        if (playerAnimation != null) {
            return playAnimation(event, tacName, loopType);
        }
        return playAnimation(event, animationName, loopType);
    }

    @NotNull
    private static PlayState getGunTypeAnimation(
        AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, ItemStack gun, String prefix) {
        return getGunTypeAnimation(event, gun, prefix, LoopType.LOOP);
    }

    /**
     * tac:hold:pistol tac:aim:pistol tac:reload:pistol tac:aim_shoot:pistol tac:hold_shoot:pistol tac:run:pistol
     */
    static PlayState playGunHoldAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event,
        ItemStack heldItem) {
        // 先检查刺雷
        PlayState playState = playLungeMineAnimation(event);
        if (playState != null) {
            return playState;
        }
        // 再检查枪械
        if (!(heldItem.getItem() instanceof GunItem)) {
            return null;
        }
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();

        if (!livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING) {
            if (Math.abs(event.getLimbSwingAmount()) > 0.05) {
                return getGunTypeAnimation(event, heldItem, "tac:climb:");
            } else {
                return getGunTypeAnimation(event, heldItem, "tac:climbing:");
            }
        }

        // zoomTime 是玩家客户端数据，需要额外判断是否是当前玩家
        double aimProgress = ClientEventHandler.zoomTime;
        if (!Objects.equals(Minecraft.getInstance().player, livingEntity)) {
            aimProgress = 0;
        }
        if (aimProgress > 0.3) {
            return getGunTypeAnimation(event, heldItem, "tac:aim:");
        } else {
            if (livingEntity.onGround() && livingEntity.isSprinting()) {
                return getGunTypeAnimation(event, heldItem, "tac:run:");
            }
            return getGunTypeAnimation(event, heldItem, "tac:hold:");
        }
    }

    /**
     * 这些动画可能是带有后摇的动画，故需要单独分一个频道来播放，从而才能超过时长进行播放
     */
    static PlayState playGunOnceAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event,
        ItemStack heldItem) {
        if (!(heldItem.getItem() instanceof GunItem gunItem)) {
            return PlayState.STOP;
        }
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        GunData gunData = GunData.from(heldItem);
        if (!Objects.equals(Minecraft.getInstance().player, livingEntity)) {
            return PlayState.STOP;
        }

        // 重置动画
        if (event.getCodedController() != null && event.getCodedController().isAnimFinished()) {
            event.getCodedController().indicateReload();
        }

        if (gunData.reloading() && gunData.reload.time() > 40) {
            return getGunTypeAnimation(event, heldItem, "tac:reload:", LoopType.PLAY_ONCE);
        }

        int meleeTime = ClientEventHandler.gunMelee;
        if (meleeTime > 5) {
            return getGunTypeAnimation(event, heldItem, "tac:melee:", LoopType.PLAY_ONCE);
        }

        double fireTick = ClientEventHandler.fireRotTimer;
        if (0 < fireTick && fireTick < 1) {
            double aimProgress = ClientEventHandler.zoomTime;
            boolean isClimbing = !livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING
                && Math.abs(event.getLimbSwingAmount()) <= 0.05;

            if (isClimbing) {
                return getGunTypeAnimation(event, heldItem, "tac:climbing:fire:", LoopType.PLAY_ONCE);
            }
            if (aimProgress > 0.3) {
                return getGunTypeAnimation(event, heldItem, "tac:aim:fire:", LoopType.PLAY_ONCE);
            } else {
                return getGunTypeAnimation(event, heldItem, "tac:hold:fire:", LoopType.PLAY_ONCE);
            }
        }
        return PlayState.CONTINUE;
    }

    @NotNull
    private static PlayState getGunTypeAnimation(
        AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, ItemStack gun, String prefix,
        LoopType loopType) {
        var gunCondition = event.getAnimatableEntity().getConditionManager().getTAC();
        if (gunCondition != null) {
            ItemStack stack = event.getAnimatableEntity().getEntity().getMainHandItem();
            String name = gunCondition.doTest(stack, prefix);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, loopType);
            }
        }
        if (gun.is(PISTOL)) {
            return playAnimation(event, prefix + "pistol", loopType);
        }
        if (gun.is(RPG)) {
            return playAnimation(event, prefix + "rpg", loopType);
        }
        return playAnimation(event, prefix + "rifle", loopType);
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName) {
        event.getCodedController().setAnimation(animationName);
        return PlayState.CONTINUE;
    }
}
