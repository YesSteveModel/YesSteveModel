package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.storage.PreviewPixels;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

public class CustomTexture extends AbstractTexture {
    private final ImageSource source;
    private final Decoder decoder;
    private final Uploader uploader;
    private @Nullable Throwable failure;
    private PreviewPixels prepared;
    private final PreviewStore.EncodedPreview ownedSource;
    private final java.util.function.BiConsumer<CustomTexture, PreviewPixels> preparedUploader;
    private boolean closed;
    private Runnable invalidated = () -> {};
    private boolean loaded;

    public CustomTexture(ImageSource source) {
        this(source, CustomTexture::decode, CustomTexture::upload);
    }

    CustomTexture(ImageSource source, Decoder decoder, Uploader uploader) {
        this(source, decoder, uploader, CustomTexture::uploadPrepared);
    }

    CustomTexture(ImageSource source, Decoder decoder, Uploader uploader,
        java.util.function.BiConsumer<CustomTexture, PreviewPixels> preparedUploader) {
        Objects.requireNonNull(source, "source");
        if (source instanceof PreviewStore.EncodedPreview encoded) {
            ownedSource = encoded.acquireContent();
            prepared = encoded.takePixels();
            this.source = ownedSource;
        } else {
            ownedSource = null;
            this.source = source;
        }
        this.preparedUploader = Objects.requireNonNull(preparedUploader, "preparedUploader");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
    }

    @Override
    public void load(ResourceManager resourceManager) {
        RenderSystem.assertOnRenderThreadOrInit();
        if (closed || failure != null) {
            return;
        }
        if (loaded)
            invalidated.run();
        loaded = true;
        var initial = prepared;
        prepared = null;
        try (var reload = initial == null && ownedSource != null ? ownedSource.beginReload() : null; initial) {
            if (initial != null) {
                preparedUploader.accept(this, initial);
            } else {
                try (var pixels = decoder.decode(source)) {
                    uploader.upload(this, pixels);
                }
            }
        } catch (Exception error) {
            failure = error;
            YesSteveModel.LOGGER.debug("Failed to load standalone GUI texture from {}", source, error);
        }
    }

    private static NativeImage decode(ImageSource source) throws Exception {
        try (var image = source.open()) {
            return image.decode();
        }
    }

    private static void upload(CustomTexture texture, NativeImage img) {
        TextureUtil.prepareImage(texture.getId(), 0, img.getWidth(), img.getHeight());
        img.upload(0, 0, 0, 0, 0, img.getWidth(), img.getHeight(), false, false, false, false);
    }

    private static void uploadPrepared(CustomTexture texture, PreviewPixels pixels) {
        texture.setFilter(false, false);
        TextureUtil.prepareImage(texture.getId(), 0, pixels.width(), pixels.height());
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        pixels.borrow(data -> GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, pixels.width(), pixels.height(),
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data));
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        invalidated.run();
        var closing = prepared;
        prepared = null;
        try {
            if (closing != null)
                closing.close();
        } finally {
            try {
                if (ownedSource != null)
                    ownedSource.close();
            } finally {
                super.close();
            }
        }
    }

    void onInvalidated(Runnable listener) {
        invalidated = Objects.requireNonNull(listener);
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @FunctionalInterface
    interface Decoder {
        NativeImage decode(ImageSource source) throws Exception;
    }

    @FunctionalInterface
    interface Uploader {
        void upload(CustomTexture texture, NativeImage image);
    }
}
