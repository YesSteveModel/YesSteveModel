package com.elfmcys.ysm.client.compat;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.texture.CustomPBRTextureSet;
import com.elfmcys.ysm.info.type.PBRTextureType;
import com.elfmcys.ysm.natives.render.VertexFormatType;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.texture.pbr.loader.PBRTextureLoader;
import net.irisshaders.iris.texture.pbr.loader.PBRTextureLoaderRegistry;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.ModList;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import static com.elfmcys.ysm.natives.render.VertexFormatType.IRIS_54;
import static com.elfmcys.ysm.natives.render.VertexFormatType.IRIS_55;
import static com.elfmcys.ysm.natives.render.VertexFormatType.IRIS_56;
import static com.elfmcys.ysm.natives.render.VertexFormatType.IRIS_56_AR;

public class IrisCompat {
    private static final String MOD_ID = "oculus";
    private static boolean INSTALLED = false;
    private static boolean MODERN = false;
    private static LongSupplier ENTITY_ID_GETTER;
    private static VertexFormat ENTITY_FORMAT;

    public static void init() {
        ModList.get().getModContainerById(MOD_ID).ifPresent(mod -> {
            try {
                if (mod.getModInfo().getVersion().compareTo(new DefaultArtifactVersion("1.7.0")) >= 0) {
                    MODERN = true;
                    ENTITY_FORMAT = IrisVertexFormats.ENTITY;
                    ENTITY_ID_GETTER = IrisCompat::getEntityIdModern;
                    PBRLoader.register();
                } else {
                    MODERN = false;
                    ENTITY_FORMAT = net.coderbot.iris.vertices.IrisVertexFormats.ENTITY;
                    ENTITY_ID_GETTER = IrisCompat::getEntityIdLegacy;
                    LegacyPBRLoader.register();
                }
                ENTITY_ID_GETTER.getAsLong();
                isRenderingShadow();
                INSTALLED = true;
            } catch (Throwable e) {
                YesSteveModel.LOGGER.error("Failed to setup oculus compat", e);
                ENTITY_FORMAT = null;
                ENTITY_ID_GETTER = null;
                INSTALLED = false;
                MODERN = false;
            }
        });
    }

    public static Optional<VertexFormatType> determineVertexFormatType(VertexFormat vertexFormat) {
        if (INSTALLED && ENTITY_FORMAT == vertexFormat) {
            if (vertexFormat.getVertexSize() == 56) {
                return Optional.of(ARCompat.isInstalled() ? IRIS_56_AR : IRIS_56);
            }
            if (vertexFormat.getVertexSize() == 55) { // 逆天设计
                return Optional.of(IRIS_55);
            }
            if (vertexFormat.getVertexSize() == 54) {
                return Optional.of(IRIS_54);
            }
            // 低于 1.20 的版本还有 48 字节的格式，暂不做支持
        }
        return Optional.empty();
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static boolean isRenderingShadow() {
        return INSTALLED && IrisApi.getInstance().isRenderingShadowPass();
    }

    public static void requirePbrTextures(CustomPBRTextureSet texture, Consumer<AbstractTexture> hostOwnership) {
        if (!INSTALLED) {
            throw new IllegalStateException("Oculus PBR integration is not installed");
        }
        if (MODERN) {
            var holder = net.irisshaders.iris.texture.pbr.PBRTextureManager.INSTANCE.getOrLoadHolder(texture.getId());
            requireExpected(texture, holder.normalTexture(), holder.specularTexture(), hostOwnership);
        } else {
            var holder = net.coderbot.iris.texture.pbr.PBRTextureManager.INSTANCE.getOrLoadHolder(texture.getId());
            requireExpected(texture, holder.getNormalTexture(), holder.getSpecularTexture(), hostOwnership);
        }
    }

    private static void requireExpected(CustomPBRTextureSet texture, AbstractTexture actualNormal,
        AbstractTexture actualSpecular, Consumer<AbstractTexture> hostOwnership) {
        IllegalStateException failure = null;
        if (texture.getNormal() != null) {
            if (texture.getNormal() == actualNormal) {
                hostOwnership.accept(texture.getNormal());
            } else {
                failure = new IllegalStateException("Oculus did not adopt the expected normal texture");
            }
        }
        if (texture.getSpecular() != null) {
            if (texture.getSpecular() == actualSpecular) {
                hostOwnership.accept(texture.getSpecular());
            } else {
                var specularFailure = new IllegalStateException("Oculus did not adopt the expected specular texture");
                if (failure == null) {
                    failure = specularFailure;
                } else {
                    failure.addSuppressed(specularFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static long getEntityIdLegacy() {
        short s0 = (short)net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
        short s1 = (short)net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
        short s2 = (short)net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
        return s0 | ((long)s1 << 16) | ((long)s2 << 32); // little endian
    }

    public static long getEntityId() {
        return INSTALLED ? ENTITY_ID_GETTER.getAsLong() : 0;
    }

    private static long getEntityIdModern() {
        short s0 = (short)CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
        short s1 = (short)CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity();
        short s2 = (short)CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
        return s0 | ((long)s1 << 16) | ((long)s2 << 32); // little endian
    }

    private static class LegacyPBRLoader
        implements net.coderbot.iris.texture.pbr.loader.PBRTextureLoader<CustomPBRTextureSet> {
        private static final LegacyPBRLoader INSTANCE = new LegacyPBRLoader();

        private LegacyPBRLoader() {}

        @Override
        public void load(CustomPBRTextureSet texture, ResourceManager resourceManager,
            PBRTextureConsumer pbrTextureConsumer) {
            loadPbr(texture, resourceManager);
            var normalTexture = texture.getPBRTextures().get(PBRTextureType.NORMAL);
            if (normalTexture != null) {
                pbrTextureConsumer.acceptNormalTexture(normalTexture);
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.TEXTURE, normalTexture,
                    "pbr normal parent=" + texture.getId());
                ResourceDiagnostics.log("texture PBR register: normal parent={}", texture.getId());
            }
            var specularTexture = texture.getPBRTextures().get(PBRTextureType.SPECULAR);
            if (specularTexture != null) {
                pbrTextureConsumer.acceptSpecularTexture(specularTexture);
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.TEXTURE, specularTexture,
                    "pbr specular parent=" + texture.getId());
                ResourceDiagnostics.log("texture PBR register: specular parent={}", texture.getId());
            }
        }

        public static void register() {
            net.coderbot.iris.texture.pbr.loader.PBRTextureLoaderRegistry.INSTANCE.register(CustomPBRTextureSet.class,
                INSTANCE);
        }
    }

    private static class PBRLoader implements PBRTextureLoader<CustomPBRTextureSet> {
        private static final PBRLoader INSTANCE = new PBRLoader();

        private PBRLoader() {}

        @Override
        public void load(CustomPBRTextureSet texture, ResourceManager resourceManager,
            PBRTextureLoader.PBRTextureConsumer pbrTextureConsumer) {
            loadPbr(texture, resourceManager);
            var normalTexture = texture.getPBRTextures().get(PBRTextureType.NORMAL);
            if (normalTexture != null) {
                pbrTextureConsumer.acceptNormalTexture(normalTexture);
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.TEXTURE, normalTexture,
                    "pbr normal parent=" + texture.getId());
                ResourceDiagnostics.log("texture PBR register: normal parent={}", texture.getId());
            }
            var specularTexture = texture.getPBRTextures().get(PBRTextureType.SPECULAR);
            if (specularTexture != null) {
                pbrTextureConsumer.acceptSpecularTexture(specularTexture);
                ResourceDiagnostics.track(ResourceDiagnostics.Kind.TEXTURE, specularTexture,
                    "pbr specular parent=" + texture.getId());
                ResourceDiagnostics.log("texture PBR register: specular parent={}", texture.getId());
            }
        }

        public static void register() {
            PBRTextureLoaderRegistry.INSTANCE.register(CustomPBRTextureSet.class, INSTANCE);
        }
    }

    private static void loadPbr(CustomPBRTextureSet texture, ResourceManager resourceManager) {
        try {
            texture.loadPbr(resourceManager);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load model PBR textures", error);
        }
    }
}
