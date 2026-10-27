package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.ParrotModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ParrotRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.player.Player;

public class CustomParrotOnShoulderLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private static final String ID = "id";
    private static final String VARIANT = "Variant";
    private final ParrotModel model;

    public CustomParrotOnShoulderLayer(EntityRendererProvider.Context context) {
        this.model = new ParrotModel(context.bakeLayer(ModelLayers.PARROT));
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, CustomPlayerEntity animatable,
        GeoRenderData renderData, int packedLight, int overlay) {
        var player = animatable.getEntity();
        var anim = renderData.animationData;
        renderData.modelState.visitLocatorGroup(PlayerLocator.get().leftShoulder, poseStack, locatorPose -> {
            this.render(poseStack, buffer, packedLight, player, anim.limbSwing, anim.limbSwingAmount, anim.netHeadYaw,
                anim.headPitch, true);
        });
        renderData.modelState.visitLocatorGroup(PlayerLocator.get().rightShoulder, poseStack, locatorPose -> {
            this.render(poseStack, buffer, packedLight, player, anim.limbSwing, anim.limbSwingAmount, anim.netHeadYaw,
                anim.headPitch, false);
        });
    }

    private void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, Player player, float limbSwing,
        float limbSwingAmount, float netHeadYaw, float headPitch, boolean leftShoulder) {
        CompoundTag shoulderTag = leftShoulder ? player.getShoulderEntityLeft() : player.getShoulderEntityRight();
        EntityType.byString(shoulderTag.getString(ID)).filter(type -> type == EntityType.PARROT).ifPresent(type -> {

            poseStack.translate(0, 1.5, 0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180));
            Parrot.Variant variant = Parrot.Variant.byId(shoulderTag.getInt(VARIANT));
            VertexConsumer bufferBuffer =
                buffer.getBuffer(this.model.renderType(ParrotRenderer.getVariantTexture(variant)));
            this.model.renderOnShoulder(poseStack, bufferBuffer, packedLight, OverlayTexture.NO_OVERLAY, limbSwing,
                limbSwingAmount, netHeadYaw, headPitch, player.tickCount);
        });
    }
}
