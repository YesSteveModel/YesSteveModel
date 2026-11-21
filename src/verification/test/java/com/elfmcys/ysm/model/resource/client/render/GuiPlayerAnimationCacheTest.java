package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.aot.TestExpressionArtifactStores;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.TestCacheFiles;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.BakeKind;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunctionProto;
import com.elfmcys.ysm.proto.mixel.common.ProgramProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.RoamingRole;
import dev.semaweave.compilation.CompilerOptions;
import dev.semaweave.compilation.EngineMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.AbstractClientPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class GuiPlayerAnimationCacheTest {
    static final Hash256 CONTAINER = new Hash256(new byte[Hash256.SIZE]);
    static final SettingsProto SETTINGS = SettingsProto.newBuilder().setPreviewAnimation("gui").build();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothPreparationOrdersShareInputsWithoutChangingExecutionPurpose(boolean fullFirst, @TempDir Path root)
        throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        try (var first = fullFirst ? full(factory, definition()) : gui(factory, definition(), false)) {
            var before = compilations(metrics);
            try (var second = fullFirst ? gui(factory, definition(), false) : full(factory, definition());
                var animations = (fullFirst ? second : first).openReadPhase()) {
                var gui = fullFirst ? second : first;
                var full = fullFirst ? first : second;
                assertTrue(gui.interpreted());
                assertFalse(full.interpreted());
                assertEquals(ModelResourcePurpose.FULL, full.diagnostics().identity().purpose());
                assertNull(gui.diagnostics().identity());
                assertEquals(CONTAINER, gui.metadata().containerId());
                assertEquals("player", gui.metadata().targetId());
                assertEquals(ExpressionSet.MAIN, gui.metadata().set());
                assertEquals(CustomGuiPlayerEntity.FakePlayer.class,
                    gui.expressionBinding().bindingPlan().rootSubjectType().orElseThrow());
                assertEquals(Set.of("idle", "gui", "world_only"), animations.animations().keySet());
                assertEquals(fullFirst ? before : before + 1, compilations(metrics));
                if (fullFirst)
                    assertEquals(ExpressionArtifactStore.Source.MEMORY, second.source());
            }
        }
        assertEquals(1, compilations(metrics));
    }

    @Test
    void admittedDiskGuiRetainsPreparedProgramsWithoutStorageOrNewWrites(@TempDir Path root) throws Exception {
        var shared = track(new AtomicSharedCache(root));
        var inputs = new AnimationInputStore(root, shared);
        var request = inputRequest();
        inputs.acquire(request, false);
        inputs.awaitPersistence();
        inputs.clearMemory();
        assertEquals(1, TestCacheFiles.bakeCount(root, BakeKind.ANIMATION));
        var aotStorage = track(new AtomicSharedCache(root.resolve("aot")));
        var artifacts = track(new ExpressionArtifactStore(root.resolve("aot"), aotStorage));
        aotStorage.close();
        var metrics = new ExpressionEngineMetrics();
        var factory = new RenderTargetExpressionModuleFactory(artifacts, inputs,
            ExpressionHost.create(HostAvailability.none()), metrics);
        try (var module = gui(factory, definition(), true); var animations = module.openReadPhase()) {
            assertEquals(ExpressionArtifactStore.Source.DISK, module.source());
            assertEquals(Set.of("idle", "gui", "world_only"), animations.animations().keySet());
            // The admitted module already owns matching source input; reopening the derivative is redundant.
            shared.close();
            var before = shared.derivedWork().snapshot();
            var id = sampleId(module);
            Object original;
            try (var use = module.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER)) {
                original = use.interpreterUse().program(id);
                assertNotNull(original);
            }
            assertEquals(1, module.boundProgramCount());
            try (var use = module.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER)) {
                assertSame(original, use.interpreterUse().program(id));
            }
            assertEquals(before, shared.derivedWork().snapshot());
        }
        assertEquals(1, TestCacheFiles.bakeCount(root, BakeKind.ANIMATION));
        assertEquals(0, compilations(metrics));
    }

    @Test
    void aotFileIsNeitherRequiredNorSufficientForCacheOnlyGui(@TempDir Path root) throws Exception {
        var shared = track(new AtomicSharedCache(root));
        var artifacts = track(new ExpressionArtifactStore(root, shared));
        var inputs = new AnimationInputStore(root, shared);
        var metrics = new ExpressionEngineMetrics();
        var factory = new RenderTargetExpressionModuleFactory(artifacts, inputs,
            ExpressionHost.create(HostAvailability.none()), metrics);
        try (var full = full(factory, definition())) {
            artifacts.awaitPersistence();
            inputs.awaitPersistence();
            var aotPath = TestExpressionArtifactStores.path(artifacts, full.diagnostics().identity());
            var aotBytes = Files.readAllBytes(aotPath);
            var inputPath = inputs.path(inputRequest().identity());
            Files.delete(aotPath);
            var diskFactory = factory(root, metrics);
            try (var gui = gui(diskFactory, definition(), true)) {
                assertTrue(gui.interpreted());
                assertEquals(ExpressionArtifactStore.Source.DISK, gui.source());
            }
            Files.write(aotPath, aotBytes);
            Files.delete(inputPath);
            var emptyFactory = factory(root, metrics);
            assertThrows(AssetLoadException.class, () -> gui(emptyFactory, definition(), true));
            try (var animations = full.openReadPhase()) {
                assertNotNull(animations.animations().get("world_only"));
            }
        }
        assertEquals(1, compilations(metrics));
    }

    @Test
    void cacheOnlyMissDoesNotPoisonLaterOrdinaryGuiOrStartCompilation(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        assertThrows(AssetLoadException.class, () -> gui(factory, definition(), true));
        assertEquals(0, TestCacheFiles.bakeCount(root, BakeKind.ANIMATION));
        try (var module = gui(factory, definition(), false); var animations = module.openReadPhase()) {
            assertEquals(ExpressionArtifactStore.Source.COLD, module.source());
            assertEquals(Set.of("idle", "gui", "world_only"), animations.animations().keySet());
        }
        assertEquals(0, compilations(metrics));
    }

    @Test
    void concurrentColdGuiAndFullRequestsEachProduceAUsableCandidate(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var gui = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return gui(factory, definition(), false);
            });
            var full = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return full(factory, definition());
            });
            start.countDown();
            try (var guiModule = gui.get(30, TimeUnit.SECONDS); var fullModule = full.get(30, TimeUnit.SECONDS);
                var use = guiModule.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER);
                var animations = fullModule.openReadPhase()) {
                assertTrue(guiModule.interpreted());
                assertFalse(fullModule.interpreted());
                assertNotNull(use.interpreterUse().program(sampleId(guiModule)));
                assertNotNull(animations.animations().get("gui"));
                assertEquals(1, compilations(metrics));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedAotPreparationDoesNotPoisonValidGuiInputsOrConsumeAnUncalledBadFunction(@TempDir Path root)
        throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(
                UserFunctionProto.newBuilder().setName("bad").setBody(ProgramProto.newBuilder().build()).build())
            .build();
        assertThrows(AssetLoadException.class,
            () -> create(factory, definition(), strings, ModelResourcePurpose.FULL, () -> false));
        try (var module = create(factory, definition(), strings, ModelResourcePurpose.GUI, () -> false);
            var use = module.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER)) {
            assertEquals(0, module.boundProgramCount());
            assertNotNull(use.interpreterUse().program(sampleId(module)));
            assertEquals(1, module.boundProgramCount());
        }
        assertEquals(0, compilations(metrics));
    }

    @Test
    void acceptedExecutionPreparesItsFirstCalledFunctionAfterStoreSeal(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        var frontend = new MolangFrontend();
        var call = EncodedProgramMapper.toProto(frontend.compile("fn.helper()", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("sealed-call")).program());
        var body = EncodedProgramMapper.toProto(frontend.compile("return 7;", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("sealed-helper")).program());
        var definition =
            ModelDataProto.newBuilder()
                .putAnimationFiles("main", AnimationFileProto.newBuilder()
                    .addAnimations(AnimationProto.newBuilder().setName("gui")
                        .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(call).build()).build())
                    .build())
                .build();
        var strings = StringDataProto.newBuilder()
            .addUserFunctions(UserFunctionProto.newBuilder().setName("helper").setBody(body).build()).build();
        try (var store = create(factory, definition, strings, ModelResourcePurpose.GUI, () -> false);
            var runtime = new com.elfmcys.ysm.expression.aot.EntityExpressionRuntime()) {
            runtime.install(runtime.prepare(store, TestComponentHolders.linkageScope(), RoamingRole.PRODUCER), () -> {
            });
            final com.elfmcys.ysm.expression.aot.FloatExpression.Program program;
            try (var preparation = store.prepare()) {
                program = preparation.requireFloatProgram(sample());
            }
            store.close();
            assertEquals(0, store.boundProgramCount());
            float value = runtime.observe(
                new com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.InvocationInputs(
                    new com.elfmcys.ysm.expression.host.InvocationInputs<net.minecraft.world.entity.Entity>() {}),
                evaluation -> evaluation.evaluate(program));
            assertEquals(7F, value);
            assertEquals(2, store.boundProgramCount());
            assertEquals(1, store.activeUses());
            runtime.clear();
            assertTrue(store.isClosed());
        }
        assertEquals(0, compilations(metrics));
    }

    @Test
    void aotCommitFailureAndGuiCancellationLeaveTheOtherPathUsable(@TempDir Path root) throws Exception {
        var shared = track(new AtomicSharedCache(root));
        var artifacts = track(TestExpressionArtifactStores.failingPersistence(root, shared));
        var metrics = new ExpressionEngineMetrics();
        var factory = new RenderTargetExpressionModuleFactory(artifacts, new AnimationInputStore(root, shared),
            ExpressionHost.create(HostAvailability.none()), metrics);
        try (var full = full(factory, definition())) {
            artifacts.awaitPersistence();
            assertEquals(1, shared.derivedWork().snapshot().failed());
            assertThrows(CancellationException.class,
                () -> create(factory, definition(), null, ModelResourcePurpose.GUI, () -> true));
            try (var module = gui(factory, definition(), false);
                var use = module.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER);
                var animations = full.openReadPhase()) {
                assertNotNull(use.interpreterUse().program(sampleId(module)));
                assertNotNull(animations.animations().get("gui"));
            }
        }
        assertEquals(1, compilations(metrics));
    }

    static ModelProgramInventory.SemanticLocation sample() {
        return new ModelProgramInventory.SemanticLocation("player", ExpressionSet.MAIN,
            "animation/main/gui/blend-weight", ProgramRole.ANIMATION_SAMPLE, "");
    }

    static long compilations(ExpressionEngineMetrics metrics) {
        return metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION);
    }

    static AnimationStore gui(RenderTargetExpressionModuleFactory factory, ModelDataProto definition, boolean cacheOnly)
        throws Exception {
        return initialize(rawGui(factory, definition, cacheOnly));
    }

    static AnimationStore rawGui(RenderTargetExpressionModuleFactory factory, ModelDataProto definition,
        boolean cacheOnly) throws Exception {
        return ModelRenderTargetLoader.prepareExpressions(factory, () -> false, CONTAINER, "player", ExpressionSet.MAIN,
            factory.expressionBinding(CustomGuiPlayerEntity.FakePlayer.class), definition, null, SETTINGS, cacheOnly,
            ModelResourcePurpose.GUI, RenderTargetKind.PLAYER, CompilerOptions.DEFAULT).store();
    }

    static AnimationStore full(RenderTargetExpressionModuleFactory factory, ModelDataProto definition)
        throws Exception {
        return create(factory, definition, null, ModelResourcePurpose.FULL, () -> false);
    }

    private static AnimationStore create(RenderTargetExpressionModuleFactory factory, ModelDataProto definition,
        StringDataProto strings, ModelResourcePurpose purpose, BooleanSupplier cancelled) throws Exception {
        return initialize(factory.createStore(cancelled, CONTAINER, "player", ExpressionSet.MAIN,
            factory.expressionBinding(
                purpose.isGui() ? CustomGuiPlayerEntity.FakePlayer.class : AbstractClientPlayer.class),
            definition, strings, SETTINGS, ProtoUtil.serializeToArray(SETTINGS), false, purpose,
            CompilerOptions.DEFAULT));
    }

    static AnimationStore initialize(AnimationStore store) {
        try (var preparation = store.prepare()) {
            preparation.initialize(preparation.animations(List.of("main")), null, java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of());
        }
        return store;
    }

    static int sampleId(AnimationStore store) {
        try (var preparation = store.prepare()) {
            return preparation.requireFloatProgram(sample()).binding().programId();
        }
    }

    private static AnimationInputStore.Request inputRequest() throws Exception {
        return AnimationInputStore.prepare(CONTAINER, "player", ExpressionSet.MAIN, definition(), null, SETTINGS,
            CompilerOptions.DEFAULT);
    }

    static RenderTargetExpressionModuleFactory factory(Path root, ExpressionEngineMetrics metrics) {
        var shared = track(new AtomicSharedCache(root));
        return new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(root, shared)),
            new AnimationInputStore(root, shared), ExpressionHost.create(HostAvailability.none()), metrics);
    }

    static ModelDataProto definition() throws Exception {
        var value = EncodedProgramMapper
            .toProto(new MolangFrontend().compile("v.sample + 1", MolangFrontend.SourceMode.EXPRESSION,
                new MolangFrontend.SourceLocation("gui-player-animation-cache")).program());
        var file = AnimationFileProto.newBuilder();
        for (var name : List.of("idle", "gui", "world_only")) {
            file.addAnimations(AnimationProto.newBuilder().setName(name)
                .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(value).build()).build());
        }
        return ModelDataProto.newBuilder().putAnimationFiles("main", file.build()).build();
    }
}
