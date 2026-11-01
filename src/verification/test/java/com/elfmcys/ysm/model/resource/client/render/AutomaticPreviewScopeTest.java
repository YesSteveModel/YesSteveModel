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
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.preview.ClientPreviewGenerator;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.PreviewDecodeProbe;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
                    try (var warm = new Fixture(root, content); var candidate = warm.load(ModelResourcePurpose.FULL)) {
                        var target = candidate.publish(warm.publisher);
                        assertNotNull(target.playerResources().defaultVariant().armModel());
                    }
                }
                try (var fixture = new Fixture(root, content)) {
                    PreviewDecodeProbe.beginScope();
                    try {
                        try (var candidate =
                            use.equals("generation") ? fixture.detached() : fixture.load(ModelResourcePurpose.GUI)) {
                            var target = candidate.publish(fixture.publisher);
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

    private static final class Fixture implements AutoCloseable {
        private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        private final AtomicSharedCache storage;
        private final ExpressionArtifactStore artifacts;
        private final ModelRenderTargetLoader loader;
        private final ManagedContainer content;
        private final HostTexturePublisher publisher;

        Fixture(Path root, ManagedContainer content) throws Exception {
            this.content = content;
            storage = new AtomicSharedCache(root);
            artifacts = new ExpressionArtifactStore(root, storage);
            var modules = new RenderTargetExpressionModuleFactory(artifacts,
                ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
            var defaults = new DefaultAnimationRuntime();
            var empty = ModelDataProto.newBuilder().build();
            defaults.publishSources(Map.of("player/main",
                new DefaultAnimationRuntime.Source(content.representation().containerId(), "player", ExpressionSet.MAIN,
                    empty, null),
                "player/first-person", new DefaultAnimationRuntime.Source(content.representation().containerId(),
                    "player", ExpressionSet.FP_ARM, empty, null)));
            loader = new ModelRenderTargetLoader(new BakedModelCache(root, storage), modules, scheduler, defaults);
            var manager = (TextureManager)UnsafeUtil.getUnsafe().allocateInstance(TextureManager.class);
            publisher = prepared -> {
                prepared.get();
                prepared.close();
                return new HostTexturePublisher.PublishedTextureBinding(new ResourceLocation("ysm", "headless"),
                    manager, List.of());
            };
        }

        ResourceRequest request(ModelResourcePurpose purpose) {
            return new ResourceRequest(content.modelId(), "player", "", loader.bakeProfile(), loader.expressionBinding(
                purpose.isGui() ? CustomGuiPlayerEntity.FakePlayer.class : AbstractClientPlayer.class), purpose);
        }

        RenderTargetKey key(ResourceRequest request) {
            return ClientModelRenderTargetManager.resolveEffectiveKey(content.representation().view().getManifest(),
                request);
        }

        ModelRenderTargetLoader.ModelCandidate load(ModelResourcePurpose purpose) throws Exception {
            var outcome = loader.loadAsync(() -> false, scheduler.dispatch(WorkPriority.BACKGROUND), content,
                key(request(purpose)), false, ModelResourceFailures.none()).get(30, TimeUnit.SECONDS);
            if (outcome instanceof ModelRenderTargetLoader.LoadResult.Failed failed)
                fail("Real preparation failed", failed.failure().cause());
            return ((ModelRenderTargetLoader.LoadResult.Ready)outcome).candidate();
        }

        @SuppressWarnings("unchecked")
        ModelRenderTargetLoader.ModelCandidate detached() throws Exception {
            var method = ClientPreviewGenerator.class.getDeclaredMethod("loadDetached", ModelRenderTargetLoader.class,
                ClientResourceScheduler.class, ModelContent.class, ResourceRequest.class, BooleanSupplier.class);
            method.setAccessible(true);
            return ((CompletableFuture<ModelRenderTargetLoader.ModelCandidate>)method.invoke(null, loader, scheduler,
                content, request(ModelResourcePurpose.GUI), (BooleanSupplier)() -> false)).get(30, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            loader.close();
            scheduler.shutdown();
            scheduler.awaitTermination();
            storage.derivedWork().awaitIdle();
            artifacts.awaitPersistence();
            storage.derivedWork().close();
        }
    }
}
