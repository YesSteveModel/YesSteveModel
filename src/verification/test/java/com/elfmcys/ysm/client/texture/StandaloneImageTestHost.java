package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.model.storage.PreviewPixels;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.ArrayList;

/** Real registry and texture transfer, with GL replaced by a synchronous consuming host. */
public final class StandaloneImageTestHost {
    public int constructions;
    public int registrations;
    public int uploads;
    public int releases;
    public boolean reject;
    public boolean failUpload;
    public final ArrayList<PreviewPixels> pixels = new ArrayList<>();
    private final HashMap<ResourceLocation, AbstractTexture> textures = new HashMap<>();
    private final CustomTextureManager.Registry registry = new CustomTextureManager.Registry(new CustomTextureManager.Host() {
        public void register(ResourceLocation id, AbstractTexture texture) {
            if (reject)
                throw new java.util.concurrent.RejectedExecutionException("host rejected");
            registrations++;
            try { texture.load(null); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            textures.put(id, texture);
        }
        public void release(ResourceLocation id) {
            releases++;
            textures.remove(id).close();
        }
    });

    public StandaloneImageTestHost() {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
    }

    public CustomTexture create(PreviewStore.EncodedPreview encoded) {
        constructions++;
        return new CustomTexture(encoded, source -> {
            throw new AssertionError("Prepared first upload must not decode");
        }, (texture, image) -> {
            throw new AssertionError("Prepared first upload must use transferred pixels");
        }, (texture, image) -> {
            pixels.add(image);
            image.borrow(bytes -> org.junit.jupiter.api.Assertions.assertEquals(image.width() * image.height() * 4, bytes.remaining()));
            uploads++;
            if (failUpload)
                throw new IllegalStateException("upload failed");
        });
    }

    public TextureHolder register(CustomTexture texture) {
        return registry.register(texture, 1);
    }

    public CustomTextureManager.Registration observe(CustomTexture texture, Runnable invalidated) {
        return registry.observe(texture, invalidated);
    }

    public void release(CustomTexture texture) {
        registry.release(texture);
    }

    public void tick() {
        registry.tick();
    }

    public void reload() {
        textures.values().forEach(texture -> {
            try { texture.load(null); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
    }
}
