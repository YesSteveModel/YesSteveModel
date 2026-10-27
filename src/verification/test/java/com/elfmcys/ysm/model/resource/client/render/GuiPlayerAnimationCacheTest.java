package com.elfmcys.ysm.model.resource.client.render;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import org.junit.jupiter.api.AfterEach;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.util.ProtoUtil;
import dev.semaweave.compilation.EngineMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.AbstractClientPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

public class GuiPlayerAnimationCacheTest {
    private final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);

    @AfterEach
    void closeScheduler() throws InterruptedException {
        scheduler.shutdown();
        scheduler.awaitTermination();
    }

    private static final Hash256 CONTAINER = new Hash256(new byte[Hash256.SIZE]);
    private static final SettingsProto SETTINGS = SettingsProto.newBuilder().setPreviewAnimation("gui").build();

    @Test
    void fullPartitionsNeverSatisfyTheGuiProbeOrCacheOnlyRequest(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        var binding = factory.expressionBinding(CustomGuiPlayerEntity.FakePlayer.class);
        try (var full = full(factory, definition(), CustomGuiPlayerEntity.FakePlayer.class)) {
            assertEquals(ModelResourcePurpose.FULL, full.identity().purpose());
        }
        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        for (var candidate : List.of(factory, factory(root, metrics))) {
            try (var loader = loader(candidate, root)) {
                var key = new RenderTargetKey("player", "", loader.bakeProfile(), binding, ModelResourcePurpose.GUI);
                assertFalse(loader.mayHaveCachedArtifacts(CONTAINER, key));
                assertFalse(candidate.mayHaveCachedArtifacts(CONTAINER, binding, ModelResourcePurpose.GUI));
                var compiled = metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION);
                var files = artifactCount(root);
                assertThrows(com.elfmcys.ysm.format.AssetLoadException.class,
                    () -> select(candidate, definition(), ModelResourcePurpose.GUI, () -> false, true));
                assertEquals(compiled, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
                assertEquals(files, artifactCount(root));
            }
        }
    }

    @Test
    void fullMainAndArmCannotWidenTheGuiClosureAndGuiWarmsIndependently(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        var definition = definition();
        try (var full = full(factory, definition, AbstractClientPlayer.class);
            var arm = factory.createSet(() -> false, CONTAINER, "player", ExpressionSet.FP_ARM,
                factory.expressionBinding(AbstractClientPlayer.class), definition, null, SETTINGS,
                ProtoUtil.serializeToArray(SETTINGS), false, ModelResourcePurpose.FULL)) {
            assertNotEquals(full.identity(), arm.identity());
        }
        var compiled = metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION);
        final String guiKey;
        try (var module = select(factory, definition, ModelResourcePurpose.GUI, () -> false).module();
            var animations = module.animations(List.of("main"), null)) {
            guiKey = module.identity().cacheKey();
            assertEquals(ExpressionArtifactStore.Source.COLD, module.source());
            assertEquals(ModelResourcePurpose.GUI, module.identity().purpose());
            assertEquals(CustomGuiPlayerEntity.FakePlayer.class,
                module.expressionBinding().bindingPlan().rootSubjectType().orElseThrow());
            assertTrue(animations.containsKey("gui"));
            assertTrue(animations.containsKey("idle"));
            assertFalse(animations.containsKey("world_only"));
        }
        assertEquals(compiled + 1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        try (var warm = select(factory, definition, ModelResourcePurpose.GUI, () -> false, true).module()) {
            assertEquals(ExpressionArtifactStore.Source.MEMORY, warm.source());
            assertEquals(guiKey, warm.identity().cacheKey());
        }
        com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
        var diskMetrics = new ExpressionEngineMetrics();
        try (var disk = select(factory(root, diskMetrics), definition, ModelResourcePurpose.GUI, () -> false, true).module();
            var animations = disk.animations(List.of("main"), null)) {
            assertEquals(ExpressionArtifactStore.Source.DISK, disk.source());
            assertEquals(guiKey, disk.identity().cacheKey());
            assertFalse(animations.containsKey("world_only"));
        }
        assertEquals(0, diskMetrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        assertEquals(3, artifactCount(root));
    }

    @Test
    void cacheOnlyMissDoesNotPoisonTheLaterOrdinaryGuiRequest(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        assertThrows(com.elfmcys.ysm.format.AssetLoadException.class,
            () -> select(factory, definition(), ModelResourcePurpose.GUI, () -> false, true));
        assertEquals(0, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
        try (var module = select(factory, definition(), ModelResourcePurpose.GUI, () -> false).module();
            var animations = module.animations(List.of("main"), null)) {
            assertEquals(ExpressionArtifactStore.Source.COLD, module.source());
            assertTrue(animations.containsKey("gui"));
            assertFalse(animations.containsKey("world_only"));
        }
        assertEquals(1, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
    }

    @Test
    void cancellationNeverStartsGuiCompilationOrAcceptsFull(@TempDir Path root) throws Exception {
        var metrics = new ExpressionEngineMetrics();
        var factory = factory(root, metrics);
        try (var full = full(factory, definition(), AbstractClientPlayer.class)) {
        }
        var compiled = metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION);
        assertThrows(CancellationException.class,
            () -> select(factory, definition(), ModelResourcePurpose.GUI, () -> true));
        assertEquals(compiled, metrics.snapshot().stageCalls(EngineMetrics.Stage.BACKEND_COMPILATION));
    }

    private static long artifactCount(Path root) throws Exception {
        if (!Files.exists(root))
            return 0;
        try (var paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".anim.ysm-cache")).count();
        }
    }

    private static ModelRenderTargetLoader.PreparedExpressions select(RenderTargetExpressionModuleFactory factory,
        ModelDataProto definition, ModelResourcePurpose purpose, BooleanSupplier cancelled) throws Exception {
        return select(factory, definition, purpose, cancelled, false);
    }

    private static ModelRenderTargetLoader.PreparedExpressions select(RenderTargetExpressionModuleFactory factory,
        ModelDataProto definition, ModelResourcePurpose purpose, BooleanSupplier cancelled, boolean cacheOnly)
        throws Exception {
        return ModelRenderTargetLoader.prepareExpressions(factory, cancelled, CONTAINER, "player", ExpressionSet.MAIN,
            factory.expressionBinding(CustomGuiPlayerEntity.FakePlayer.class), definition, null, SETTINGS, cacheOnly,
            purpose, RenderTargetKind.PLAYER);
    }

    private static RenderTargetExpressionModule full(RenderTargetExpressionModuleFactory factory,
        ModelDataProto definition, Class<?> subjectType) throws Exception {
        return factory.createSet(() -> false, CONTAINER, "player", ExpressionSet.MAIN,
            factory.expressionBinding(subjectType), definition, null, SETTINGS, ProtoUtil.serializeToArray(SETTINGS),
            false, ModelResourcePurpose.FULL);
    }

    private static RenderTargetExpressionModuleFactory factory(Path root, ExpressionEngineMetrics metrics) {
        return new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))),
            ExpressionHost.create(HostAvailability.none()), metrics);
    }

    private ModelRenderTargetLoader loader(RenderTargetExpressionModuleFactory factory, Path root) {
        return new ModelRenderTargetLoader(
            new BakedModelCache(root.resolve("geometry"), track(new AtomicSharedCache(root))), factory, scheduler,
            new DefaultAnimationRuntime());
    }

    private static ModelDataProto definition() throws Exception {
        var frontend = new MolangFrontend();
        var value = EncodedProgramMapper.toProto(frontend.compile("v.sample + 1", MolangFrontend.SourceMode.EXPRESSION,
            new MolangFrontend.SourceLocation("gui-player-animation-cache")).program());
        var file = AnimationFileProto.newBuilder();
        for (var name : List.of("idle", "gui", "world_only")) {
            file.addAnimations(AnimationProto.newBuilder().setName(name)
                .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(value).build()).build());
        }
        return ModelDataProto.newBuilder().putAnimationFiles("main", file.build()).build();
    }

    public static final class OtherSubject {}
}
