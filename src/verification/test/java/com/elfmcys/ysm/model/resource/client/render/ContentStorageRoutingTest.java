package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactIdentity;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.baked.model.BakedModelWriter;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKey;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.session.client.RemoteCatalogActivation;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.storage.CacheRegion;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.model.storage.StorageIndex;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.natives.storage.NativeArtifactIndex;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContentStorageRoutingTest {
    private static final Locators LOCATORS = new Locators();
    @TempDir
    Path temp;

    @Test
    void independentSourcesAndProductionArtifactRoutesKeepTheirBusinessBoundary() throws Exception {
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", temp.resolve("global").toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        var global = paths.global().orElseThrow();
        var fixtures = List.of(fixture("misc/1_alex", "Li"), fixture("misc/2_steve", "Lg"), fixture("default", "B"),
            fixture("misc/3_default_boy", "R"));
        assertEquals(4, fixtures.stream().map(Fixture::identity).distinct().count());
        var roots = List.of(new CatalogRootIdentity(CatalogRootKind.MODELS, paths.modelsRoot()),
            new CatalogRootIdentity(CatalogRoot.GLOBAL, global.modelsRoot()),
            new CatalogRootIdentity(CatalogRootKind.BUILTIN, paths.builtinModelsResource()));
        var scheduler = new ClientResourceScheduler(2);
        try (var storage = ModelStorageInfra.open(paths);
            var loader = new ModelRenderTargetLoader(storage,
                new RenderTargetExpressionModuleFactory(storage.local().expressions(), storage.local().animations(),
                    ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics()),
                scheduler, new DefaultAnimationRuntime())) {
            storage.registerCaches();
            var destinations = List.of(new Destination(storage.local(), storage.local().cache().catalogIndex(), "Li"),
                new Destination(storage.remote(), storage.remote().cache().catalogIndex(), "Lg"),
                new Destination(storage.remote(), storage.remote().cache().remoteIndex(), "Lg"));
            var stageField = ModelRenderTargetLoader.class.getDeclaredField("buildStage");
            stageField.setAccessible(true);
            var stage = stageField.get(loader);
            var models = stage.getClass().getDeclaredMethod("models", ModelContent.class);
            var modules = stage.getClass().getDeclaredMethod("modules", ModelContent.class);
            models.setAccessible(true);
            modules.setAccessible(true);
            var geometryContainer = hash(81);
            var textureHash = new byte[Hash256.SIZE];
            var geometryHash = BakedModelCache.bakeHash(geometryContainer, "main", textureHash);
            var profile = new BakedModelCache(storage.local().root().resolve("bake"), storage.local().cache());
            var geometryKey = profile.key(geometryContainer, geometryHash);
            for (var destination : destinations.subList(0, 2))
                seedGeometry(destination, geometryHash, geometryKey);
            var contents = new ArrayList<ManagedContainer>();
            var entries = new ArrayList<CatalogIndexEntry>();
            var previews = new LinkedHashMap<Destination, byte[]>();
            var expressions = new LinkedHashMap<Destination, Path>();
            try {
                for (var i = 0; i < roots.size(); i++) {
                    var root = roots.get(i);
                    var fixture = fixtures.get(i);
                    var source = root.configuredAbsoluteRoot().resolve("same.mxc");
                    Files.createDirectories(source.getParent());
                    Files.copy(fixture.file(), source);
                    var key = new ModelSourceKey(root, new ModelPath("same.mxc"), ModelSourceKind.DIRECT_CONTAINER);
                    var binding = storage.binding(ModelSourceDiscovery.observe(key, source));
                    var expected = destinations.get(i == 1 ? 1 : 0);
                    assertSame(expected.region(), binding.region());
                    assertSame(expected.region().cache().bakeIndex(), binding.artifacts());
                    assertEquals(i == 2 ? ContentStorageBinding.Kind.BUILTIN : ContentStorageBinding.Kind.LOCAL,
                        binding.kind());
                    var scope = storage.scope(root);
                    var location = root.location(new ModelPath("same.mxc"));
                    final Path object;
                    try (var temporary = scope.objects().stage()) {
                        temporary.file().writeBytes(fixture.bytes());
                        var staged = temporary.path();
                        object = scope.objects()
                            .commit(new RawCompileResult(fixture.identity().modelId(), staged), location, temporary)
                            .file();
                    }
                    assertTrue(object.startsWith(i == 1 ? global.cacheRoot() : paths.gameCacheRoot()));
                    assertArrayEquals(fixture.bytes(), Files.readAllBytes(object));
                    var entry = new CatalogIndexEntry(fixture.identity(), location, object, binding);
                    entries.add(entry);
                    var content = ManagedContainer.openIndexed(entry);
                    contents.add(content);
                    assertEquals(fixture.identity(), content.representation().identity());
                    assertSame(binding, new RuntimeContentStore(ignored -> {
                    }).exact(content).storageBinding());
                    verifyArtifacts(storage, content, expected, stage, models, modules, geometryContainer, textureHash,
                        previews, expressions, i + 1);
                }
                try (var remote = RemoteModelStore.create(storage)) {
                    assertTrue(remote.root().startsWith(global.cacheRoot()));
                    var remoteFixture = fixtures.get(3);
                    try (var source = ManagedContainer.verifyFile(remoteFixture.file())) {
                        var prefix = source.metadataPrefix().orElseThrow();
                        var bytes = new byte[prefix.size()];
                        prefix.nio().get(bytes);
                        remote.commitMetadataPrefix(remoteFixture.identity(), prefix);
                        var indexedMetadata = destinations.get(2).index()
                            .find(NativeArtifactIndex.ExactKind.METADATA,
                                remoteFixture.identity().modelId() + "/" + remoteFixture.identity().containerId())
                            .orElseThrow();
                        var metadata =
                            storage.remote().cache().path(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.REMOTE,
                                indexedMetadata.location().fileId());
                        assertTrue(metadata.startsWith(global.cacheRoot()));
                        assertArrayEquals(bytes, Files.readAllBytes(metadata));
                    }
                    var request = repack(fixtures.get(0));
                    assertEquals(fixtures.get(0).identity().modelId(), request.modelId());
                    assertNotEquals(fixtures.get(0).identity().containerId(), request.containerId());
                    var fetches = new AtomicInteger();
                    try (var activation = new RemoteCatalogActivation(
                        () -> new CatalogIndexSnapshot(entries, List.of(), ModelScanReport.empty()),
                        CatalogSnapshot.empty(), remote, (identities, receiver) -> {
                            fetches.incrementAndGet();
                            return CompletableFuture.failedFuture(new AssertionError("Unexpected network request"));
                        }, Runnable::run)) {
                        var publication = new RemotePublicationSnapshot(List.of(
                            new PublicationEntry(request, new HierarchyPath("global:substitute"), CatalogAccess.PUBLIC,
                                true),
                            new PublicationEntry(remoteFixture.identity(), new HierarchyPath("global:remote"),
                                CatalogAccess.PUBLIC, true)),
                            Set.of(), List.of(), Map.of());
                        var result = activation.activate(publication).join();
                        assertTrue(result.failures().isEmpty(), result.failures().toString());
                        assertEquals(0, fetches.get());
                        var local =
                            assertInstanceOf(ActivationSnapshot.Ready.class, result.entries().get(request.modelId()))
                                .record().binding().content();
                        var pureRemote = assertInstanceOf(ActivationSnapshot.Ready.class,
                            result.entries().get(remoteFixture.identity().modelId())).record().binding().content();
                        try (var localRepresentation = local.representation();
                            var remoteRepresentation = pureRemote.representation()) {
                            assertEquals(fixtures.get(0).identity(), localRepresentation.identity());
                            assertEquals(ContentStorageBinding.Kind.LOCAL, local.storageBinding().kind());
                            assertEquals(roots.get(0), local.storageBinding().localSource().orElseThrow().key().root());
                            verifyArtifacts(storage, local, destinations.get(0), stage, models, modules,
                                geometryContainer, textureHash, previews, expressions, 4);
                            assertEquals(remoteFixture.identity(), remoteRepresentation.identity());
                            assertEquals(ContentStorageBinding.Kind.REMOTE, pureRemote.storageBinding().kind());
                            var cached = ((com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent)pureRemote)
                                .cachedOnlyView();
                            assertEquals(pureRemote.storageBinding(), cached.storageBinding());
                            verifyArtifacts(storage, cached, destinations.get(2), stage, models, modules,
                                geometryContainer, textureHash, previews, expressions, 5);
                        }
                    }
                }
                assertFalse(Files.exists(storage.local().root().resolve("remote-c2.sqlite")));
                assertTrue(Files.isRegularFile(storage.local().root().resolve("f/catalog.sqlite")));
                assertTrue(Files.isRegularFile(global.cacheRoot().resolve("f/catalog.sqlite")));
                assertTrue(Files.isRegularFile(global.cacheRoot().resolve("f/remote.sqlite")));
                assertEquals(2, expressions.values().stream().distinct().count());
            } finally {
                contents.forEach(content -> content.representation().close());
            }
        } finally {
            scheduler.shutdown();
            scheduler.awaitTermination();
        }
    }

    private void verifyArtifacts(ModelStorageInfra storage, ModelContent content, Destination expected, Object stage,
        java.lang.reflect.Method models, java.lang.reflect.Method modules, Hash256 geometryContainer,
        byte[] textureHash, Map<Destination, byte[]> previews, Map<Destination, Path> expressions, int marker)
        throws Exception {
        var binding = content.storageBinding();
        assertSame(expected.region(), binding.region());
        assertSame(expected.region().cache().bakeIndex(), binding.artifacts());
        // Invoke the production selectors so a binding-to-store regression cannot hide behind a test constructor.
        var geometry = (BakedModelCache)models.invoke(stage, content);
        try (var model = geometry.loadExisting(geometryContainer, "main", textureHash, LOCATORS)) {
            assertEquals(expected.marker(), model.sortedBones().get(0).name());
        }
        var factory = (RenderTargetExpressionModuleFactory)modules.invoke(stage, content);
        var model = new ModelFileIdentity(ModelId.fromHash(hash(82)), hash(83));
        var definition = ModelDataProto.newBuilder().build();
        var settings = SettingsProto.newBuilder().setDefaultTexture("").setPreviewAnimation("")
            .setDisablePreviewRotation(false).build();
        final ExpressionArtifactIdentity identity;
        try (var module = factory.create(() -> false, model, "player", "main", new BakeProfile("routing"), false,
            definition, null, settings, ProtoUtil.serializeToArray(settings))) {
            assertEquals(
                expressions.keySet().stream().anyMatch(destination -> destination.region() == expected.region())
                    ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD,
                module.source());
            identity = module.identity();
        }
        for (var region : storage.regions())
            region.cache().derivedWork().awaitIdle();
        var lookup = ExpressionArtifactStore.class.getDeclaredMethod("path", ExpressionArtifactIdentity.class);
        lookup.setAccessible(true);
        var file = (Path)lookup.invoke(expected.region().expressions(), identity);
        assertTrue(file.startsWith(expected.region().root()));
        assertTrue(Files.size(file) > 0);
        expressions.put(expected, file);
        storage.regions().forEach(CacheRegion::clearMemory);
        try (var warm = factory.create(() -> false, model, "player", "main", new BakeProfile("routing"), false,
            definition, null, settings, ProtoUtil.serializeToArray(settings))) {
            assertEquals(ExpressionArtifactStore.Source.DISK, warm.source());
            assertEquals(identity, warm.identity());
        }
        storage.regions().forEach(CacheRegion::clearMemory);
        var previewId = hash(84);
        var bytes = png(marker);
        try (var preview = PreviewStorage.routed().forContent(content).accept(previewId, bytes)) {
            assertArrayEquals(bytes, preview.bytes());
        }
        previews.put(expected, bytes);
        for (var entry : previews.entrySet()) {
            var indexed =
                entry.getKey().index().find(NativeArtifactIndex.ExactKind.PREVIEW, previewId.toString()).orElseThrow();
            var path = entry.getKey().region().cache()
                .path(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.PREVIEW, indexed.location().fileId());
            assertTrue(path.startsWith(entry.getKey().region().root()));
            assertArrayEquals(entry.getValue(), Files.readAllBytes(path));
        }
    }

    private void seedGeometry(Destination destination, Hash256 expected,
        com.elfmcys.ysm.natives.storage.CacheStorageTypes.BakeKey key) throws Exception {
        var geometry = GeoModelProto.newBuilder()
            .addBones(BoneProto.newBuilder().setName(destination.marker()).addPivot(0).addPivot(0).addPivot(0)
                .addRotate(0).addRotate(0).addRotate(0).setCubeCount(0).build())
            .setProperties(GeoPropertiesProto.newBuilder().setTextureWidth(1).setTextureHeight(1).build())
            .setCubes(ByteBuffer.allocate(0)).build();
        var staging = destination.region().cache().reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.BAKE);
        try (var input = ArrayBuffer.move(ProtoUtil.serializeToArray(geometry));
            var pixels = NativeBuffer.allocate(4)) {
            pixels.nio().putInt(-1);
            var result = NativeBakedModel.bake(input, 1, pixels, 1, 1, 29, false);
            try (var baked = result.bakedModel(); var serialized = baked.serialize();
                var writer = new BakedModelWriter()) {
                writer.setData(expected.bytes(), geometry, serialized);
                writer.write(staging.file());
            }
        }
        staging.publish(file -> true, move -> {
            move.run();
            destination.region().cache().bakeIndex().publish(key, staging.location());
        });
        staging.close();
    }

    private Fixture fixture(String resource, String name) throws Exception {
        var source = Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/" + resource)).toURI());
        final Path file;
        try (var directory = new Directory(source)) {
            var output = Files.createDirectories(temp.resolve("fixtures").resolve(name));
            file = resource.equals("default") ? ModelParser.parseBuiltinDefault(directory, output)
                : ModelParser.parse(directory, output, DefaultAnimationFilter.keepAll());
        }
        try (var verified = ManagedContainer.verifyFile(file)) {
            return new Fixture(file, verified.identity(), Files.readAllBytes(file));
        }
    }

    private ModelFileIdentity repack(Fixture fixture) throws Exception {
        var bytes = fixture.bytes().clone();
        bytes[AssetContainerConstant.HEAD.length] ^= 1;
        try (var channel = Files.newByteChannel(fixture.file())) {
            var verification =
                AssetContainerReader.read(channel).getChunkInfo(AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
            var hash = Blake3.computeHash(ArrayBuffer.borrow(bytes, 0, verification.offset()));
            System.arraycopy(hash, 0, bytes, verification.offset(), hash.length);
        }
        var file = Files.write(temp.resolve("remote-request.mxc"), bytes);
        try (var verified = ManagedContainer.verifyFile(file)) {
            return verified.identity();
        }
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static byte[] png(int marker) throws Exception {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff000000 | marker);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private record Fixture(Path file, ModelFileIdentity identity, byte[] bytes) {
    }

    private record Destination(CacheRegion region, StorageIndex<? extends NativeArtifactIndex> index, String marker) {
    }

    private static final class Locators extends GeoLocatorType {
        @SuppressWarnings("removal")
        Locators() {
            super(new ResourceLocation("ysm", "storage-routing"));
            freeze();
        }
    }
}
