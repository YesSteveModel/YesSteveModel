package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.client.texture.CustomPBRTextureSet;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@FunctionalInterface
interface HostTexturePublisher {
    PublishedTextureBinding publish(PreparedTextureSet prepared) throws Exception;

    static HostTexturePublisher production() {
        return MinecraftHostTexturePublisher.INSTANCE;
    }

    final class PublishedTextureBinding implements AutoCloseable {
        private final ResourceLocation base;
        private final TextureManager textureManager;
        private final List<ResourceLocation> mappings;
        private final AtomicBoolean released = new AtomicBoolean();

        PublishedTextureBinding(ResourceLocation base, TextureManager textureManager, List<ResourceLocation> mappings) {
            this.base = Objects.requireNonNull(base, "base");
            this.textureManager = Objects.requireNonNull(textureManager, "textureManager");
            this.mappings = List.copyOf(mappings);
        }

        ResourceLocation base() {
            return base;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            for (var index = mappings.size() - 1; index >= 0; index--) {
                try {
                    textureManager.release(mappings.get(index));
                } catch (RuntimeException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}

final class MinecraftHostTexturePublisher implements HostTexturePublisher {
    static final MinecraftHostTexturePublisher INSTANCE = new MinecraftHostTexturePublisher();
    private long nextId;

    private MinecraftHostTexturePublisher() {}

    @Override
    public PublishedTextureBinding publish(PreparedTextureSet prepared) {
        RenderSystem.assertOnRenderThread();
        var manager = Minecraft.getInstance().getTextureManager();
        var texture = prepared.transfer();
        var mappings = new ArrayList<ResourceLocation>(3);
        var hostOwned = new ArrayList<AbstractTexture>(3);
        var base = nextId("base");
        var success = false;
        Throwable publicationFailure = null;
        try {
            register(manager, base, texture);
            mappings.add(base);
            hostOwned.add(texture);
            if (IrisCompat.isInstalled()) {
                IrisCompat.requirePbrTextures(texture, hostOwned::add);
            } else {
                registerComponent(manager, mappings, hostOwned, texture.getNormal(), "normal");
                registerComponent(manager, mappings, hostOwned, texture.getSpecular(), "specular");
            }
            success = true;
            return new PublishedTextureBinding(base, manager, mappings);
        } catch (RuntimeException | Error failure) {
            publicationFailure = failure;
            throw failure;
        } finally {
            if (!success) {
                RuntimeException cleanupFailure = null;
                for (var index = mappings.size() - 1; index >= 0; index--) {
                    try {
                        manager.release(mappings.get(index));
                    } catch (RuntimeException error) {
                        cleanupFailure = append(cleanupFailure, error);
                    }
                }
                try {
                    texture.closeUnregistered(hostOwned);
                } catch (RuntimeException error) {
                    cleanupFailure = append(cleanupFailure, error);
                }
                if (cleanupFailure != null) {
                    if (publicationFailure != null) {
                        publicationFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private void registerComponent(TextureManager manager, List<ResourceLocation> mappings,
        List<AbstractTexture> hostOwned, AbstractTexture texture, String component) {
        if (texture == null) {
            return;
        }
        var id = nextId(component);
        register(manager, id, texture);
        mappings.add(id);
        hostOwned.add(texture);
    }

    private static void register(TextureManager manager, ResourceLocation id, AbstractTexture texture) {
        manager.register(id, texture);
        try {
            if (manager.getTexture(id) == texture) {
                return;
            }
        } catch (RuntimeException error) {
            try {
                manager.release(id);
            } catch (RuntimeException cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
        var failure = new IllegalStateException("Host substituted model texture mapping: " + id);
        try {
            manager.release(id);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        throw failure;
    }

    private static RuntimeException append(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    @SuppressWarnings("removal")
    private ResourceLocation nextId(String component) {
        return new ResourceLocation(YesSteveModel.MOD_ID, "model-textures/" + ++nextId + "/" + component);
    }
}
