package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.expression.aot.RenderTargetExpressionModuleFactory;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.util.ProtoUtil;
import com.elfmcys.ysm.util.StringPool;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import com.elfmcys.ysm.api.molang.MolangActionView;
import com.elfmcys.ysm.client.animation.physics.PhysicsManager;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.processor.BoneView;
import com.elfmcys.ysm.geckolib3.core.processor.MinecraftRuntimeInputs;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import dev.semaweave.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public operations with real owner-local algorithms; no Forge world/thread claim. */
class PublicCapabilitiesTest {
    @Test
    void sharedRandomAndSecondOrderKeepEachInvocationAndOwnerAcrossColdWarm(@TempDir Path cache) throws Exception {
        ComponentScopeTest.initializeMinecraftEntityClasses();
        var frontend = new MolangFrontend();
        var encoded =
            EncodedProgramMapper
                .toProto(frontend
                    .compile("v.draw=math.random(0,1);return v.draw+ysm.second_order('spring',4,1,1,1);",
                        MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("shared-state"))
                    .program());
        var animation = AnimationProto.newBuilder().setName("idle").setLength(0).setLoop(LoopType.PLAY_ONCE)
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(encoded).build()).build();
        var definition = ModelDataProto.newBuilder().putAnimationFiles("main", AnimationFileProto.newBuilder()
            .addAnimations(animation).addAnimations(animation.toBuilder().setName("copy").build()).build()).build();
        var settings = SettingsProto.newBuilder().build();
        var model = new ModelFileIdentity(new Hash256(new byte[32]), new Hash256(new byte[32]));
        var firstLocation = new ModelProgramInventory.SemanticLocation("player", "animation/main/idle/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var copyLocation = new ModelProgramInventory.SemanticLocation("player", "animation/main/copy/blend-weight",
            ProgramRole.ANIMATION_SAMPLE, "");
        var host = ExpressionHost.create(HostAvailability.none());
        for (boolean warm : new boolean[] {false, true}) {
            var shared = com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new AtomicSharedCache(cache));
            var store =
                com.elfmcys.ysm.testutil.DerivedStorageTestScope.track(new ExpressionArtifactStore(cache, shared));
            try (
                var module = new RenderTargetExpressionModuleFactory(store, host, new ExpressionEngineMetrics()).create(
                    () -> false, model, "player", "main", new BakeProfile("shared-state"), false, definition, null,
                    settings, ProtoUtil.serializeToArray(settings));
                var runtimeA = new EntityExpressionRuntime(); var runtimeB = new EntityExpressionRuntime()) {
                assertEquals(warm ? ExpressionArtifactStore.Source.DISK : ExpressionArtifactStore.Source.COLD,
                    module.source());
                assertTrue(module.compilationReport().replacements().isEmpty(), module.compilationReport().toString());
                assertTrue(module.compilationReport().localRecoveries().isEmpty(),
                    module.compilationReport().toString());
                assertEquals(module.requireProgram(firstLocation).entryId(),
                    module.requireProgram(copyLocation).entryId());
                var a = new Inputs();
                var b = new Inputs();
                a.subject = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
                    .allocateInstance(net.minecraft.world.entity.decoration.ArmorStand.class);
                b.subject = (Entity)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
                    .allocateInstance(net.minecraft.world.entity.decoration.ArmorStand.class);
                runtimeA.replace(module, Map.of(),
                    BuiltinDeclarations.createComponents(TestComponentHolders.owner(a.subject), host));
                runtimeB.replace(module, Map.of(),
                    BuiltinDeclarations.createComponents(TestComponentHolders.owner(b.subject), host));
                var oracleA = RandomSource.create(41);
                var oracleB = RandomSource.create(41);
                var first = module.requireFloatProgram(firstLocation);
                var copy = module.requireFloatProgram(copyLocation);
                var inputsA = invocation(a);
                var inputsB = invocation(b);
                assertEquals(oracleA.nextFloat() + 4, runtimeA.<Float>observe(inputsA, e -> e.evaluate(first)));
                assertEquals(oracleA.nextFloat(), runtimeA.<Float>observe(inputsA, e -> e.evaluate(copy)));
                assertEquals(oracleB.nextFloat() + 4, runtimeB.<Float>observe(inputsB, e -> e.evaluate(first)));
                int key = StringPool.computeIfAbsent("spring");
                var physicsA = a.model.physics.get(key);
                var physicsB = b.model.physics.get(key);
                assertNotSame(physicsA, physicsB);
                physicsA.update(0.05f);
                physicsA.update(0.05f);
                // Two explicit Euler steps from zero give this closed-form position for the fixed coefficients.
                float position = (float)(0.4 * Math.PI + 0.04 * Math.PI * Math.PI);
                assertEquals(position, physicsA.getValue(), 0.00001f);
                assertEquals(oracleA.nextFloat() + position, runtimeA.observe(inputsA, e -> e.evaluate(copy)),
                    0.00001f);
                assertEquals(oracleB.nextFloat(), runtimeB.<Float>observe(inputsB, e -> e.evaluate(copy)));
                assertEquals(3, a.randomAccesses);
                assertEquals(2, b.randomAccesses);
                store.awaitPersistence();
            } finally {
                com.elfmcys.ysm.testutil.DerivedStorageTestScope.drain();
            }
        }
    }

    private static EntityExpressionRuntime.InvocationInputs invocation(Inputs input) {
        return new EntityExpressionRuntime.InvocationInputs(input, (commands, provenance) -> () -> {
        }, () -> false);
    }

    @Test
    void readsUseOwnerLocalRandomPhysicsAndDetachedBoneValues() throws Exception {
        var first = new Inputs();
        var second = new Inputs();
        var current = new AtomicBoolean(true);
        var state = state(first, current, false);
        var a = state;
        var b = state(second, new AtomicBoolean(true), false);
        try {
            var oracle = RandomSource.create(41);
            assertEquals(oracle.nextFloat(), a.random());
            assertEquals(oracle.nextFloat(), a.random());
            assertEquals(RandomSource.create(41).nextFloat(), b.random());
            assertEquals(2f, a.firstOrder("spring", 2, 1));
            assertEquals(9f, b.firstOrder("spring", 9, 1));
            assertEquals(4f, a.secondOrder("other", 4, 1, 1, 1));
            assertNotSame(first.model.physics.get(StringPool.computeIfAbsent("spring")),
                second.model.physics.get(StringPool.computeIfAbsent("spring")));
            var vector = a.boneRotation("root");
            assertEquals(-90f, vector.x(), 0.0001f);
            assertEquals(0f, vector.y(), 0.0001f);
            assertEquals(180f, vector.z(), 0.0001f);
            first.rotation = 0;
            assertEquals(-90f, vector.x(), 0.0001f);
            assertEquals(0f, a.boneRotation("root").x(), 0.0001f);
            assertEquals(new MolangVector(0, 0, 0), a.boneRotation("missing"));
            current.set(false);
            assertThrows(ExecutionFailure.class, state::verifyEligible);
            assertFalse(a.actionAllowed());
        } finally {
            end(a);
            end(b);
        }
        var pure = state(second, new AtomicBoolean(true), true);
        try {
            assertDoesNotThrow(pure::random);
            assertDoesNotThrow(() -> pure.firstOrder("spring", 1));
            assertDoesNotThrow(() -> pure.boneRotation("root"));
        } finally {
            end(pure);
        }
    }

    @Test
    void everyActionCategorySharesPermissionPrevalidationAndBatch() throws Exception {
        List<Consumer<MolangActionView<Entity>>> requests = List.of(c -> c.playSound("voice", "clip", 0, 1, 1),
            c -> c.stopSound("voice", false), c -> c.stopAllSounds(false), c -> c.particle("smoke", 1),
            c -> c.absoluteParticle("smoke", 1), c -> c.boneColor("root", 1, 1, 1), c -> c.boneGlow("root", 1),
            c -> c.boneTransparency("root", 1), MolangActionView::resetController, c -> c.setAnimation("idle", 1),
            c -> c.sync(1, 2), c -> c.defer(1, 2f));
        var inputs = new Inputs();
        var state = state(inputs, new AtomicBoolean(true), true);
        state.endTopLevel();
        state.beginTopLevel(false);
        try {
            for (var request : requests) {
                assertThrows(IllegalStateException.class, () -> request.accept(state));
                assertEquals(0, state.effects().orElseThrow().size());
            }
            state.endTopLevel();
            state.beginTopLevel(true);
            inputs.reject = true;
            for (var request : requests)
                assertEquals(inputs.failure, assertThrows(IllegalStateException.class, () -> request.accept(state)));
            assertEquals(0, state.effects().orElseThrow().size());
            inputs.reject = false;
            inputs.commands.clear();
            requests.forEach(request -> request.accept(state));
            assertEquals(requests.size(), state.effects().orElseThrow().size());
            assertTrue(inputs.commands.stream().anyMatch(EffectCommand.Sound.class::isInstance));
            assertTrue(inputs.commands.stream().anyMatch(EffectCommand.Particle.class::isInstance));
            assertTrue(inputs.commands.stream().anyMatch(EffectCommand.Controller.class::isInstance));
            assertTrue(inputs.commands.stream().anyMatch(EffectCommand.Sync.class::isInstance));
            assertTrue(inputs.commands.stream().anyMatch(EffectCommand.Deferred.class::isInstance));
            int count = state.effects().orElseThrow().size();
            assertThrows(IllegalArgumentException.class, () -> state.sync(Float.NaN));
            assertEquals(count, state.effects().orElseThrow().size());
        } finally {
            end(state);
        }
    }

    private static void end(MolangContext<?> context) {
        context.endTopLevel();
        context.releaseExpression();
        context.closeRound();
    }

    private static MolangContext<Entity> state(Inputs inputs, AtomicBoolean current, boolean action) {
        var roaming = com.elfmcys.ysm.expression.TestRoamingStates.create(null, RoamingRole.PRODUCER);
        var provenance = new InvocationProvenance("public", "capability", 0, 1, 1);
        var context = action
            ? MolangContext.logical(inputs, inputs, MolangContext.LogicalPhase.ACTION, inputs, roaming, provenance,
                () -> false, current::get, 32)
            : MolangContext.observation(inputs, inputs, inputs, roaming, provenance, () -> false, current::get);
        context.bindExpression("capability", 0);
        context.beginTopLevel(action);
        return context;
    }

    private static final class Inputs implements MinecraftRuntimeInputs<Entity> {
        final Model model;
        final RandomSource random = RandomSource.create(41);
        final List<EffectCommand> commands = new ArrayList<>();
        final IllegalStateException failure = new IllegalStateException("host-rejected");
        boolean reject;
        int randomAccesses;
        Entity subject;
        float rotation = (float)(Math.PI / 2);

        Inputs() throws InstantiationException {
            // Avoid unrelated renderer/game startup while retaining the real physics algorithms.
            model = (Model)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe().allocateInstance(Model.class);
            model.physics = new PhysicsManager();
            model.inputs = this;
        }

        public Entity entity() {
            return subject;
        }

        public AnimatableEntity<?> animatable() {
            return model;
        }

        public AnimationEvent<?> animationEvent() {
            return null;
        }

        public RandomSource randomSource() {
            randomAccesses++;
            return random;
        }

        public ControllerExecutionState controllerState() {
            return null;
        }

        public AnimationExecutionState animationState() {
            return null;
        }

        public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {
            if (reject)
                throw failure;
            commands.add(command);
        }
    }

    private static final class Model extends AnimatableEntity<Entity> {
        PhysicsManager physics;
        Inputs inputs;

        private Model() {
            super(null);
        }

        public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
            return physics;
        }

        public BoneView getBone(int id) {
            if (id != StringPool.computeIfAbsent("root"))
                return null;
            return (BoneView)Proxy.newProxyInstance(BoneView.class.getClassLoader(), new Class<?>[] {BoneView.class},
                (o, method, args) -> switch (method.getName()) {
                    case "getRotationX" -> inputs.rotation;
                    case "getRotationY" -> 0f;
                    case "getRotationZ" -> (float)Math.PI;
                    default -> throw new AssertionError(method);
                });
        }

        public net.minecraft.resources.ResourceLocation getTextureLocation() {
            return null;
        }

        public float getWidthScale() {
            return 1;
        }

        public float getHeightScale() {
            return 1;
        }

        public com.elfmcys.ysm.geckolib3.core.builder.Animation getAnimation(String name) {
            return null;
        }

        protected void prepareAnimationControllers(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {}

        protected com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel
            resolveGeoModel(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target, String texture) {
            throw new AssertionError("No asset supplied");
        }
    }
}
