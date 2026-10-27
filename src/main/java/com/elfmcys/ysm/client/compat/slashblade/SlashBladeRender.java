package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.client.renderer.model.BladeModelManager;
import mods.flammpfeil.slashblade.client.renderer.model.obj.WavefrontObject;
import mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@SuppressWarnings("removal")
public class SlashBladeRender {
    private static final ResourceLocation RESOURCE_DEFAULT_MODEL =
        new ResourceLocation("slashblade", "model/blade.obj");
    private static final ResourceLocation RESOURCE_DEFAULT_TEXTURE =
        new ResourceLocation("slashblade", "model/blade.png");

    public static void renderSlashBlade(PoseStack matrixStack, MultiBufferSource bufferIn, int lightIn,
        ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        stack.getCapability(CapabilitySlashBlade.BLADESTATE).ifPresent(bladeState -> {
            ResourceLocation texture = bladeState.getTexture().orElse(RESOURCE_DEFAULT_TEXTURE);
            WavefrontObject obj =
                BladeModelManager.getInstance().getModel(bladeState.getModel().orElse(RESOURCE_DEFAULT_MODEL));
            String part;
            if (bladeState.isBroken()) {
                part = "blade_damaged";
            } else {
                part = "blade";
            }
            BladeRenderState.renderOverrided(stack, obj, part, texture, matrixStack, bufferIn, lightIn);
            BladeRenderState.renderOverridedLuminous(stack, obj, part + "_luminous", texture, matrixStack, bufferIn,
                lightIn);
            BladeRenderState.renderOverrided(stack, obj, "sheath", texture, matrixStack, bufferIn, lightIn);
            BladeRenderState.renderOverridedLuminous(stack, obj, "sheath_luminous", texture, matrixStack, bufferIn,
                lightIn);
        });
    }

    public static void renderMainhandSlashBlade(LivingEntity livingEntity, GeoRenderData data, PoseStack matrixStack,
        MultiBufferSource bufferIn, int lightIn, ItemStack stack) {
        if (SlashBladeCompat.isSlashBladeItem(stack)) {
            // 如果没有 bladeBones 和 sheathBones，说明是旧版渲染
            if (data.modelState.locatorGroupSize(PlayerLocator.get().blade) == 0
                || data.modelState.locatorGroupSize(PlayerLocator.get().sheath) == 0
                || data.modelState.locatorGroupSize(PlayerLocator.get().leftWaist) == 0) {
                oldMainhandSlashBlade(livingEntity, data, matrixStack, bufferIn, lightIn, stack);
            } else {
                stack.getCapability(CapabilitySlashBlade.BLADESTATE).ifPresent(bladeState -> {
                    newMainhandSlashBlade(bladeState, data, matrixStack, bufferIn, lightIn, stack);
                });
            }
        }
    }

    private static void newMainhandSlashBlade(ISlashBladeState bladeState, GeoRenderData data, PoseStack matrixStack,
        MultiBufferSource bufferIn, int lightIn, ItemStack stack) {
        ResourceLocation texture = bladeState.getTexture().orElse(RESOURCE_DEFAULT_TEXTURE);
        WavefrontObject obj =
            BladeModelManager.getInstance().getModel(bladeState.getModel().orElse(RESOURCE_DEFAULT_MODEL));
        String part;
        if (bladeState.isBroken()) {
            part = "blade_damaged";
        } else {
            part = "blade";
        }

        // 定位点定在刀中心，刀朝向前方（默认）
        data.modelState.visitLocatorGroup(PlayerLocator.get().leftWaist, matrixStack, locatorPose -> {
            locatorPose.translate(0, 0.025, -0.6);
            locatorPose.scale(0.01F, 0.01F, 0.01F);
            locatorPose.mulPose(Axis.YP.rotationDegrees(-90));
            locatorPose.mulPose(Axis.ZP.rotationDegrees(180));

            BladeRenderState.renderOverrided(stack, obj, part, texture, locatorPose, bufferIn, lightIn);
            BladeRenderState.renderOverridedLuminous(stack, obj, part + "_luminous", texture, locatorPose, bufferIn,
                lightIn);
            BladeRenderState.renderOverrided(stack, obj, "sheath", texture, locatorPose, bufferIn, lightIn);
            BladeRenderState.renderOverridedLuminous(stack, obj, "sheath_luminous", texture, locatorPose, bufferIn,
                lightIn);
        });

        // 定位点定在刀柄中心，刀朝向前方（默认）
        data.modelState.visitLocatorGroup(PlayerLocator.get().blade, matrixStack, locatorPose -> {
            locatorPose.translate(0, 0.035, 0);
            locatorPose.scale(0.01F, 0.01F, 0.01F);
            locatorPose.mulPose(Axis.YP.rotationDegrees(-90));
            locatorPose.mulPose(Axis.XP.rotationDegrees(180));

            BladeRenderState.renderOverrided(stack, obj, part, texture, locatorPose, bufferIn, lightIn);
            BladeRenderState.renderOverridedLuminous(stack, obj, part + "_luminous", texture, locatorPose, bufferIn,
                lightIn);
        });

        // 定位点定在刀鞘最末端，刀朝向前方（默认）
        data.modelState.visitLocatorGroup(PlayerLocator.get().sheath, matrixStack, locatorPose -> {
            locatorPose.translate(0, 0.025, -0.6);
            locatorPose.scale(0.01F, 0.01F, 0.01F);
            locatorPose.mulPose(Axis.YP.rotationDegrees(-90));
            locatorPose.mulPose(Axis.ZP.rotationDegrees(180));

            BladeRenderState.renderOverrided(stack, obj, "sheath", texture, locatorPose, bufferIn, lightIn);
            BladeRenderState.renderOverridedLuminous(stack, obj, "sheath_luminous", texture, locatorPose, bufferIn,
                lightIn);
        });
    }

    private static void oldMainhandSlashBlade(LivingEntity livingEntity, GeoRenderData data, PoseStack matrixStack,
        MultiBufferSource bufferIn, int lightIn, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        stack.getCapability(CapabilitySlashBlade.BLADESTATE).ifPresent(bladeState -> {
            ResourceLocation texture = bladeState.getTexture().orElse(RESOURCE_DEFAULT_TEXTURE);
            WavefrontObject obj =
                BladeModelManager.getInstance().getModel(bladeState.getModel().orElse(RESOURCE_DEFAULT_MODEL));
            String part;
            if (bladeState.isBroken()) {
                part = "blade_damaged";
            } else {
                part = "blade";
            }

            // 主手的刀渲染在左边
            data.modelState.visitLocatorGroupOrDefault(PlayerLocator.get().leftWaist, matrixStack, locatorPose -> {
                locatorPose.translate(0, 0, -0.7);
                locatorPose.scale(0.01F, 0.01F, 0.01F);
                locatorPose.mulPose(Axis.YP.rotationDegrees(-90));
                locatorPose.mulPose(Axis.ZP.rotationDegrees(180));

                BladeRenderState.renderOverrided(stack, obj, "sheath", texture, locatorPose, bufferIn, lightIn);
                BladeRenderState.renderOverridedLuminous(stack, obj, "sheath_luminous", texture, locatorPose, bufferIn,
                    lightIn);
                long time = livingEntity.level().getGameTime() - bladeState.getLastActionTime();
                if (time < 5) {
                    float i = time + data.partialTicks;
                    locatorPose.translate(0, 0, -0.5 / 0.007);
                    locatorPose.mulPose(Axis.YP.rotationDegrees(60 + i * 48));
                    locatorPose.mulPose(Axis.XP.rotationDegrees(90));
                }
                BladeRenderState.renderOverrided(stack, obj, part, texture, locatorPose, bufferIn, lightIn);
                BladeRenderState.renderOverridedLuminous(stack, obj, part + "_luminous", texture, locatorPose, bufferIn,
                    lightIn);
            }, locatorPose -> {
                locatorPose.translate(-0.25, 1.25, 0);
                locatorPose.mulPose(Axis.XP.rotationDegrees(20));
            });
        });
    }

    public static void renderOffhandSlashBlade(GeoRenderData data, PoseStack matrixStack, MultiBufferSource bufferIn,
        int lightIn, ItemStack stack) {
        if (SlashBladeCompat.isSlashBladeItem(stack)) {
            // 副手的刀渲染在右边
            data.modelState.visitLocatorGroupOrDefault(PlayerLocator.get().rightWaist, matrixStack, locatorPose -> {
                locatorPose.translate(0, 0, -0.7);
                locatorPose.scale(0.01F, 0.01F, 0.01F);
                locatorPose.mulPose(Axis.YP.rotationDegrees(-90));
                locatorPose.mulPose(Axis.ZP.rotationDegrees(180));
                SlashBladeRender.renderSlashBlade(locatorPose, bufferIn, lightIn, stack);
            }, locatorPose -> {
                locatorPose.translate(0.25, 1.25, 0);
                locatorPose.mulPose(Axis.XP.rotationDegrees(5));
            });
        }
    }
}
