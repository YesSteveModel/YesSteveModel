package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.compatibility.AbiVersion;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.baked.model.BakedModelConstant;
import com.elfmcys.ysm.format.schema.baked.model.BakedModelView;
import com.elfmcys.ysm.format.schema.baked.model.BakedModelWriter;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

public final class BakedModelCache {
    static final String CACHE_SUFFIX = ".geo.ysm-cache";
    static final String CACHE_ABI = "geometry-" + AbiVersion.CACHE_GEOMETRY;
    private static final byte[] HASH_DOMAIN = ("ysm." + CACHE_ABI + "\0").getBytes(StandardCharsets.UTF_8);

    private final Path bakedRoot;
    private final AtomicSharedCache cache;
    private final String capabilityKey;

    public BakedModelCache(Path bakedRoot, AtomicSharedCache cache) {
        this.bakedRoot = bakedRoot;
        this.cache = cache;
        this.capabilityKey = sanitize(System.getProperty("os.arch", "unknown")) + "-"
            + sanitize(System.getProperty("os.name", "unknown")) + "-simd-"
            + Integer.toUnsignedString(NativeBakedModel.capability()) + "-bake-" + BakedModelConstant.CURRENT_VERSION;
    }

    String profileKey() {
        return CACHE_ABI + "/" + capabilityKey;
    }

    public GeoModel loadOrBake(Hash256 containerId, String resourceName, byte[] textureHash, GeoModelProto source,
        TexturePixelsSupplier texture, int originVersion, boolean forceCulling, boolean forceTranslucent,
        boolean hasPbr, GeoLocatorType locatorType) throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        var target = path(containerId, bakeHash);
        try {
            return read(target, bakeHash, locatorType);
        } catch (IOException unavailable) {
            // Only the initial optional-cache acquisition can fall back to production.
        }
        var candidate = bake(source, texture, originVersion, forceCulling, forceTranslucent, hasPbr);
        var delivered = false;
        try {
            var model = new GeoModel(bakeHash.toString(), source, locatorType, candidate);
            delivered = true;
            var backing = candidate.bakedModel();
            var workCache = cache;
            workCache.derivedWork()
                .submit(gate -> workCache.replaceValidated("baked", containerId + "/" + bakeHash, target,
                    file -> validate(file, bakeHash), destination -> write(destination, bakeHash, source, backing),
                    gate));
            return model;
        } finally {
            if (!delivered)
                candidate.bakedModel().close();
        }
    }

    public GeoModel loadExisting(Hash256 containerId, String resourceName, byte[] textureHash,
        GeoLocatorType locatorType) throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        var target = path(containerId, bakeHash);
        return read(target, bakeHash, locatorType);
    }

    Path path(Hash256 containerId, Hash256 bakeHash) {
        return bakedRoot.resolve(CACHE_ABI).resolve(capabilityKey).resolve(containerId.toString())
            .resolve(bakeHash + CACHE_SUFFIX);
    }

    public static GeoModel bakeResident(GeoModelProto source, String resourceName, TexturePixelsSupplier textureSource,
        int originVersion, boolean forceCulling, boolean forceTranslucent, boolean hasPbr, GeoLocatorType locatorType)
        throws IOException {
        var baked = bake(source, textureSource, originVersion, forceCulling, forceTranslucent, hasPbr);
        var delivered = false;
        try {
            var model = new GeoModel("default:" + resourceName, source, locatorType, baked);
            delivered = true;
            return model;
        } finally {
            if (!delivered) {
                baked.bakedModel().close();
            }
        }
    }

    private static NativeBakedModel.ModelResult bake(GeoModelProto source, TexturePixelsSupplier textureSource,
        int originVersion, boolean forceCulling, boolean forceTranslucent, boolean hasPbr) throws IOException {
        var texture = textureSource.get();
        try {
            return NativeBakedModel.bake(source, texture, originVersion, forceCulling, forceTranslucent, hasPbr);
        } catch (RuntimeException error) {
            throw AssetLoadException.content("Failed to bake model", error);
        }
    }

    private static void write(com.elfmcys.ysm.model.storage.CacheFile destination, Hash256 bakeHash,
        GeoModelProto source, NativeBakedModel baked) throws IOException {
        final NativeStorage serialized;
        try {
            serialized = baked.serialize();
        } catch (RuntimeException error) {
            throw AssetLoadException.content("Failed to serialize model", error);
        }
        try (var result = serialized;
            var operation = ResourceDiagnostics.operation("geometry cache write (staging)", bakeHash);
            var writer = new BakedModelWriter(); var channel = destination) {
            writer.setData(bakeHash.bytes(), source, result);
            writer.write(channel);
            channel.force();
            operation.succeeded();
        }
    }

    private GeoModel read(Path file, Hash256 expected, GeoLocatorType locatorType) throws IOException {
        NativeBakedModel.ModelResult candidate = null;
        GeoModel model = null;
        try (var operation = ResourceDiagnostics.operation("geometry cache read", file)) {
            final FileChannel opened;
            try {
                opened = cache.openRead(file);
            } catch (IOException error) {
                throw AssetLoadException.access("Failed to open baked model cache", error);
            }
            try (var channel = opened) {
                var view = new BakedModelView(channel);
                if (!view.bakeHash().equals(expected)) {
                    throw AssetLoadException.content("Baked model cache key mismatch");
                }
                var data = view.readModelData(channel);
                if (data == null) {
                    throw AssetLoadException.content("Baked model cache has no native payload");
                }
                try (data) {
                    try {
                        candidate = NativeBakedModel.read(data, view.model().bones().size());
                    } catch (RuntimeException error) {
                        throw AssetLoadException.content("Failed to decode baked model cache", error);
                    }
                    model = new GeoModel(expected.toString(), view.model(), locatorType, candidate);
                    operation.succeeded();
                }
            } catch (AssetLoadException error) {
                throw error;
            } catch (IOException | RuntimeException error) {
                throw AssetLoadException.content("Invalid baked model cache", error);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (model != null) {
                model.close();
            } else if (candidate != null) {
                candidate.bakedModel().close();
            }
            throw failure;
        }
        return model;
    }

    private static boolean validate(java.nio.channels.SeekableByteChannel channel, Hash256 expected)
        throws IOException {
        try {
            var view = new BakedModelView(channel);
            if (!view.bakeHash().equals(expected)) {
                return false;
            }
            try (var data = view.readModelData(channel)) {
                return data != null;
            }
        } catch (AssetLoadException error) {
            if (error.reason() == AssetLoadException.Reason.ACCESS) {
                throw error;
            }
            return false;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static Hash256 bakeHash(Hash256 containerId, String resourceName, byte[] textureHash) {
        var name = resourceName.getBytes(StandardCharsets.UTF_8);
        var input = ByteBuffer
            .allocate(HASH_DOMAIN.length + Hash256.SIZE + Integer.BYTES * 2 + name.length + textureHash.length);
        input.put(HASH_DOMAIN).put(containerId.bytes()).putInt(name.length).put(name).putInt(textureHash.length)
            .put(textureHash);
        return ModelHashing.blake3(input.array());
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    @FunctionalInterface
    public interface TexturePixelsSupplier {
        /** Returns borrowed pixels; the caller that created the supplier retains ownership. */
        NativeImage get() throws IOException;
    }
}
