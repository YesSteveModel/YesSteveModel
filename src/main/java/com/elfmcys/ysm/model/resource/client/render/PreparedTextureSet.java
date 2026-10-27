package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.client.texture.CustomPBRTextureSet;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One candidate's decoded model texture set before host publication. */
final class PreparedTextureSet implements BakedModelCache.TexturePixelsSupplier, AutoCloseable {
    private final PBRImageSources sources;
    private final ModelResourceFailureGate uvFailure;
    private final ModelResourceFailureGate normalFailure;
    private final ModelResourceFailureGate specularFailure;
    private final Decoder decoder;
    private NativeImage uv;
    private NativeImage normal;
    private NativeImage specular;
    private boolean transferred;

    PreparedTextureSet(PBRImageSources sources, String resourcePrefix, ModelResourceFailures failures) {
        this(sources, resourcePrefix, failures, PreparedTextureSet::decodeSource);
    }

    PreparedTextureSet(PBRImageSources sources, String resourcePrefix, ModelResourceFailures failures,
        Decoder decoder) {
        this.sources = Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(resourcePrefix, "resourcePrefix");
        Objects.requireNonNull(failures, "failures");
        uvFailure = failures.texture(resourcePrefix + "uv");
        normalFailure = failures.texture(resourcePrefix + "normal");
        specularFailure = failures.texture(resourcePrefix + "specular");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public NativeImage get() throws IOException {
        requireOwned();
        if (uv == null) {
            uv = decode(sources.uv(), uvFailure, "uv");
        }
        return uv;
    }

    void prepare(BooleanSupplier cancelled) throws IOException {
        requireActive(cancelled);
        get();
        requireActive(cancelled);
        if (sources.normal() != null) {
            normal = decode(sources.normal(), normalFailure, "normal");
            requireActive(cancelled);
        }
        if (sources.specular() != null) {
            specular = decode(sources.specular(), specularFailure, "specular");
            requireActive(cancelled);
        }
    }

    CustomPBRTextureSet transfer() {
        requireOwned();
        if (uv == null || (sources.normal() != null && normal == null)
            || (sources.specular() != null && specular == null)) {
            throw new IllegalStateException("Model texture set is not completely prepared");
        }
        var result = new CustomPBRTextureSet(sources, uv, normal, specular, uvFailure, normalFailure, specularFailure);
        uv = null;
        normal = null;
        specular = null;
        transferred = true;
        return result;
    }

    private NativeImage decode(ImageSource source, ModelResourceFailureGate failureGate, String component)
        throws IOException {
        var previous = failureGate.failure().orElse(null);
        if (previous != null) {
            throw AssetLoadException.content("Model texture component previously failed: " + component, previous);
        }
        try {
            return decoder.decode(source);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException error) {
            failureGate.fail(error);
            if (error instanceof IOException io) {
                throw AssetLoadException.content("Failed to decode model texture component: " + component, io);
            }
            throw error;
        }
    }

    private static NativeImage decodeSource(ImageSource source) throws IOException {
        try (var image = source.open()) {
            return image.decode();
        }
    }

    private void requireOwned() {
        if (transferred) {
            throw new IllegalStateException("Prepared model texture set was already transferred");
        }
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model texture preparation was cancelled");
        }
    }

    @Override
    public void close() {
        close(uv);
        close(normal);
        close(specular);
        uv = null;
        normal = null;
        specular = null;
    }

    private static void close(NativeImage image) {
        if (image != null) {
            image.close();
        }
    }

    @FunctionalInterface
    interface Decoder {
        NativeImage decode(ImageSource source) throws IOException;
    }
}
