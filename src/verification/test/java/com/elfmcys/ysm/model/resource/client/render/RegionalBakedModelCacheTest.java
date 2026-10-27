package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.schema.baked.model.BakedModelWriter;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.CacheRegion;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegionalBakedModelCacheTest {
    @Test
    void eachRegionCommitsAndReopensItsNativeGeometry(@TempDir Path root) throws Exception {
        var source = Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default")).toURI());
        try (var directory = new Directory(source); var parsed = ModelParser.parseBuiltinDefaultMemory(directory);
            var bytes = parsed.bytes()) {
            var content = ManagedContainer.openResidentDefault(bytes,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")));
            try (var representation = content.representation()) {
                var definition =
                    content.modelFile().requireRenderTarget("player").readDefinition(() -> false, content.chunks());
                var geometry = ModelRenderTargetLoader.geoModel(definition, "main");
                var locators = new FixtureLocators();
                try (var input = ArrayBuffer.move(ProtoUtil.serializeToArray(geometry));
                    var pixels = NativeBuffer.allocate(64 * 64 * 4)) {
                    for (var offset = 0; offset < pixels.size(); offset += 8)
                        pixels.nio().putLong(offset, -1L);
                    var baked = NativeBakedModel.bake(input, geometry.bones().size(), pixels, 64, 64, 29, false);
                    try (var nativeModel = baked.bakedModel(); var serialized = nativeModel.serialize()) {
                        for (var region : List.of(new CacheRegion(root.resolve("local")),
                            new CacheRegion(root.resolve("global")))) {
                            var cache = new BakedModelCache(region.root().resolve("bake"), region.cache());
                            var textureHash = new byte[32];
                            var bakeHash = BakedModelCache.bakeHash(representation.containerId(), "main", textureHash);
                            var target = cache.path(representation.containerId(), bakeHash);
                            region.cache().materialize("baked", representation.containerId() + "/" + bakeHash, target,
                                path -> {
                                    try (var channel = path) {
                                        return new com.elfmcys.ysm.format.schema.baked.model.BakedModelView(channel)
                                            .bakeHash().equals(bakeHash);
                                    }
                                }, destination -> {
                                    try (var writer = new BakedModelWriter(); var channel = destination) {
                                        writer.setData(bakeHash.bytes(), geometry, serialized);
                                        writer.write(channel);
                                    }
                                });
                            try (var model =
                                cache.loadOrBake(representation.containerId(), "main", textureHash, geometry, () -> {
                                    throw new AssertionError("Verified cache must avoid the Minecraft pixel path");
                                }, 29, false, false, false, locators)) {
                                assertNotNull(model);
                                var retained = model.bakedModel();
                                assertThrows(IllegalStateException.class, retained::close);
                                model.close();
                                try (var output = retained.serialize()) {
                                    assertTrue(output.size() > 0);
                                    assertTrue(retained.storageBytes().backing() > 0);
                                    assertEquals(output.size(), retained.storageBytes().serialization());
                                }
                            }
                            try (var reopened =
                                cache.loadExisting(representation.containerId(), "main", textureHash, locators)) {
                                assertNotNull(reopened);
                            }
                            try (var files = Files.walk(region.root())) {
                                assertEquals(1,
                                    files.filter(path -> path.toString().endsWith(".geo.ysm-cache")).count());
                            }
                        }
                    }
                }
            }
        }
    }

    private static final class FixtureLocators extends GeoLocatorType {
        @SuppressWarnings("removal")
        FixtureLocators() {
            super(new ResourceLocation("ysm", "regional-cache-fixture"));
            freeze();
        }
    }

}
