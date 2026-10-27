package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At(value = "HEAD"))
    private void beforeRenderLevel(PoseStack pMatrixStack, float pPartialTicks, long pFinishTimeNano,
        boolean pDrawBlockOutline, Camera pActiveRenderInfo, GameRenderer pGameRenderer, LightTexture pLightmap,
        Matrix4f pProjection, CallbackInfo ci) {
        if (YesSteveModel.isAvailable()) {
            RenderUtil.setRenderingLevel(true);
            AnimationParallelTicker.scheduleAll(pPartialTicks);
        }
    }

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At(value = "RETURN"))
    private void afterRenderEntities(PoseStack pMatrixStack, float pPartialTicks, long pFinishTimeNano,
        boolean pDrawBlockOutline, Camera pActiveRenderInfo, GameRenderer pGameRenderer, LightTexture pLightmap,
        Matrix4f pProjection, CallbackInfo ci) {
        if (YesSteveModel.isAvailable()) {
            AnimationParallelTicker.waitAll();
            RenderUtil.setRenderingLevel(false);
        }
    }
}
