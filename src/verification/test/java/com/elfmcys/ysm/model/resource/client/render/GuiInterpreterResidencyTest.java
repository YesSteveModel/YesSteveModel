package com.elfmcys.ysm.model.resource.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.host.TestComponentHolders;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourcePurpose;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import com.elfmcys.ysm.model.resource.client.work.WorkPriority;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.semaweave.RoamingRole;
import dev.semaweave.interpreter.InterpreterProgram;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiInterpreterResidencyTest {
    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread())
            RenderSystem.initRenderThread();
    }

    @Test
    void idleReadyStoreRetainsProgramsAndAnimationsAcrossLongAbsence(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root)) {
            var first = fixture.acquire();
            var second = fixture.acquire();
            fixture.cache.tick();
            assertSame(fixture.target, assertInstanceOf(AcquireResult.Ready.class, first.poll()).target());
            assertSame(fixture.target, assertInstanceOf(AcquireResult.Ready.class, second.poll()).target());
            assertEquals(0, fixture.store.activeUses(), "Ready leases are retention, not execution uses");
            var original = fixture.prepare();
            var animation = fixture.animations.get("gui");
            first.close();
            second.close();
            fixture.tick(1200);
            assertEquals(1, fixture.store.boundProgramCount());
            assertEquals(AnimationStore.State.READY, fixture.animations.state("gui"));
            try (var reacquired = fixture.acquire(); var firstRuntime = fixture.runtime()) {
                assertSame(fixture.target, assertInstanceOf(AcquireResult.Ready.class, reacquired.poll()).target());
                assertSame(original, fixture.prepare());
                assertSame(animation, fixture.animations.get("gui"));
                assertEquals(1F, fixture.evaluate(firstRuntime));
                assertEquals(2F, fixture.evaluate(firstRuntime));
            }
            fixture.tick(1200);
            try (var runtime = fixture.runtime()) {
                assertEquals(1F, fixture.evaluate(runtime), "Private variables start fresh with each installation");
            }
            assertSame(original, fixture.prepare());
        }
    }

    @Test
    void independentBatchOwnsOneUseAndKeepsProgramsAfterClose(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root)) {
            var original = fixture.prepare();
            try (var batch = fixture.animations.openReadPhase()) {
                assertEquals(1, fixture.store.activeUses());
                assertNotNull(batch.animations().get("gui"));
                assertNotNull(batch.animations().get("world_only"));
                assertEquals(1, fixture.store.activeUses());
            }
            assertEquals(0, fixture.store.activeUses());
            assertSame(original, fixture.prepare());
            assertEquals(AnimationStore.State.READY, fixture.animations.state("world_only"));
        }
    }

    @Test
    void enteredReadCallbackDefersStoreCleanupAfterBatchClose(@TempDir Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try (var fixture = new Fixture(root, name -> {
            entered.countDown();
            assertTrue(release.await(20, TimeUnit.SECONDS));
            return new com.elfmcys.ysm.geckolib3.core.builder.Animation(name, 0,
                com.elfmcys.ysm.geckolib3.core.builder.LoopType.PLAY_ONCE, null, List.of(), List.of(), List.of());
        }); var batch = fixture.animations.openReadPhase()) {
            var original = fixture.prepare();
            var read = workers.submit(() -> batch.animations().get("gui"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            workers.submit(() -> {
                batch.close();
                fixture.store.close();
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.store.activeUses());
            assertFalse(fixture.store.isClosed());
            assertEquals(1, fixture.store.boundProgramCount());
            assertSame(fixture.store, resourceField(batch, "source"));
            assertThrows(IllegalStateException.class, () -> batch.animations().get("world_only"));
            assertThrows(IllegalStateException.class, fixture.store::prepare);
            release.countDown();
            assertNotNull(read.get(5, TimeUnit.SECONDS));
            assertTrue(fixture.store.isClosed());
            assertEquals(0, fixture.store.activeUses());
            assertEquals(0, fixture.store.boundProgramCount());
            assertNull(resourceField(batch, "source"));
            assertNull(resourceField(batch, "gates"));
            assertNull(resourceField(batch.animations(), "store"));
            assertNull(resourceField(batch.animations(), "gates"));
            assertThrows(IllegalStateException.class, () -> batch.animations().controllers());
            assertNotNull(original);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void acceptedExecutionCanPrepareFunctionsAndReadAfterSealWithoutAnotherUse(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root); var execution = fixture.open()) {
            var environment = execution.interpreterUse().environment();
            var id = execution.requireFloatProgram(GuiPlayerAnimationCacheTest.sample()).binding().programId();
            fixture.store.close();
            assertThrows(IllegalStateException.class, fixture.store::prepare);
            assertThrows(IllegalStateException.class, fixture::open);
            assertThrows(IllegalStateException.class, fixture.animations::openReadPhase);
            var program = execution.interpreterUse().program(id);
            assertEquals(1, fixture.store.activeUses());
            try (var batch = execution.openReadPhase(fixture.animations)) {
                assertNotNull(batch.animations().get("world_only"));
                assertEquals(1, fixture.store.activeUses(), "Nested batches reuse the accepted execution use");
            }
            assertSame(program, execution.interpreterUse().program(id));
            assertNotNull(environment.prepare(program.source(), () -> false));
            execution.close();
            assertTrue(fixture.store.isClosed());
            assertThrows(IllegalStateException.class, () -> environment.prepare(program.source(), () -> false));
        }
    }

    @Test
    void retiringStoreKeepsAnAcceptedAotInstanceAlive(@TempDir Path root) throws Exception {
        var factory = GuiPlayerAnimationCacheTest.factory(root, new ExpressionEngineMetrics());
        try (var store = GuiPlayerAnimationCacheTest.full(factory, GuiPlayerAnimationCacheTest.definition());
            var execution = store.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER)) {
            var instance = execution.aotInstance();
            store.close();
            assertFalse(store.isClosed());
            assertThrows(IllegalStateException.class,
                () -> store.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER));
            assertSame(instance, execution.aotInstance());
            assertNotNull(instance.identity());
            execution.close();
            assertTrue(store.isClosed());
        }
    }

    @Test
    void acceptedPreparationCanBindAndAssembleAfterSeal(@TempDir Path root) throws Exception {
        var factory = GuiPlayerAnimationCacheTest.factory(root, new ExpressionEngineMetrics());
        try (var store = GuiPlayerAnimationCacheTest.rawGui(factory, GuiPlayerAnimationCacheTest.definition(), false);
            var preparation = store.prepare()) {
            store.close();
            assertFalse(store.isClosed());
            var index = preparation.animations(List.of("main"));
            assertNotNull(preparation.requireFloatProgram(GuiPlayerAnimationCacheTest.sample()));
            preparation.initialize(index, null, Map.of(), Map.of(), Map.of());
            assertEquals(3, store.view().keySet().size());
            assertThrows(IllegalStateException.class, store::prepare);
            preparation.close();
            assertTrue(store.isClosed());
        }
    }

    @Test
    void idleRetirementClearsProgramsAndDefinitionsWithoutAnotherTick(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root)) {
            fixture.prepare();
            assertNotNull(fixture.animations.get("gui"));
            fixture.store.close();
            assertTrue(fixture.store.isClosed());
            assertEquals(0, fixture.store.boundProgramCount());
            assertTrue(fixture.animations.keySet().isEmpty());
        }
    }

    private static Object resourceField(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class Fixture implements AutoCloseable {
        final AnimationStore store;
        final AnimationStore.View animations;
        final ClientResourceScheduler scheduler = new ClientResourceScheduler(2);
        final ModelRenderTargetCache cache =
            new ModelRenderTargetCache(scheduler, ModelRenderTarget::close, failure -> {
                throw new AssertionError(failure);
            }, textures -> {
                throw new AssertionError("Texture publication is not needed by this fixture");
            }, () -> 4, () -> 4);
        final ModelContent content;
        final ModelRenderTarget target;
        final ResourceRequest request;
        final RenderTargetKey key;

        Fixture(Path root) throws Exception {
            this(root, null);
        }

        Fixture(Path root, AnimationStore.AnimationLoader custom) throws Exception {
            var factory = GuiPlayerAnimationCacheTest.factory(root, new ExpressionEngineMetrics());
            store = GuiPlayerAnimationCacheTest.rawGui(factory, definition(), false);
            try (var preparation = store.prepare()) {
                preparation.initialize(custom == null ? preparation.animations(List.of("main"))
                    : AnimationStore.Index.lazy(List.of("gui"), custom), null, Map.of(), Map.of(), Map.of());
            }
            animations = store.view();
            var id = ModelId.fromHash(GuiPlayerAnimationCacheTest.CONTAINER);
            var identity = new ModelFileIdentity(id, GuiPlayerAnimationCacheTest.CONTAINER);
            var representation =
                (ModelRepresentation)UnsafeUtil.getUnsafe().allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            UnsafeUtil.getUnsafe().putObject(representation, UnsafeUtil.getUnsafe().objectFieldOffset(field), identity);
            content = new ModelContent() {
                public ModelRepresentation representation() {
                    return representation;
                }

                public ChunkDataSource chunks() {
                    return null;
                }
            };
            var assets = new CommonAsset(Map.of(), TargetSoundResources.empty(identity));
            var variants = new FifoHashMap<>(new String[] {"default"},
                new PlayerModelVariant[] {new PlayerModelVariant(UnrenderedGeometry.create(), null)});
            var resources = new PlayerModelResources(variants, animations, AnimationStore.emptyView(),
                new ConditionManager(), new FPArmConditionManager(), "default");
            target = new ModelRenderTarget(id, "player", resources, assets, store,
                (ModelInfoView)UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
            request = new ResourceRequest(id, "player", "", new BakeProfile("interpreter-residency"),
                store.expressionBinding(), ModelResourcePurpose.GUI);
            key = new RenderTargetKey(request.targetId(), request.requestedTexture(), request.bakeProfile(),
                request.expressionBinding(), request.purpose());
        }

        ResourceLease acquire() {
            return cache.getOrStart(request, content, key,
                (cancelled,
                    dispatch) -> CompletableFuture.completedFuture(new ModelRenderTargetLoader.LoadResult.Ready(
                        ModelRenderTargetLoader.ModelCandidate.testing(target))),
                (identity, selected, asked) -> true, this, WorkPriority.BACKGROUND);
        }

        AnimationStore.Execution open() {
            return store.openExecution(TestComponentHolders.linkageScope(), RoamingRole.PRODUCER);
        }

        InterpreterProgram prepare() throws Exception {
            try (var use = open()) {
                return use.interpreterUse()
                    .program(use.requireFloatProgram(GuiPlayerAnimationCacheTest.sample()).binding().programId());
            }
        }

        EntityExpressionRuntime runtime() {
            var runtime = new EntityExpressionRuntime();
            runtime.install(runtime.prepare(store, TestComponentHolders.linkageScope(), RoamingRole.PRODUCER), () -> {
            });
            return runtime;
        }

        float evaluate(EntityExpressionRuntime runtime) {
            return runtime.observe(
                new EntityExpressionRuntime.InvocationInputs(
                    new com.elfmcys.ysm.expression.host.InvocationInputs<net.minecraft.world.entity.Entity>() {}),
                evaluation -> evaluation.evaluate(
                    (com.elfmcys.ysm.expression.aot.FloatExpression.Program)animations.get("gui").blendWeight));
        }

        static com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto definition() throws Exception {
            var program =
                com.elfmcys.ysm.expression.EncodedProgramMapper
                    .toProto(new com.elfmcys.ysm.expression.frontend.MolangFrontend()
                        .compile("v.sample=v.sample+1;return v.sample;",
                            com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceMode.EXPRESSION,
                            new com.elfmcys.ysm.expression.frontend.MolangFrontend.SourceLocation("residency"))
                        .program());
            var original = GuiPlayerAnimationCacheTest.definition();
            var file = com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto.newBuilder();
            for (var animation : original.animationFiles().get("main").animations())
                file.addAnimations(animation.toBuilder()
                    .setBlendWeight(com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto.newBuilder()
                        .setProgram(program).build())
                    .build());
            return original.toBuilder().putAnimationFiles("main", file.build()).build();
        }

        void tick(int count) {
            for (int index = 0; index < count; index++) {
                cache.tick();
            }
        }

        @Override
        public void close() throws InterruptedException {
            try {
                cache.close();
                target.close();
            } finally {
                scheduler.shutdown();
                scheduler.awaitTermination();
            }
        }
    }

    private static final class UnrenderedGeometry extends GeoModel {
        private UnrenderedGeometry() {
            super(null, null, null, null);
        }

        static UnrenderedGeometry create() throws InstantiationException {
            return (UnrenderedGeometry)UnsafeUtil.getUnsafe().allocateInstance(UnrenderedGeometry.class);
        }

        @Override
        public void close() {}
    }
}
