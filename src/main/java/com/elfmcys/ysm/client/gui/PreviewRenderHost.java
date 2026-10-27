package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.RenderUtil;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Draws one already-published player target into a private host framebuffer. */
public final class PreviewRenderHost {
    public static final int WIDTH = 156;
    public static final int HEIGHT = 270;
    private static final float GUI_SCALE = 3.0F;

    private PreviewRenderHost() {}

    public static NativeImage render(ResourceRequest request, ResourceLease lease) {
        RenderSystem.assertOnRenderThread();
        var acquired = lease.poll();
        if (!(acquired instanceof AcquireResult.Ready ready)) {
            throw new IllegalArgumentException("Preview rendering requires a ready target");
        }
        var target = ready.target();
        if (target.playerResources() == null) {
            throw new IllegalArgumentException("Preview rendering requires a player target");
        }

        var entity = new CustomGuiPlayerEntity();
        try {
            entity.installPreviewResource(request, new BorrowedTargetLease(request, target));
            entity.getPreviewInfo().setPreview(AnimationRegister.IDLE);
            entity.getPreviewInfo().setHover(AnimationRegister.EMPTY);
            entity.getPreviewInfo().setFocus(AnimationRegister.EMPTY);
            return renderEntity(entity, target);
        } finally {
            entity.reset();
        }
    }

    private static NativeImage renderEntity(CustomGuiPlayerEntity entity, ModelRenderTarget target) {
        var minecraft = Minecraft.getInstance();
        var mainTarget = minecraft.getMainRenderTarget();
        var previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        TextureTarget offscreen = null;
        Throwable primaryFailure = null;
        modelView.pushPose();
        try {
            modelView.setIdentity();
            // GUI model transforms assume a camera at Z=-2000 within the 1000..3000 depth range.
            modelView.translate(0.0D, 0.0D, -2_000.0D);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0F, WIDTH / GUI_SCALE, HEIGHT / GUI_SCALE, 0.0F, 1_000.0F, 3_000.0F),
                VertexSorting.ORTHOGRAPHIC_Z);

            offscreen = new TextureTarget(WIDTH, HEIGHT, true, Minecraft.ON_OSX);
            offscreen.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            offscreen.clear(Minecraft.ON_OSX);
            offscreen.bindWrite(true);
            RenderUtil.renderModelInGui(WIDTH / GUI_SCALE / 2.0F, HEIGHT / GUI_SCALE / 2.0F + 20.0F, 30.0F,
                minecraft.getFrameTime(), entity, RegisterEntityRenderersEvent.getPlayerRenderer(),
                disablePreviewRotation(target.info().getSettings()), true);
            return readPixels(offscreen);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            if (offscreen != null) {
                try {
                    offscreen.destroyBuffers();
                } catch (RuntimeException | Error failure) {
                    cleanupFailure = failure;
                }
            }
            try {
                mainTarget.bindWrite(true);
            } catch (RuntimeException | Error failure) {
                cleanupFailure = append(cleanupFailure, failure);
            }
            try {
                RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            } catch (RuntimeException | Error failure) {
                cleanupFailure = append(cleanupFailure, failure);
            }
            try {
                modelView.popPose();
                RenderSystem.applyModelViewMatrix();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = append(cleanupFailure, failure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof Error error) {
                    throw error;
                } else {
                    throw (RuntimeException)cleanupFailure;
                }
            }
        }
    }

    private static NativeImage readPixels(TextureTarget target) {
        var pixels = new NativeImage(WIDTH, HEIGHT, false);
        try {
            RenderSystem.bindTexture(target.getColorTextureId());
            // Vanilla screenshot readback discards alpha, including the transparent clear color.
            pixels.downloadTexture(0, false);
            pixels.flipY();
            return pixels;
        } catch (RuntimeException | Error failure) {
            pixels.close();
            throw failure;
        }
    }

    static boolean disablePreviewRotation(SettingsProto settings) {
        // An authored GUI pose may supply its own orientation; idle needs the ordinary view instead.
        var customPreview = settings.previewAnimation()
            .filter(name -> !name.isBlank() && !name.equals(AnimationRegister.IDLE)).isPresent();
        return settings.disablePreviewRotation() && !customPreview;
    }

    private static Throwable append(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private record BorrowedTargetLease(ResourceRequest request, ModelRenderTarget target) implements ResourceLease {
        @Override
        public ModelResourcePurpose purpose() {
            return request.purpose();
        }

        @Override
        public AcquireResult poll() {
            return new AcquireResult.Ready(target);
        }

        @Override
        public boolean isCurrent(ResourceRequest candidate) {
            return request.equals(candidate);
        }

        @Override
        public void cancelPending() {}

        @Override
        public void close() {}
    }
}
