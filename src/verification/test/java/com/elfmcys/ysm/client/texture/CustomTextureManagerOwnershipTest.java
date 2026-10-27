package com.elfmcys.ysm.client.texture;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class CustomTextureManagerOwnershipTest {
    @Test
    void previewAvatarAndPackIconFirstLoadsCompleteBeforeRegistrationReturns() {
        var host = new TracingHost();
        var registry = new CustomTextureManager.Registry(host);
        var preview = new TracingTexture();
        var avatar = new TracingTexture();
        var packIcon = new TracingTexture();

        var previewHolder = registry.register(preview, 0);
        var avatarHolder = registry.register(avatar, 0);
        var packHolder = registry.register(packIcon, 0);

        assertEquals(1, preview.loads.get());
        assertEquals(1, avatar.loads.get());
        assertEquals(1, packIcon.loads.get());
        assertNotEquals(previewHolder.id(), avatarHolder.id());
        assertNotEquals(avatarHolder.id(), packHolder.id());
    }

    @Test
    void pagingReuseKeepsOneHolderAndOneHostRegistration() {
        var host = new TracingHost();
        var registry = new CustomTextureManager.Registry(host);
        var texture = new TracingTexture();

        var first = registry.register(texture, 0);
        var reused = registry.register(texture, 0);

        assertSame(first, reused);
        assertEquals(1, host.registrations.get());
        assertEquals(1, texture.loads.get());
    }

    @Test
    void replacementBeforeDelayedReleaseKeepsHostOwnershipAndIdentity() {
        var host = new TracingHost();
        var registry = new CustomTextureManager.Registry(host);
        var texture = new TracingTexture();
        var old = registry.register(texture, 0);

        registry.release(texture);
        var replacement = registry.register(texture, 0);
        registry.tick();

        assertNotSame(old, replacement);
        assertEquals(old.id(), replacement.id());
        assertEquals(1, host.registrations.get());
        assertEquals(0, host.releases.get());

        registry.release(texture);
        registry.tick();
        assertEquals(1, host.releases.get());
        assertEquals(1, texture.closes.get());
    }

    @Test
    void configuredDelayDefersPhysicalHostRelease() {
        var host = new TracingHost();
        var registry = new CustomTextureManager.Registry(host);
        var texture = new TracingTexture();
        registry.register(texture, 1);

        registry.release(texture);
        registry.tick();
        assertEquals(0, host.releases.get());

        registry.tick();
        assertEquals(1, host.releases.get());
        assertEquals(1, texture.closes.get());
    }

    private static final class TracingHost implements CustomTextureManager.Host {
        private final HashMap<ResourceLocation, AbstractTexture> textures = new HashMap<>();
        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public void register(ResourceLocation id, AbstractTexture texture) {
            registrations.incrementAndGet();
            try {
                texture.load(null);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            textures.put(id, texture);
        }

        @Override
        public void release(ResourceLocation id) {
            releases.incrementAndGet();
            var texture = textures.remove(id);
            if (texture != null) {
                texture.close();
            }
        }
    }

    private static final class TracingTexture extends AbstractTexture {
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void load(ResourceManager resourceManager) {
            loads.incrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }
}
