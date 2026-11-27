package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.accessor.ILivingRenderer;
import com.elfmcys.ysm.api.rendering.event.RenderLayerEvent;
import com.elfmcys.ysm.api.rendering.event.RenderModelEvent;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.EntityRootTransform;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugController;
import com.elfmcys.ysm.client.physics.debug.PhysicsDebugWireframe;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

public abstract class GeoReplacedEntityRenderer<TEntity extends LivingEntity, T extends CustomHumanoidEntity<TEntity>>
    extends LivingEntityRenderer<TEntity, PlayerModel<TEntity>> implements IGeoRenderer<T> {
    protected final List<GeoLayerRenderer<T>> layerRenderers = new ObjectArrayList<>();

    public GeoReplacedEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
    }

    public static int getPackedOverlay(LivingEntity entity, float u) {
        return OverlayTexture.pack(OverlayTexture.u(u), OverlayTexture.v(entity.hurtTime > 0 || entity.deathTime > 0));
    }

    public void renderAnimatableEntity(T animatableEntity, float entityYaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight) {
        renderAnimatableEntity(animatableEntity, null, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    public void renderAnimatableEntity(T animatableEntity, @Nullable ResourceLocation textureOverride, float entityYaw,
        float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        renderReplacement(animatableEntity, textureOverride, entityYaw, partialTick, poseStack, bufferSource,
            packedLight, () -> {
            });
    }

    public boolean renderReplacement(T animatableEntity, @Nullable ResourceLocation textureOverride, float entityYaw,
        float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, Runnable handoff) {
        if (!animatableEntity.isPresentationAllowed())
            return false;
        var data = animatableEntity.update(partialTick);
        return renderPreparedReplacement(animatableEntity, data, textureOverride, entityYaw, partialTick, poseStack,
            bufferSource, packedLight, handoff);
    }

    public boolean renderPreparedReplacement(T animatableEntity, GeoRenderData data,
        @Nullable ResourceLocation textureOverride, float entityYaw, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, Runnable handoff) {
        if (!animatableEntity.isPresentationAllowed())
            return false;
        return animatableEntity.consumePrepared(data, borrowed -> {
            if (MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Pre<>(animatableEntity.getEntity(), this,
                partialTick, poseStack, bufferSource, packedLight))) {
                if (!animatableEntity.canConsume(borrowed, borrowed.ctx))
                    return false;
                handoff.run();
                return true;
            }
            if (!animatableEntity.canConsume(borrowed, borrowed.ctx))
                return false;
            handoff.run();
            renderPrepared(animatableEntity, borrowed, textureOverride, entityYaw, partialTick, poseStack, bufferSource,
                packedLight);
            return true;
        });
    }

    protected void renderPrepared(T animatableEntity, GeoRenderData data, @Nullable ResourceLocation textureOverride,
        float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        final TEntity entity = animatableEntity.getEntity();
        var mc = Minecraft.getInstance();
        if (data != null && mc.player != null) {
            poseStack.pushPose();
            try {

                if (data.rootTransform != null) {
                    data.rootTransform.applyModelPose(poseStack);
                } else {
                    EntityRootTransform.applyModelPose(entity, data.animationData.lerpBodyRot, poseStack);
                }

                var texture = textureOverride == null ? data.texture : textureOverride;
                var bodyVisible = this.isBodyVisible(entity) && !entity.isInvisibleTo(mc.player);
                var glowing = mc.shouldEntityAppearGlowing(entity);
                var renderType = getRenderType(texture, bodyVisible, glowing, data.modelState.hasTranslucentVertices());

                var renderLayersFirst = data.renderLayersFirst;
                var packedOverlay = getPackedOverlay(entity, getOverlayProgress(entity, partialTick));

                if (renderType != null) {
                    preRender(data, animatableEntity, poseStack, bufferSource, packedLight, packedOverlay, Color.WHITE);
                    if (bufferSource instanceof com.elfmcys.ysm.client.gui.GuiModelFraming framing
                        && !framing.apply(data.installation, data.modelState, poseStack))
                        return;
                    if (renderLayersFirst && !entity.isSpectator() && animatableEntity.canConsume(data, data.ctx)) {
                        renderLayer(poseStack, bufferSource, animatableEntity, data, packedLight, packedOverlay);
                    }

                    if (animatableEntity.canConsume(data, data.ctx)) {
                        var event = new RenderModelEvent(animatableEntity.getEntity(), data, bufferSource, renderType,
                            poseStack, packedLight, packedOverlay, Color.WHITE.getColor());
                        if (!YesSteveModel.postEvent(event) && animatableEntity.canConsume(data, data.ctx)) {
                            render(data, animatableEntity, renderType, poseStack, bufferSource, packedLight,
                                packedOverlay, Color.WHITE);
                        }
                    }

                    if (!renderLayersFirst && !entity.isSpectator() && animatableEntity.canConsume(data, data.ctx)) {
                        renderLayer(poseStack, bufferSource, animatableEntity, data, packedLight, packedOverlay);
                    }
                    if (animatableEntity.canConsume(data, data.ctx))
                        postRender(data, animatableEntity, poseStack, bufferSource, packedLight, packedOverlay,
                            Color.WHITE);
                    if (PhysicsDebugController.isTarget(animatableEntity)) {
                        var debug = PhysicsDebugController.capture(animatableEntity, data);
                        if (debug != null)
                            PhysicsDebugWireframe.render(poseStack, bufferSource, debug);
                    }
                }
            } finally {
                poseStack.popPose();
            }
        }

        if (animatableEntity.canConsume(data, data.ctx)) {
            ((ILivingRenderer)this).ysm$renderNameTag(entity, entityYaw, partialTick, poseStack, bufferSource,
                packedLight);
            if (animatableEntity.canConsume(data, data.ctx))
                MinecraftForge.EVENT_BUS.post(
                    new RenderLivingEvent.Post<>(entity, this, partialTick, poseStack, bufferSource, packedLight));
        }
    }

    protected void renderLayer(PoseStack poseStack, MultiBufferSource buffer, T animatable, GeoRenderData renderData,
        int packedLight, int overlay) {
        var event = new RenderLayerEvent(animatable.getEntity(), renderData, poseStack, buffer, packedLight, overlay);
        if (!YesSteveModel.postEvent(event)) {
            for (GeoLayerRenderer<T> layerRenderer : this.layerRenderers) {
                if (!animatable.canConsume(renderData, renderData.ctx))
                    break;
                layerRenderer.render(poseStack, buffer, animatable, renderData, packedLight, overlay);
            }
        }
    }

    protected float getOverlayProgress(TEntity entity, float partialTicks) {
        return 0.0F;
    }

    @Override
    public boolean shouldShowName(TEntity entity) {
        double nameRenderDistance = entity.isDiscrete() ? 32d : 64d;
        if (this.entityRenderDispatcher.distanceToSqr(entity) >= nameRenderDistance * nameRenderDistance) {
            return false;
        }
        return entity == this.entityRenderDispatcher.crosshairPickEntity && entity.hasCustomName()
            && Minecraft.renderNames();
    }

    public final boolean addLayer(GeoLayerRenderer<T> layer) {
        return this.layerRenderers.add(layer);
    }
}
