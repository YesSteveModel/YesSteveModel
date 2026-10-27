package com.elfmcys.ysm.client.compat.backpack.sophisticated;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import static net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackLayerRenderer.renderBackpack;

public class SophisticatedBackpackLayerRenderer extends GeoLayerRenderer<CustomPlayerEntity> {
    private final EntityModel<Player> model;

    SophisticatedBackpackLayerRenderer() {
        this.model = getEmptyModel();
    }

    /**
     * 空 EntityModel，仅用于渲染背包时的占位符
     */
    @SuppressWarnings("all")
    private static EntityModel<Player> getEmptyModel() {
        return new EntityModel<>() {
            @Override
            public void setupAnim(Player entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                float netHeadYaw, float headPitch) {}

            @Override
            public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                float red, float green, float blue, float alpha) {}
        };
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, CustomPlayerEntity animatable,
        GeoRenderData renderData, int packedLight, int overlay) {
        Player player = animatable.getEntity();
        ItemStack backpack = SophisticatedCompat.getBackpackItemStack(player);
        if (backpack != null && !backpack.isEmpty()) {
            renderData.modelState.visitLocatorGroup(PlayerLocator.get().backpack, poseStack, locatorPose -> {
                locatorPose.mulPose(Axis.XP.rotationDegrees(180));
                locatorPose.mulPose(Axis.YP.rotationDegrees(180));
                locatorPose.translate(0, -0.1, 0);
                renderBackpack(this.model, player, locatorPose, buffer, packedLight, backpack, false);
            });
        }

    }
}
