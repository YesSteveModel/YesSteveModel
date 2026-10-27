package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.compat.simplehat.SimpleHatsCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import static net.minecraft.world.entity.EquipmentSlot.HEAD;

public class CustomPlayerHeadLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private final ItemInHandRenderer handRenderer;

    public CustomPlayerHeadLayer(EntityRendererProvider.Context context) {
        this.handRenderer = context.getItemInHandRenderer();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, CustomPlayerEntity animatable,
        GeoRenderData renderData, int packedLight, int overlay) {
        var player = animatable.getEntity();
        ItemStack head = player.getItemBySlot(HEAD);
        if (!head.isEmpty() && !isArmorHead(head)) {
            renderHeadItem(poseStack, buffer, packedLight, renderData, player, head);
        }
        ItemStack curiosHead = SimpleHatsCompat.getCuriosHead(player);
        if (curiosHead != null && !curiosHead.isEmpty()) {
            renderHeadItem(poseStack, buffer, packedLight, renderData, player, curiosHead);
        }
    }

    private boolean isArmorHead(ItemStack itemStack) {
        return itemStack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == HEAD;
    }

    private void renderHeadItem(PoseStack poseStack, MultiBufferSource buffer, int packedLight, GeoRenderData data,
        Player player, ItemStack head) {
        data.modelState.visitLocatorGroup(PlayerLocator.get().head, poseStack, locatorPose -> {
            locatorPose.scale(0.625F, 0.625F, 0.625F);
            locatorPose.translate(0.0F, 0.25F, 0.0F);
            this.handRenderer.renderItem(player, head, ItemDisplayContext.HEAD, false, locatorPose, buffer,
                packedLight);
        });
    }
}
