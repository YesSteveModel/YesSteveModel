package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.client.entity.PlayerPhysicsHostFixture;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.natives.physics.NativePhysicsWorld;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AxisConfigProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.BoneProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollectionNameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CollisionShapeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ConstraintFrameProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModelProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoPropertiesProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LimitKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsConstraintProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsDefinitionProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.data.PhysicsNodeProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.SphereShapeProto;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.ManifestProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.CommonProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSetProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.InfoProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettingsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStatsProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.testutil.DerivedStorageTestScope;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.BusBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith({NativeLibraryExtension.class, DerivedStorageTestScope.class})
@EnabledIfSystemProperty(named = "ysm.physicsTargetPreparation", matches = "true")
class PhysicsTargetPreparationIntegrationTest {
    private static final String MODEL_ID = "hash:" + "12".repeat(32);
    private static final BoneProto BODY = bone("body");
    private static final BoneProto HAIR = bone("hair");
    private static final CollisionShapeProto SPHERE = CollisionShapeProto.newBuilder()
        .setSphere(SphereShapeProto.newBuilder().addCenter(0, 0, 0).setRadius(1).build()).build();
    private static final PhysicsNodeProto DYNAMIC = PhysicsNodeProto.newBuilder().setId("strand/body/0").setBone("hair")
        .setKind(PhysicsNodeKind.DYNAMIC).addShapes(SPHERE).setMass(2).setLinearDamping(0.25F).setCollectionMask(2)
        .setPresetTag("hair_chain:strand").build();
    private static final PhysicsNodeProto ANCHOR =
        PhysicsNodeProto.newBuilder().setId("anchor").setBone("body").setKind(PhysicsNodeKind.ANCHOR).build();
    private static final ConstraintFrameProto FRAME = ConstraintFrameProto.newBuilder().addPivotOffset(0, 0, 0)
        .addAxisX(1, 0, 0).addAxisY(0, 1, 0).addAxisZ(0, 0, 1).build();
    private static final PhysicsConstraintProto JOINT = PhysicsConstraintProto.newBuilder().setId("strand/joint/0")
        .setNodeA(ANCHOR.id()).setNodeB(DYNAMIC.id()).setFrameA(FRAME).setFrameB(FRAME)
        .setKind(PhysicsConstraintKind.HINGE).setAngularZ(hard(0.25F, 0.25F)).build();
    private static final PhysicsConstraintProto EQUALITY =
        JOINT.withId("offset").withKind(PhysicsConstraintKind.GENERIC).withAngularZ(null).withLinearX(hard(-2, -2))
            .withLinearY(hard(3, 3)).withLinearZ(hard(1, 1));
    private static final PhysicsDefinitionProto VALID = physics(DYNAMIC, JOINT).toBuilder().addConstraints(EQUALITY)
        .addCollections(CollectionNameProto.newBuilder().setSlot(2).setName("hair").build()).build();

    @TempDir
    Path root;

    @BeforeAll
    static void initializeHost() throws ReflectiveOperationException {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
        var eventBus = YesSteveModel.class.getDeclaredField("EVENT_BUS");
        eventBus.setAccessible(true);
        eventBus.set(null, BusBuilder.builder().build());
        PlayerLocator.init();
    }

    @Test
    void sharedPreparationSurvivesIndependentEvictionsAndWorldsRetainNativeReferences() throws Exception {
        try (var fixture = new Fixture(root); var firstUse = fixture.acquire("main", ModelResourcePurpose.FULL);
            var sameTextureUse = fixture.acquire("main", ModelResourcePurpose.FULL);
            var secondTextureUse = fixture.acquire("blue", ModelResourcePurpose.FULL);
            var guiUse = fixture.acquire("main", ModelResourcePurpose.GUI)) {
            var first = fixture.ready(firstUse);
            var secondTexture = fixture.ready(secondTextureUse);
            assertSame(first, fixture.ready(sameTextureUse));
            assertTrue(first.isStateCompatible(secondTexture));
            var asset = assertNotNullResult(first.physicsAsset());
            var metadata = assertNotNullResult(first.physicsMetadata());
            assertSame(asset, secondTexture.physicsAsset());
            assertSame(metadata, secondTexture.physicsMetadata());
            assertEquals(DYNAMIC, metadata.nodes().get(0));
            assertEquals("hair", metadata.collections().get(0).name());
            assertEquals(JOINT, metadata.constraints().get(0));
            assertEquals(EQUALITY, metadata.constraints().get(1));
            assertThrows(UnsupportedOperationException.class, () -> metadata.nodes().clear());
            var gui = fixture.ready(guiUse);
            assertNull(gui.physicsAsset());
            assertNull(gui.physicsMetadata());

            try (var firstWorld = assertNotNullResult(NativePhysicsWorld.create(asset));
                var secondWorld = assertNotNullResult(NativePhysicsWorld.create(asset))) {
                assertNotEquals(firstWorld.get(), secondWorld.get());
                firstUse.close();
                fixture.cache.tick();
                assertSame(asset, first.physicsAsset());
                sameTextureUse.close();
                fixture.cache.tick();
                assertTrue(fixture.findReady("main").isEmpty());
                first.close();
                assertThrows(IllegalStateException.class, first::physicsAsset);
                assertSame(asset, secondTexture.physicsAsset());
                assertNotEquals(0, asset.get());
                fixture.loader.close();
                assertNotEquals(0, asset.get());
                secondTextureUse.close();
                fixture.cache.tick();
                assertTrue(fixture.findReady("blue").isEmpty());
                secondTexture.close();
                assertThrows(IllegalStateException.class, asset::get);
                firstWorld.setDebugContactCapture(true);
                secondWorld.setDebugContactCapture(true);
            }
        }
    }

    @Test
    void definitionBoneOrderAndProfileChangesRebuildEvenWithTheSameModelId() throws Exception {
        try (var fixture = new Fixture(root); var first = fixture.acquire("main", ModelResourcePurpose.FULL);
            var changed = fixture.acquire(fixture.write("changed",
                VALID.toBuilder().clearNodes().addNodes(DYNAMIC.withMass(4), ANCHOR).build(), geometry(BODY, HAIR)),
                fixture.loader.bakeProfile());
            var reordered =
                fixture.acquire(fixture.write("reordered", VALID, geometry(HAIR, BODY)), fixture.loader.bakeProfile());
            var profile = fixture.acquire(fixture.content, new BakeProfile("other")); var historical = fixture
                .acquire(fixture.write("historical", null, geometry(BODY, HAIR)), fixture.loader.bakeProfile())) {
            var original = fixture.ready(first);
            for (var changedUse : List.of(changed, reordered, profile)) {
                var replacement = fixture.ready(changedUse);
                assertEquals(original.modelHash(), replacement.modelHash());
                assertNotSame(original.physicsAsset(), replacement.physicsAsset());
                assertNotSame(original.physicsMetadata(), replacement.physicsMetadata());
                assertFalse(original.isStateCompatible(replacement));
            }
            assertEquals(4.0F, fixture.ready(changed).physicsMetadata().nodes().get(0).massUnsafe());
            assertNull(fixture.ready(historical).physicsAsset());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject-nodes-129", "reject-constraints-257", "reject-node-shapes-33",
        "reject-model-shapes-513", "reject-mass-high", "reject-sphere-radius-high", "reject-unsupported-shape",
        "reject-collection-mask-high-bit", "reject-absent-bone", "reject-unanchored-free",
        "reject-unanchored-zero-soft", "reject-unanchored-full-turn", "reject-swing-outside-disk",
        "reject-anchor-on-dynamic-descendant"})
    void m2AndBothPurposesRejectTheSameInvalidDefinitionWhileOtherContentLoads(String name) throws Exception {
        try (var fixture = new Fixture(root)) {
            var file = root.resolve(name + ".mxc");
            try (var input = getClass().getResourceAsStream("/physics-fixtures/" + file.getFileName())) {
                assertNotNull(input, name);
                Files.copy(input, file);
            }
            var invalid = fixture.open(name);
            var target = invalid.representation().view().getPlayer();
            var failure =
                assertThrows(AssetLoadException.class, () -> target.readDefinition(() -> false, invalid.chunks()));
            assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
            var texture = target.getTextureNames().iterator().next();
            try (var gui = fixture.acquire(invalid, texture, ModelResourcePurpose.GUI, fixture.loader.bakeProfile());
                var full = fixture.acquire(invalid, texture, ModelResourcePurpose.FULL, fixture.loader.bakeProfile());
                var healthy = fixture.acquire("main", ModelResourcePurpose.FULL)) {
                for (var use : List.of(gui, full)) {
                    var rejection = assertInstanceOf(AcquireResult.Failed.class, fixture.complete(use)).failure();
                    assertEquals(ResourceFailure.Kind.DETERMINISTIC, rejection.kind());
                    assertEquals(failure.getMessage(), rejection.cause().getMessage());
                }
                assertNotNull(fixture.ready(healthy).physicsAsset());
            }
        }
    }

    @Test
    void nativeCompilationFailureRejectsFullBeforeBakingAndDoesNotBlockAnotherModel() throws Exception {
        var oversized = geometry(BODY, HAIR).toBuilder();
        for (int index = 2; index <= 65_536; index++) {
            oversized.addBones(BODY.withName("extra-" + index));
        }
        try (var fixture = new Fixture(root)) {
            var content = fixture.write("native-capacity", VALID, oversized.build());
            assertEquals(VALID, content.representation().view().getPlayer()
                .readDefinition(() -> false, content.chunks()).physicsUnsafe());
            try (var rejected = fixture.acquire(content, fixture.loader.bakeProfile());
                var gui = fixture.acquire(content, "main", ModelResourcePurpose.GUI, fixture.loader.bakeProfile());
                var healthy = fixture.acquire("main", ModelResourcePurpose.FULL)) {
                var failure = assertInstanceOf(AcquireResult.Failed.class, fixture.complete(rejected)).failure();
                assertEquals("Failed to compile physics for player", failure.cause().getMessage());
                var guiFailure = assertInstanceOf(AcquireResult.Failed.class, fixture.complete(gui)).failure();
                assertEquals("Failed to bake model", guiFailure.cause().getMessage());
                assertNotNull(fixture.ready(healthy).physicsAsset());
            }
        }
    }

    @Test
    void observerCannotReplaceCanonicalPoseOrConsumeMainTickAndTeleportRevokesPreparedDraw() throws Exception {
        try (var fixture = new Fixture(root); var use = fixture.acquire("main", ModelResourcePurpose.FULL)) {
            var target = fixture.ready(use);
            var binding = fixture.loader.expressionBinding(true, AbstractClientPlayer.class);
            var request = new ResourceRequest(fixture.content.modelId(), "player", "main", fixture.loader.bakeProfile(),
                binding, ModelResourcePurpose.FULL);
            PlayerPhysicsHostFixture.verifyTimeline(request, use, target, fixture.content);
        }
    }

    private static PhysicsDefinitionProto physics(PhysicsNodeProto dynamic, PhysicsConstraintProto constraint) {
        return PhysicsDefinitionProto.newBuilder().addNodes(dynamic, ANCHOR).addConstraints(constraint).build();
    }

    private static AxisConfigProto hard(float min, float max) {
        return AxisConfigProto.newBuilder().setLimit(LimitKind.HARD).setMin(min).setMax(max).build();
    }

    private static BoneProto bone(String name) {
        return BoneProto.newBuilder().setName(name).addPivot(0, 0, 0).addRotate(0, 0, 0).build();
    }

    private static GeoModelProto geometry(BoneProto... bones) {
        return GeoModelProto.newBuilder().addBones(bones).setCubes(ByteBuffer.allocate(0))
            .setProperties(GeoPropertiesProto.newBuilder().setTextureWidth(1).setTextureHeight(1).build()).build();
    }

    private static <T> T assertNotNullResult(T value) {
        assertNotNull(value);
        return value;
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final ManagedContainer content;
        final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        final ModelRenderTargetLoader loader;
        final ModelRenderTargetCache cache;
        final HashSet<ModelRenderTarget> targets = new HashSet<>();

        Fixture(Path root) throws Exception {
            this.root = root;
            content = write("initial", VALID, geometry(BODY, HAIR));
            var cacheRoot = root.resolve("cache");
            var shared = track(new AtomicSharedCache(cacheRoot));
            var expressions = new RenderTargetExpressionModuleFactory(
                track(new ExpressionArtifactStore(cacheRoot, shared)), new AnimationInputStore(cacheRoot, shared),
                ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
            loader = new ModelRenderTargetLoader(new BakedModelCache(cacheRoot, shared), expressions, scheduler,
                new DefaultAnimationRuntime());
            var textures = new TextureManager(null);
            cache = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, failure -> {
                throw new AssertionError(failure);
            }, prepared -> {
                prepared.close();
                return new HostTexturePublisher.PublishedTextureBinding(new ResourceLocation("ysm", "headless"),
                    textures, List.of());
            }, () -> 4, () -> 0);
        }

        ManagedContainer write(String name, PhysicsDefinitionProto physics, GeoModelProto main) throws Exception {
            var file = root.resolve(name + ".mxc");
            var arm = geometry(BODY);
            var definition = ModelDataProto.newBuilder().putGeoModels("main", encode(main))
                .putGeoModels("arm", encode(arm)).build().withPhysics(physics);
            var png = new ByteArrayOutputStream();
            var pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            pixel.setRGB(0, 0, 0xFFFFFFFF);
            assertTrue(ImageIO.write(pixel, "png", png));
            try (var writer = new ModelFileWriter();
                var image = Image.adopt(Image.Format.PNG, 1, 1, ArrayBuffer.move(png.toByteArray()));
                var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var texture =
                    PBRTextureSetProto.newBuilder().setUv(ImageProto.newBuilder().setBlobId(writer.addImageBlob(image))
                        .setFormat("PNG").setWidth(1).setHeight(1).setFrameCount(1).build()).build();
                var player = RenderTargetProto.newBuilder().setTargetId("player").setKind(RenderTargetKind.PLAYER)
                    .setBlobId(writer.addProtoBlob(definition, 0)).putTextures("main", texture)
                    .putTextures("blue", texture)
                    .setSettings(ModelSettingsProto.newBuilder().setHeightScale(1).setWidthScale(1).build())
                    .setStats(ModelStatsProto.newBuilder().setBones(main.bones().size()).build()).build();
                var info =
                    InfoProto.newBuilder().setProperties(PropertiesProto.newBuilder().setModelId(MODEL_ID).build())
                        .setSettings(SettingsProto.newBuilder().setDefaultTexture("main").build()).build();
                writer.setManifest(ManifestProto.newBuilder().addRenderTargets(player).setInfo(info)
                    .setCommonAssets(CommonProto.newBuilder().build()).build());
                writer.write(output);
            }
            return open(name);
        }

        ManagedContainer open(String name) throws IOException {
            var location = new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath(name));
            var container = ManagedContainer.openDirect(root.resolve(name + ".mxc"), location);
            track(container.representation());
            return container;
        }

        ResourceLease acquire(String texture, ModelResourcePurpose purpose) {
            return acquire(content, texture, purpose, loader.bakeProfile());
        }

        ResourceLease acquire(ManagedContainer source, BakeProfile profile) {
            return acquire(source, "main", ModelResourcePurpose.FULL, profile);
        }

        ResourceLease acquire(ManagedContainer source, String texture, ModelResourcePurpose purpose,
            BakeProfile profile) {
            var binding = loader.expressionBinding(true, AbstractClientPlayer.class);
            var key = new RenderTargetKey("player", texture, profile, binding, purpose);
            var request = new ResourceRequest(source.modelId(), "player", texture, profile, binding, purpose);
            return cache
                .getOrStart(
                    request, source, key, (cancelled, dispatch) -> loader.loadAsync(cancelled, dispatch, source, key,
                        true, ModelResourceFailures.none()),
                    (id, candidate, current) -> true, this, WorkPriority.BACKGROUND);
        }

        java.util.Optional<ResourceLease> findReady(String texture) {
            var profile = loader.bakeProfile();
            var binding = loader.expressionBinding(true, AbstractClientPlayer.class);
            var key = new RenderTargetKey("player", texture, profile, binding, ModelResourcePurpose.FULL);
            var request =
                new ResourceRequest(content.modelId(), "player", texture, profile, binding, ModelResourcePurpose.FULL);
            return cache.findReady(request, content, key, (id, candidate, current) -> true);
        }

        AcquireResult complete(ResourceLease lease) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (lease.poll() instanceof AcquireResult.Pending && System.nanoTime() < deadline) {
                cache.tick();
                Thread.sleep(1);
            }
            assertFalse(lease.poll() instanceof AcquireResult.Pending, "target preparation timed out");
            return lease.poll();
        }

        ModelRenderTarget ready(ResourceLease lease) throws InterruptedException {
            var result = complete(lease);
            if (result instanceof AcquireResult.Failed failure)
                fail("target preparation failed", failure.failure().cause());
            var target = assertInstanceOf(AcquireResult.Ready.class, result).target();
            targets.add(target);
            return target;
        }

        @Override
        public void close() throws Exception {
            cache.close();
            targets.forEach(ModelRenderTarget::close);
            loader.close();
            scheduler.shutdown();
            scheduler.awaitTermination();
        }

        private static ByteBuffer encode(GeoModelProto geometry) throws Exception {
            return ByteBuffer.wrap(ProtoUtil.serializeToArray(geometry));
        }
    }
}
