package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.accessor.ILivingRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@SuppressWarnings("unchecked,rawtypes")
@Mixin(LivingEntityRenderer.class)
public abstract class LivingRendererMixin extends EntityRenderer implements ILivingRenderer {
    protected LivingRendererMixin(EntityRendererProvider.Context pContext) {
        super(pContext);
    }

    @Unique
    @Override
    public void ysm$renderNameTag(LivingEntity pEntity, float pEntityYaw, float pPartialTicks, PoseStack pMatrixStack,
        MultiBufferSource pBuffer, int pPackedLight) {
        super.render(pEntity, pEntityYaw, pPartialTicks, pMatrixStack, pBuffer, pPackedLight);
    }
}
