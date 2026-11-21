package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.model.resource.client.render.AnimationInputStore;

import static com.elfmcys.ysm.expression.TestRoamingPayloads.named;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.host.InvocationInputs;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.format.parser.AnimationBuilder;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import dev.semaweave.RoamingPayload;
import dev.semaweave.RoamingRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeRoamingOwnershipTest {
    @Test
    void numericSelectionMappingAppliesToTheRealGeneratedConsumer(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            var dictionary = new com.elfmcys.ysm.network.protocol.RoamingDictionary(new String[] {"unknown", "shared"});
            var mapping = dictionary.mapTo(fixture.armModule.roamingFields());
            fixture.arm.initializeRoaming(mapping
                .decode(new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {0, 1}, new float[] {99, 2})));
            var state = fixture.arm.roamingState().orElseThrow();
            assertEquals(2F, state.read((short)state.fieldNames().indexOf("shared")));
            assertEquals(0F, state.read((short)state.fieldNames().indexOf("arm_only")));
            state.write((short)state.fieldNames().indexOf("arm_only"), 8F);
            fixture.arm.applyRoamingPatch(
                mapping.decode(new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {1}, new float[] {-0F})));
            assertEquals(8F, state.read((short)state.fieldNames().indexOf("arm_only")));
            assertEquals(0x80000000, Float.floatToRawIntBits(state.read((short)state.fieldNames().indexOf("shared"))));
            fixture.arm.applyRoamingFull(
                mapping.decode(new com.elfmcys.ysm.network.protocol.RoamingValues(new short[] {1}, new float[] {5F})));
            assertEquals(0F, state.read((short)state.fieldNames().indexOf("arm_only")));
            assertEquals(5F, state.read((short)state.fieldNames().indexOf("shared")));
        }
    }

    @Test
    void observationAndWatchWritesWaitForNormalConsumptionAndKeepActionArgumentsBlocked(@TempDir Path root)
        throws Exception {
        try (var fixture = fixture(root)) {
            fixture.arm.connectArmTo(fixture.body);
            float bodyValue = fixture.body.observe(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            assertEquals(7F, bodyValue);
            assertEquals(Map.of("shared", 7F, "body_only", 9F), named(fixture.body.snapshot().orElseThrow()));
            assertTrue(fixture.body.hasRoamingDelta());
            assertEquals(0F, fixture.arm.roamingState().orElseThrow()
                .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));

            try (var watch = fixture.body.compileTransient(fixture.body.generation(),
                "r.shared=8; ysm.sync(r.body_only=123); return r.shared;",
                TransientExpressionSession.Privilege.LOCAL_WATCH, TransientExpressionSession.Purpose.WATCH,
                "test:roaming-watch", () -> false)) {
                assertEquals(8F, fixture.body.executeTransient(inputs(), watch));
            }
            assertEquals(9F, fixture.body.roamingState().orElseThrow()
                .read((short)fixture.body.roamingState().orElseThrow().fieldNames().indexOf("body_only")));
            assertTrue(fixture.body.hasRoamingDelta());
            assertEquals(0F, fixture.arm.roamingState().orElseThrow()
                .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));

            var batches = new ArrayList<Map<String, Float>>();
            round(fixture.body, evaluation -> null,
                delta -> batches.add(named(delta, fixture.bodyModule.roamingFields())));
            assertEquals(java.util.List.of(Map.of("shared", 8F, "body_only", 9F)), batches);
            assertEquals(8F, fixture.arm.roamingState().orElseThrow()
                .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
            assertFalse(fixture.body.hasRoamingDelta());
            float armValue = fixture.arm.observe(inputs(), evaluation -> evaluation.evaluate(fixture.armProgram));
            assertEquals(99F, armValue);
            assertThrows(IllegalStateException.class, () -> fixture.arm.roamingState().orElseThrow().hasChanges());
            assertEquals(8F, fixture.body.roamingState().orElseThrow()
                .read((short)fixture.body.roamingState().orElseThrow().fieldNames().indexOf("shared")));
        }
    }

    @Test
    void snapshotsAndArmFullLeaveBodyDirtyAndSameValueCreatesAnotherLocalBatch(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            var body = fixture.body;
            var arm = fixture.arm;
            var batches = new ArrayList<Map<String, Float>>();
            body.action(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            assertEquals(Map.of("shared", 7F, "body_only", 9F), named(body.snapshot().orElseThrow()));
            assertTrue(body.hasRoamingDelta());
            arm.connectArmTo(body);
            assertEquals(7F, arm.roamingState().orElseThrow()
                .read((short)arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
            assertEquals(0F, arm.roamingState().orElseThrow()
                .read((short)arm.roamingState().orElseThrow().fieldNames().indexOf("arm_only")));
            assertTrue(body.hasRoamingDelta());
            round(body, evaluation -> null, delta -> batches.add(named(delta, fixture.bodyModule.roamingFields())));
            assertEquals(Map.of("shared", 7F, "body_only", 9F), batches.get(0));
            assertFalse(body.hasRoamingDelta());

            arm.update(inputs(), evaluation -> evaluation.evaluate(fixture.armProgram));
            assertEquals(99F, arm.roamingState().orElseThrow()
                .read((short)arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
            assertEquals(11F, arm.roamingState().orElseThrow()
                .read((short)arm.roamingState().orElseThrow().fieldNames().indexOf("arm_only")));
            assertThrows(IllegalStateException.class, () -> arm.roamingState().orElseThrow().hasChanges());
            assertEquals(7F, named(body.snapshot().orElseThrow()).get("shared"));
            body.action(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            var before = body.snapshot().orElseThrow();
            assertTrue(body.hasRoamingDelta());
            round(body, evaluation -> null, delta -> batches.add(named(delta, fixture.bodyModule.roamingFields())));
            assertEquals(2, batches.size());
            assertEquals(batches.get(0), batches.get(1));
            assertEquals(7F, arm.roamingState().orElseThrow()
                .read((short)arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
            assertEquals(11F, arm.roamingState().orElseThrow()
                .read((short)arm.roamingState().orElseThrow().fieldNames().indexOf("arm_only")));
            assertEquals(named(before), named(body.snapshot().orElseThrow()));
            assertThrows(IllegalStateException.class, () -> arm.connectArmTo(body));
        }
    }

    @Test
    void targetMustBeJoinedBeforeThePendingProjectionIsAdopted(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            fixture.arm.connectArmTo(fixture.body);
            boolean[] joined = {false};
            fixture.arm.projectionAvailability(() -> joined[0]);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var busy = CompletableFuture.runAsync(() -> fixture.arm.update(inputs(), evaluation -> {
                evaluation.evaluate(fixture.armProgram);
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var projection = new ArrayList<Map<String, Float>>();
                round(fixture.body, evaluation -> evaluation.evaluate(fixture.bodyProgram),
                    delta -> projection.add(named(delta, fixture.bodyModule.roamingFields())));
                assertEquals(Map.of("shared", 7F, "body_only", 9F), projection.get(0));
                assertFalse(fixture.body.hasRoamingDelta());
                release.countDown();
                busy.get(10, TimeUnit.SECONDS);
                // Actual execution has ended, but the owner has not opened the target window.
                fixture.body.adoptProjections(false);
                assertEquals(99F, fixture.arm.roamingState().orElseThrow()
                    .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
                joined[0] = true;
                fixture.body.adoptProjections(true);
                assertEquals(7F, fixture.arm.roamingState().orElseThrow()
                    .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
                fixture.body.adoptProjections(true);
                assertEquals(1, projection.size());
                assertEquals(11F, fixture.arm.roamingState().orElseThrow()
                    .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("arm_only")));
            } finally {
                release.countDown();
                busy.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void armConnectionRequiresTheBodyHandoffAndThenCopiesTheFullIntersection(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            boolean[] joined = {false};
            fixture.body.projectionAvailability(() -> joined[0]);
            assertThrows(IllegalStateException.class, () -> fixture.arm.connectArmTo(fixture.body));
            joined[0] = true;
            fixture.body.action(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            fixture.arm.connectArmTo(fixture.body);
            assertEquals(7F, fixture.arm.roamingState().orElseThrow()
                .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));
            assertEquals(0F, fixture.arm.roamingState().orElseThrow()
                .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("arm_only")));
            assertTrue(fixture.body.hasRoamingDelta());
        }
    }

    @Test
    void retiringEitherExactInstallationCannotApplyOldWorkToAnIdenticalReplacement(@TempDir Path root)
        throws Exception {
        try (var fixture = fixture(root)) {
            fixture.arm.connectArmTo(fixture.body);
            fixture.body.action(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            var sampled = fixture.body.snapshot().orElseThrow();
            var sends = new AtomicInteger();
            assertTrue(sampled.installation().commit(sends::incrementAndGet));
            TestExpressionModules.replace(fixture.body, fixture.bodyModule, Map.of(), RoamingRole.PRODUCER);
            assertFalse(sampled.installation().commit(sends::incrementAndGet));
            assertEquals(1, sends.get());
            round(fixture.body, evaluation -> evaluation.evaluate(fixture.bodyProgram), delta -> {
            });
            assertEquals(0F, fixture.arm.roamingState().orElseThrow()
                .read((short)fixture.arm.roamingState().orElseThrow().fieldNames().indexOf("shared")));

            TestExpressionModules.replace(fixture.arm, fixture.armModule, Map.of(), RoamingRole.CONSUMER);
            fixture.arm.connectArmTo(fixture.body);
            round(fixture.body, evaluation -> evaluation.evaluate(fixture.bodyProgram), delta -> fixture.arm.clear());
            assertTrue(fixture.arm.metadata().isEmpty());
            assertFalse(fixture.body.hasRoamingDelta());
            assertEquals(9F, named(fixture.body.snapshot().orElseThrow()).get("body_only"));
        }
    }

    private static <T> T round(EntityExpressionRuntime runtime, EntityExpressionRuntime.Body<T> body,
        java.util.function.Consumer<RoamingPayload> projection) {
        runtime.prepareExecution();
        var result = runtime.normalUpdate(inputs(), body, projection);
        runtime.adoptProjections(false);
        return result;
    }

    private static Fixture fixture(Path root) throws Exception {
        var host = ExpressionHost.create(HostAvailability.none());
        var expressionCache = track(new AtomicSharedCache(root));
        var factory = new RenderTargetExpressionModuleFactory(track(new ExpressionArtifactStore(root, expressionCache)),
            new AnimationInputStore(root, expressionCache), host, new ExpressionEngineMetrics());
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("main",
                animation("r.shared=7; r.body_only=9; ysm.sync(r.body_only=123); return r.shared;"))
            .putAnimationFiles("fp_arm", animation("r.shared=99; r.arm_only=11; return r.shared;")).build();
        var bytes = new byte[32];
        bytes[0] = 1;
        var expressionBinding = factory.expressionBinding();
        var main = factory.createSet(() -> false, new Hash256(bytes), "player", ExpressionSet.MAIN, expressionBinding,
            ExpressionSet.MAIN.select(definition), null, SettingsProto.newBuilder().build(), new byte[0], false);
        var arm = factory.createSet(() -> false, new Hash256(bytes), "player", ExpressionSet.FP_ARM, expressionBinding,
            ExpressionSet.FP_ARM.select(definition), null, SettingsProto.newBuilder().build(), new byte[0], false);
        return new Fixture(main, arm);
    }

    private static com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto animation(String expression) {
        var json = new com.google.gson.JsonObject();
        var idle = new com.google.gson.JsonObject();
        idle.addProperty("blend_weight", expression);
        var animations = new com.google.gson.JsonObject();
        animations.add("idle", idle);
        json.add("animations", animations);
        return AnimationBuilder.build(AnimationFile.createGson(false).fromJson(json, AnimationFile.class));
    }

    private static EntityExpressionRuntime.InvocationInputs inputs() {
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs<net.minecraft.world.entity.Entity>() {

        });
    }

    private static final class Fixture implements AutoCloseable {
        private final RenderTargetExpressionModule bodyModule, armModule;
        private final EntityExpressionRuntime body = new EntityExpressionRuntime(), arm = new EntityExpressionRuntime();
        private final FloatExpression.Program bodyProgram, armProgram;

        private Fixture(RenderTargetExpressionModule main, RenderTargetExpressionModule firstPerson) {
            bodyModule = main;
            armModule = firstPerson;
            TestExpressionModules.replace(body, main, Map.of(), RoamingRole.PRODUCER);
            TestExpressionModules.replace(arm, firstPerson, Map.of(), RoamingRole.CONSUMER);
            body.initializeRoaming(com.elfmcys.ysm.expression.TestRoamingPayloads.empty());
            bodyProgram = main.requireFloatProgram(new ModelProgramInventory.SemanticLocation("player",
                ExpressionSet.MAIN, "animation/main/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""));
            armProgram = firstPerson.requireFloatProgram(new ModelProgramInventory.SemanticLocation("player",
                ExpressionSet.FP_ARM, "animation/fp_arm/idle/blend-weight", ProgramRole.ANIMATION_SAMPLE, ""));
        }

        @Override
        public void close() {
            arm.close();
            body.close();
            armModule.close();
            bodyModule.close();
        }
    }
}
