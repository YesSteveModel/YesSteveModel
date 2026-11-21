package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewDecodeProbe;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.BakeKey;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.BakeKind;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.semaweave.compilation.EngineMetrics;

import static org.junit.jupiter.api.Assertions.*;

/** Real preparation, geometry restore/bake and texture decode; only GPU publication is replaced. */
@Timeout(60)
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "ysm.previewDecodeProbe", matches = "true")
class AutomaticPreviewScopeTest {
    @TempDir
    Path root;

    @BeforeAll
    static void establishHost() throws Exception {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
        var eventBus = YesSteveModel.class.getDeclaredField("EVENT_BUS");
        eventBus.setAccessible(true);
        eventBus.set(null, net.minecraftforge.eventbus.api.BusBuilder.builder().build());
        PlayerLocator.init();
    }

    @org.junit.jupiter.api.Test
    void offlineOpeningRetainsItsSourceUntilTheLoaderOwnsIndependentInputs() throws Exception {
        var source = Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default")).toURI());
        try (var directory = new Directory(source)) {
            var memory = ModelParser.parseBuiltinDefaultMemory(directory);
            var content = ManagedContainer.openResidentDefault(memory.bytes(),
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("fixture")));
            try (var representation = content.representation();
                var fixture = new Fixture(root.resolve("derived"), content); var remoteStore =
                    new com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore(root.resolve("remote"))) {
                var remote = remoteStore.commitMetadataPrefix(representation.identity(),
                    representation.metadataPrefix().orElseThrow());
                try (var metadata = remote.representation()) {
                    var asset = representation.view().getFileView().getAssetView();
                    var manifest =
                        asset.getChunkInfo(com.elfmcys.ysm.format.schema.model.ModelFileConstant.MANIFEST_CHUNK_NAME);
                    var metadataEnd = manifest.offset() + manifest.size();
                    for (var chunk : asset.getChunkTable().values()) {
                        if (chunk.offset() >= metadataEnd)
                            remoteStore.publishChunk(content.modelId(), chunk,
                                content.chunks().readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY));
                    }
                    var entered = new java.util.concurrent.CountDownLatch(1);
                    var release = new java.util.concurrent.CountDownLatch(1);
                    fixture.scheduler.backgroundExecutor().execute(() -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(30, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    });
                    try {
                        var managerType = ClientModelRenderTargetManager.class;
                        var manager = UnsafeUtil.getUnsafe().allocateInstance(managerType);
                        var loaderField = managerType.getDeclaredField("loader");
                        loaderField.setAccessible(true);
                        loaderField.set(manager, fixture.loader);
                        var resolvedType = Class.forName(managerType.getName() + "$ResolvedResource");
                        var constructor = resolvedType.getDeclaredConstructors()[0];
                        constructor.setAccessible(true);
                        var resolved =
                            constructor.newInstance(null, fixture.key(fixture.request(ModelResourcePurpose.GUI)));
                        var opening =
                            managerType.getDeclaredMethod("loadOffline", java.util.function.BooleanSupplier.class,
                                ClientResourceScheduler.Dispatch.class, resolvedType,
                                com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent.class, boolean.class);
                        opening.setAccessible(true);
                        assertTrue(entered.await(10, TimeUnit.SECONDS));
                        @SuppressWarnings("unchecked")
                        var result = (java.util.concurrent.CompletableFuture<ModelRenderTargetLoader.LoadResult>)opening
                            .invoke(manager, (java.util.function.BooleanSupplier)() -> false,
                                fixture.scheduler.dispatch(WorkPriority.BACKGROUND), resolved, remote, false);
                        metadata.close();
                        release.countDown();
                        var outcome = result.get(30, TimeUnit.SECONDS);
                        if (outcome instanceof ModelRenderTargetLoader.LoadResult.Failed failed)
                            fail("Offline preparation failed", failed.failure().cause());
                        var ready = assertInstanceOf(ModelRenderTargetLoader.LoadResult.Ready.class, outcome);
                        try (var candidate = ready.candidate()) {
                            var targetField = ModelRenderTargetLoader.ModelCandidate.class.getDeclaredField("target");
                            targetField.setAccessible(true);
                            var target = (ModelRenderTarget)targetField.get(candidate);
                            assertNotNull(target.playerResources().defaultVariant().mainModel());
                        }
                    } finally {
                        release.countDown();
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"cold,page", "cold,generation", "fullDisk,page", "fullDisk,generation"})
    void automaticBuildsOnlyTheDefaultBodyRegardlessOfFullDiskCache(String temperature, String use) throws Exception {
        var source = Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default")).toURI());
        try (var directory = new Directory(source)) {
            var memory = ModelParser.parseBuiltinDefaultMemory(directory);
            var bytes = memory.bytes();
            var content = ManagedContainer.openResidentDefault(bytes,
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("fixture")));
            try (var representation = content.representation()) {
                if (temperature.equals("fullDisk")) {
                    try (var warm = new Fixture(root, content); var lease = warm.load(ModelResourcePurpose.FULL)) {
                        var target = ((AcquireResult.Ready)lease.poll()).target();
                        assertNotNull(target.playerResources().defaultVariant().armModel());
                    }
                }
                try (var fixture = new Fixture(root, content)) {
                    PreviewDecodeProbe.beginScope();
                    try {
                        try (var lease =
                            use.equals("generation") ? fixture.detached() : fixture.load(ModelResourcePurpose.GUI)) {
                            var target = ((AcquireResult.Ready)lease.poll()).target();
                            assertNull(target.playerResources().defaultVariant().armModel());
                            var selected = fixture.key(fixture.request(ModelResourcePurpose.GUI)).selectedTexture();
                            assertEquals(Map.of("player/" + selected + "/main", 1), PreviewDecodeProbe.geometry());
                            var descriptor =
                                content.modelFile().requireRenderTarget("player").textureDescriptor(selected);
                            var expected = new java.util.HashMap<String, Integer>();
                            expected.put("uv", 1);
                            if (descriptor.hasNormal())
                                expected.put("normal", 1);
                            if (descriptor.hasSpecular())
                                expected.put("specular", 1);
                            assertEquals(expected, PreviewDecodeProbe.textures());
                        }
                    } finally {
                        PreviewDecodeProbe.endScope();
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fullPreparationRestoresOnlyMissingInputsAndAotAlongsideCachedGeometry(boolean keepInputs, boolean keepAot)
        throws Exception {
        var source = Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default")).toURI());
        try (var directory = new Directory(source)) {
            var memory = ModelParser.parseBuiltinDefaultMemory(directory);
            var content = ManagedContainer.openResidentDefault(memory.bytes(),
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("fixture")));
            try (var representation = content.representation()) {
                final Map<BakeKey, Path> seeded;
                final List<String> aot;
                try (var warm = new Fixture(root, content); var lease = warm.load(ModelResourcePurpose.FULL)) {
                    var target = assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target();
                    warm.storage.derivedWork().awaitIdle();
                    seeded = warm.cachePaths();
                    aot = List.of(target.animationStore().diagnostics().identity().lookupKey(),
                        target.armAnimationStore().diagnostics().identity().lookupKey());
                    assertEquals(6, seeded.size());
                    assertEquals(2,
                        seeded.keySet().stream().filter(key -> key.partition().kind() == BakeKind.GEOMETRY).count());
                }
                var retained = new java.util.HashMap<BakeKey, byte[]>();
                for (var entry : seeded.entrySet()) {
                    var keep = entry.getKey().partition().kind() == BakeKind.GEOMETRY
                        || (aot.contains(entry.getKey().exact()) ? keepAot : keepInputs);
                    if (keep)
                        retained.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
                    else
                        Files.delete(entry.getValue());
                }
                try (var fixture = new Fixture(root, content); var lease = fixture.load(ModelResourcePurpose.FULL)) {
                    var request = fixture.request(ModelResourcePurpose.FULL);
                    var target = assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target();
                    assertNotNull(target.playerResources().defaultVariant().mainModel());
                    assertNotNull(target.playerResources().defaultVariant().armModel());
                    assertEquals(ExpressionSet.MAIN, target.animationStore().metadata().set());
                    assertEquals(ExpressionSet.FP_ARM, target.armAnimationStore().metadata().set());
                    assertFalse(target.animationStore().diagnostics().programLocations().isEmpty());
                    for (var store : List.of(target.animationStore(), target.armAnimationStore())) {
                        assertEquals(ModelResourcePurpose.FULL, store.metadata().purpose());
                        assertSame(request.expressionBinding(), store.expressionBinding());
                        assertFalse(store.interpreted());
                        // The builtin FP_ARM has no expressions but still loads a verified AOT module.
                        assertNotNull(store.diagnostics().compilationReport());
                        assertEquals(
                            keepAot ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD,
                            store.source());
                    }
                    assertEquals(keepAot ? 0 : 2,
                        fixture.metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
                    fixture.storage.derivedWork().awaitIdle();
                    assertEquals((keepInputs ? 0 : 2) + (keepAot ? 0 : 2),
                        fixture.storage.derivedWork().snapshot().succeeded());
                    for (var entry : seeded.entrySet()) {
                        var restored = fixture.storage.bakeIndex().find(entry.getKey()).orElseThrow();
                        var path = fixture.storage.path(Pool.BAKE, restored.location().fileId());
                        assertTrue(Files.exists(path));
                        if (retained.containsKey(entry.getKey())) {
                            assertEquals(entry.getValue(), path);
                            assertArrayEquals(retained.get(entry.getKey()), Files.readAllBytes(path));
                        } else {
                            assertNotEquals(entry.getValue(), path);
                        }
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    void fullReadyDoesNotWaitForAnyDerivedPersistence() throws Exception {
        var source = Path.of(Objects.requireNonNull(getClass().getResource("/assets/ysm/builtin/default")).toURI());
        try (var directory = new Directory(source)) {
            var memory = ModelParser.parseBuiltinDefaultMemory(directory);
            var content = ManagedContainer.openResidentDefault(memory.bytes(),
                new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("fixture")));
            try (var representation = content.representation(); var fixture = new Fixture(root, content)) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var blocker = fixture.storage.derivedWork().submit(gate -> {
                    entered.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS))
                            throw new java.io.IOException("Timed out waiting for persistence release");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(failure);
                    }
                    gate.commit(() -> {
                    });
                });
                assertNotNull(blocker);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    try (var lease = fixture.load(ModelResourcePurpose.FULL)) {
                        var target = assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target();
                        assertNotNull(target.playerResources().defaultVariant().mainModel());
                        assertNotNull(target.playerResources().defaultVariant().armModel());
                        assertEquals(0, fixture.storage.derivedWork().snapshot().succeeded());
                        assertEquals(7, fixture.storage.derivedWork().snapshot().jobs());
                        assertTrue(fixture.cachePaths().isEmpty());
                        release.countDown();
                        fixture.storage.derivedWork().awaitIdle();
                        assertEquals(7, fixture.storage.derivedWork().snapshot().succeeded());
                        assertEquals(6, fixture.cachePaths().size());
                        assertSame(target, assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
                    }
                } finally {
                    release.countDown();
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        private final AtomicSharedCache storage;
        private final ExpressionArtifactStore artifacts;
        private final ExpressionEngineMetrics metrics = new ExpressionEngineMetrics();
        private final ModelRenderTargetLoader loader;
        private final ModelRenderTargetCache renderTargets;
        private final List<ModelRenderTarget> acquiredTargets = new ArrayList<>();
        private final ManagedContainer content;

        Fixture(Path root, ManagedContainer content) throws Exception {
            this.content = content;
            storage = new AtomicSharedCache(root);
            artifacts = new ExpressionArtifactStore(root, storage);
            var modules = new RenderTargetExpressionModuleFactory(artifacts, new AnimationInputStore(root, storage),
                ExpressionHost.create(HostAvailability.none()), metrics);
            var defaults = new DefaultAnimationRuntime();
            var empty = ModelDataProto.newBuilder().build();
            defaults.publishSources(Map.of("player/main",
                new DefaultAnimationRuntime.Source(content.representation().containerId(), "player", ExpressionSet.MAIN,
                    empty, null),
                "player/first-person", new DefaultAnimationRuntime.Source(content.representation().containerId(),
                    "player", ExpressionSet.FP_ARM, empty, null)));
            loader = new ModelRenderTargetLoader(new BakedModelCache(root, storage), modules, scheduler, defaults);
            var manager = (TextureManager)UnsafeUtil.getUnsafe().allocateInstance(TextureManager.class);
            HostTexturePublisher publisher = prepared -> {
                prepared.get();
                prepared.close();
                return new HostTexturePublisher.PublishedTextureBinding(new ResourceLocation("ysm", "headless"),
                    manager, List.of());
            };
            renderTargets = new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, failure -> {
                throw new AssertionError(failure);
            }, publisher, () -> 4, () -> 0);
        }

        ResourceRequest request(ModelResourcePurpose purpose) {
            return new ResourceRequest(content.modelId(), "player", "", loader.bakeProfile(), loader.expressionBinding(
                purpose.isGui() ? CustomGuiPlayerEntity.FakePlayer.class : AbstractClientPlayer.class), purpose);
        }

        RenderTargetKey key(ResourceRequest request) {
            return ClientModelRenderTargetManager.resolveEffectiveKey(content.representation().view().getManifest(),
                request);
        }

        Map<BakeKey, Path> cachePaths() throws Exception {
            try (var running = com.elfmcys.ysm.natives.NativeCancellation.create();
                var bake = com.elfmcys.ysm.natives.storage.NativeBakeIndex.open(storage.layoutRoot(), running)) {
                var result = new java.util.LinkedHashMap<BakeKey, Path>();
                for (var row : bake.pageObjects(java.util.OptionalLong.empty(), running).rows())
                    result.put(row.key(), storage.path(Pool.BAKE, row.file().location().fileId()));
                return result;
            }
        }

        ResourceLease load(ModelResourcePurpose purpose) throws Exception {
            var request = request(purpose);
            var key = key(request);
            return awaitReady(renderTargets.getOrStart(
                request, content, key, (cancelled, dispatch) -> loader.loadAsync(cancelled, dispatch, content, key,
                    false, ModelResourceFailures.none()),
                (identity, targetKey, use) -> true, this, WorkPriority.BACKGROUND));
        }

        ResourceLease detached() throws Exception {
            var request = request(ModelResourcePurpose.GUI);
            var key = key(request);
            return awaitReady(renderTargets.getOrStartDetached(request, content, key, (cancelled, dispatch) -> loader
                .loadAsync(cancelled, dispatch, content, key, false, ModelResourceFailures.none()), this,
                WorkPriority.BACKGROUND));
        }

        private ResourceLease awaitReady(ResourceLease lease) throws Exception {
            var accepted = false;
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline) {
                    renderTargets.tick();
                    var outcome = lease.poll();
                    if (outcome instanceof AcquireResult.Ready ready) {
                        acquiredTargets.add(ready.target());
                        accepted = true;
                        return lease;
                    }
                    if (outcome instanceof AcquireResult.Failed failed)
                        fail("Real preparation failed", failed.failure().cause());
                    assertInstanceOf(AcquireResult.Pending.class, outcome);
                    Thread.sleep(1);
                }
                return fail("Real preparation did not become ready");
            } finally {
                if (!accepted)
                    lease.close();
            }
        }

        @Override
        public void close() throws Exception {
            try (storage; var work = storage.derivedWork()) {
                renderTargets.close();
                acquiredTargets.forEach(ModelRenderTarget::close);
                loader.close();
                scheduler.shutdown();
                scheduler.awaitTermination();
                work.awaitIdle();
                artifacts.awaitPersistence();
            }
        }
    }
}
