package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.baked.model.BakedModelWriter;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@EnabledIfSystemProperty(named = "ysm.geometryReadProbe", matches = "true")
class GeometryCacheReadTest {
    @Test
    void validatedPayloadIsConsumedOnceAndOptionalPersistencePreservesLiveGeometry(@TempDir Path root)
        throws Exception {
        var source = Path
            .of(Objects.requireNonNull(GeometryCacheReadTest.class.getResource("/assets/ysm/builtin/default")).toURI());
        try (var directory = new Directory(source); var parsed = ModelParser.parseBuiltinDefaultMemory(directory);
            var bytes = parsed.bytes()) {
            var content = ManagedContainer.openResidentDefault(bytes,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            try (var representation = content.representation()) {
                var definition =
                    content.modelFile().requireRenderTarget("player").readDefinition(() -> false, content.chunks());
                var geometry = ModelRenderTargetLoader.geoModel(definition, "main");
                var shared = new AtomicSharedCache(root.resolve("geometry"));
                var cache = new BakedModelCache(root.resolve("geometry"), shared);
                var textureHash = new byte[32];
                var id = representation.containerId();
                var expected = BakedModelCache.bakeHash(id, "main", textureHash);
                var target = cache.path(id, expected);
                Files.createDirectories(target.getParent());
                var locators = new Locators();
                try (var input = ArrayBuffer.move(ProtoUtil.serializeToArray(geometry));
                    var pixels = NativeBuffer.allocate(64 * 64 * 4)) {
                    for (int offset = 0; offset < pixels.size(); offset += 8)
                        pixels.nio().putLong(offset, -1L);
                    var baked = NativeBakedModel.bake(input, geometry.bones().size(), pixels, 64, 64, 29, false);
                    try (var nativeModel = baked.bakedModel(); var serialized = nativeModel.serialize()) {
                        try (var writer = new BakedModelWriter(); var channel =
                            Files.newByteChannel(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                            writer.setData(expected.bytes(), geometry, serialized);
                            writer.write(channel);
                        }
                    }
                }
                var valid = Files.readAllBytes(target);
                CheckedAction ordinary = () -> {
                    try (var model = cache.loadOrBake(id, "main", textureHash, geometry, () -> {
                        throw new AssertionError("A warm hit must not produce geometry");
                    }, 29, false, false, false, locators)) {
                        assertTrue(model.bakedModel().storageBytes().backing() > 0, "Missing geometry");
                    }
                };
                CheckedAction cacheOnly = () -> {
                    try (var model = cache.loadExisting(id, "main", textureHash, locators)) {
                        assertTrue(model.bakedModel().storageBytes().backing() > 0, "Missing cache-only geometry");
                    }
                };
                var normal = observe(null, null, ordinary);
                var offline = observe(null, null, cacheOnly);
                for (var sample : List.of(normal, offline))
                    assertAll("A hit must parse, acquire, hash and decode exactly once",
                        () -> assertEquals(1, sample.metadata), () -> assertEquals(1, sample.payload),
                        () -> assertEquals(1, sample.hash), () -> assertEquals(1, sample.decode));

                var differentTexture = textureHash.clone();
                differentTexture[0] = 1;
                var different = cache.path(id, BakedModelCache.bakeHash(id, "main", differentTexture));
                Files.write(different, valid);
                observe(null, null,
                    () -> expectFailure(() -> cache.loadExisting(id, "main", differentTexture, locators)));

                var corrupt = valid.clone();
                corrupt[corrupt.length - 1] ^= 1;
                Files.write(target, corrupt);
                var damaged = observe(null, null, () -> expectFailure(cacheOnly));
                assertTrue(damaged.hash == 1 && damaged.decode == 0, "Damaged payload reached the native decoder");

                Files.write(target, valid);
                observe(() -> io(() -> Files.write(target, new byte[0])), null, () -> expectFailure(cacheOnly));

                Files.write(target, valid);
                var replacement = target.resolveSibling("replacement");
                Files.write(replacement, new byte[] {0});
                var replaced = observe(null,
                    () -> io(() -> Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING)), cacheOnly);
                assertTrue(replaced.decode == 1 && Files.size(target) == 1,
                    "Path replacement changed an already acquired payload");

                try (var writer = new BakedModelWriter(); var invalid = NativeBuffer.allocate(16); var channel =
                    Files.newByteChannel(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    invalid.nio().putLong(0, 0).putLong(8, 0);
                    writer.setData(expected.bytes(), geometry, invalid);
                    writer.write(channel);
                }
                var nativeFailure = observe(null, null, () -> expectFailure(cacheOnly));
                assertTrue(nativeFailure.hash == 1 && nativeFailure.decode == 1,
                    "Valid container did not exercise native rejection");

                var producerFailure = new IOException("Required producer failed");
                try {
                    cache.loadOrBake(id, "main", textureHash, geometry, () -> {
                        throw producerFailure;
                    }, 29, false, false, false, locators);
                    throw new AssertionError("Required failure was hidden");
                } catch (IOException failure) {
                    assertSame(producerFailure, failure, "Optional I/O handling swallowed the producer failure");
                }
                Files.write(target, valid);
                observe(null, null, () -> {
                    Thread.currentThread().interrupt();
                    try {
                        expectFailure(cacheOnly);
                    } finally {
                        Thread.interrupted();
                    }
                });
                assertEquals(0, shared.derivedWork().snapshot().jobs(), "Cache-only created storage work");
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var coldHash = BakedModelCache.bakeHash(id, "cold", textureHash);
                var coldPath = cache.path(id, coldHash);
                var holder =
                    CompletableFuture.runAsync(() -> io(() -> shared.withKeyLock("baked", id + "/" + coldHash, () -> {
                        held.countDown();
                        try {
                            if (!release.await(20, TimeUnit.SECONDS))
                                throw new IOException("Derived storage barrier timed out");
                        } catch (InterruptedException failure) {
                            throw new IOException(failure);
                        }
                        return null;
                    })));
                assertTrue(held.await(5, TimeUnit.SECONDS), "The real same-key lock was not acquired");
                try (var pixels = new com.mojang.blaze3d.platform.NativeImage(64, 64, false)) {
                    pixels.fillRect(0, 0, 64, 64, -1);
                    try (var cold = cache.loadOrBake(id, "cold", textureHash, geometry, () -> pixels, 29, false, false,
                        false, locators)) {
                        assertFalse(Files.exists(coldPath), "Blocked storage committed before release");
                        assertEquals(1, shared.derivedWork().snapshot().jobs(), "Cold geometry was not admitted");
                        var retained = cold.bakedModel();
                        cold.close();
                        try (var usable = retained.serialize()) {
                            assertTrue(usable.size() > 0, "Retiring a consumer destroyed a live storage borrow");
                        }
                    }
                } finally {
                    release.countDown();
                    holder.get(5, TimeUnit.SECONDS);
                    shared.derivedWork().awaitIdle();
                }
                try (var reopened = cache.loadExisting(id, "cold", textureHash, locators)) {
                    assertTrue(reopened.bakedModel().storageBytes().backing() > 0, "Cold write was incomplete");
                }
                shared.derivedWork().close();
            }
        }
    }

    private static GeometryReadProbe.Observation observe(Runnable beforePayload, Runnable beforeDecode,
        CheckedAction action) throws Exception {
        var observation = GeometryReadProbe.start(beforePayload, beforeDecode);
        try {
            action.run();
            return observation;
        } finally {
            GeometryReadProbe.stop();
        }
    }

    private static void expectFailure(CheckedAction action) {
        assertThrows(IOException.class, action::run);
    }

    private static void io(IoAction action) {
        try {
            action.run();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private static final class Locators extends GeoLocatorType {
        @SuppressWarnings("removal")
        Locators() {
            super(new ResourceLocation("ysm", "geometry-cache-read"));
            freeze();
        }
    }
}
