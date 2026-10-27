package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

public abstract class GeoProjectilesRenderer<TEntity extends Projectile, T extends AnimatableEntity<TEntity>>
    extends EntityRenderer<TEntity> implements IGeoRenderer<T> {
    public GeoProjectilesRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    public void render(T animatable, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
        int packedLight) {
        renderReplacement(animatable, yaw, partialTick, poseStack, bufferSource, packedLight);
    }

    public boolean renderReplacement(T animatable, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        if (!animatable.isPresentationAllowed())
            return false;

        var mc = Minecraft.getInstance();
        var data = animatable.update(partialTick);
        return animatable.consumePrepared(data, borrowed -> {
            if (mc.player == null)
                return false;
            {
                var entity = animatable.getEntity();
                var bodyVisible = !entity.isInvisibleTo(mc.player);
                var glowing = mc.shouldEntityAppearGlowing(entity);
                var renderType =
                    getRenderType(data.texture, bodyVisible, glowing, data.modelState.hasTranslucentVertices());

                if (renderType != null && (bodyVisible || glowing)) {
                    poseStack.pushPose();
                    try {
                        poseStack.mulPose(
                            Axis.YP.rotationDegrees(Mth.lerp(partialTick, entity.yRotO, entity.getYRot()) - 90));
                        poseStack
                            .mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, entity.xRotO, entity.getXRot())));
                        preRender(data, animatable, poseStack, bufferSource, packedLight, getPackedOverlay(entity, 0),
                            Color.WHITE);
                        if (!animatable.canConsume(data, data.ctx))
                            return false;
                        if (!render(data, animatable, renderType, poseStack, bufferSource, packedLight,
                            getPackedOverlay(entity, 0), Color.WHITE))
                            return false;
                        if (animatable.canConsume(data, data.ctx))
                            postRender(data, animatable, poseStack, bufferSource, packedLight,
                                getPackedOverlay(entity, 0), Color.WHITE);
                    } finally {
                        poseStack.popPose();
                    }
                }
            }
            if (animatable.canConsume(data, data.ctx))
                super.render(animatable.getEntity(), yaw, partialTick, poseStack, bufferSource, packedLight);
            return true;
        });
    }

    public static int getPackedOverlay(Entity entity, float uIn) {
        return OverlayTexture.pack(OverlayTexture.u(uIn), OverlayTexture.v(false));
    }
}
