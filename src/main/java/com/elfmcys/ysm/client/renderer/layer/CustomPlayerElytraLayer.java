package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.util.EquipmentUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;

@SuppressWarnings("removal")
public class CustomPlayerElytraLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private static final ResourceLocation WINGS_LOCATION = new ResourceLocation("textures/entity/elytra.png");
    private final ElytraModel<LivingEntity> elytraModel;

    public CustomPlayerElytraLayer(EntityRendererProvider.Context context) {
        elytraModel = new ElytraModel<>(context.getModelSet().bakeLayer(ModelLayers.ELYTRA));
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, CustomPlayerEntity animatable,
        GeoRenderData renderData, int packedLight, int overlay) {
        var player = animatable.getEntity();
        ItemStack stack = EquipmentUtil.getEquippedElytraItem(player);
        if (!stack.isEmpty() && player instanceof AbstractClientPlayer clientPlayer) {
            ResourceLocation texture;
            if (clientPlayer.isElytraLoaded() && clientPlayer.getElytraTextureLocation() != null) {
                texture = clientPlayer.getElytraTextureLocation();
            } else if (clientPlayer.isCapeLoaded() && clientPlayer.getCloakTextureLocation() != null
                && player.isModelPartShown(PlayerModelPart.CAPE)) {
                texture = clientPlayer.getCloakTextureLocation();
            } else {
                texture = WINGS_LOCATION;
            }
            renderData.modelState.visitLocatorGroup(PlayerLocator.get().elytra, poseStack, locatorPose -> {
                locatorPose.translate(0, 1.5, 0);
                locatorPose.mulPose(Axis.ZP.rotationDegrees(180));
                locatorPose.scale(2.0f, 2.0f, 2.0f);
                var anim = renderData.animationData;
                this.elytraModel.setupAnim(player, anim.limbSwing, anim.limbSwingAmount, anim.lerpedAge,
                    anim.netHeadYaw, anim.headPitch);
                var vertexConsumer = ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(texture),
                    false, stack.hasFoil());
                this.elytraModel.renderToBuffer(locatorPose, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F);
            });
        }
    }
}
