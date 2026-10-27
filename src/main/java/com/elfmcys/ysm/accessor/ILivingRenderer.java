package com.elfmcys.ysm.accessor;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;

public interface ILivingRenderer {
    void ysm$renderNameTag(LivingEntity pEntity, float pEntityYaw, float pPartialTicks, PoseStack pMatrixStack,
        MultiBufferSource pBuffer, int pPackedLight);
}
