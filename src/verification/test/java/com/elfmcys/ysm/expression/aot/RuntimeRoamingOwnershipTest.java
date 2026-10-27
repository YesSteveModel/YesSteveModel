package com.elfmcys.ysm.expression.aot;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.expression.host.MolangContext;
import com.elfmcys.ysm.expression.host.EffectCommand;
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
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeRoamingOwnershipTest {
    @Test
    void snapshotsAndArmFullLeaveBodyDirtyAndSameValueCreatesAnotherLocalBatch(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            var body = fixture.body;
            var arm = fixture.arm;
            var batches = new ArrayList<Map<String, Float>>();
            body.action(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            assertEquals(Map.of("shared", 7F, "body_only", 9F), body.snapshot().orElseThrow().payload().values());
            assertTrue(body.hasRoamingDelta());
            arm.connectArmTo(body);
            assertEquals(7F, arm.roamingState().orElseThrow().read("shared"));
            assertEquals(0F, arm.roamingState().orElseThrow().read("arm_only"));
            assertTrue(body.hasRoamingDelta());
            body.normalUpdate(inputs(), evaluation -> null, delta -> batches.add(delta.values()));
            assertEquals(Map.of("shared", 7F, "body_only", 9F), batches.get(0));
            assertFalse(body.hasRoamingDelta());

            arm.update(inputs(), evaluation -> evaluation.evaluate(fixture.armProgram));
            assertEquals(99F, arm.roamingState().orElseThrow().read("shared"));
            assertEquals(11F, arm.roamingState().orElseThrow().read("arm_only"));
            assertThrows(IllegalStateException.class, () -> arm.roamingState().orElseThrow().hasDelta());
            assertEquals(7F, body.snapshot().orElseThrow().payload().values().get("shared"));
            body.action(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram));
            var before = body.snapshot().orElseThrow();
            assertTrue(body.hasRoamingDelta());
            body.normalUpdate(inputs(), evaluation -> null, delta -> batches.add(delta.values()));
            assertEquals(2, batches.size());
            assertEquals(batches.get(0), batches.get(1));
            assertEquals(7F, arm.roamingState().orElseThrow().read("shared"));
            assertEquals(11F, arm.roamingState().orElseThrow().read("arm_only"));
            assertEquals(before.payload(), body.snapshot().orElseThrow().payload());
            assertThrows(IllegalStateException.class, () -> arm.connectArmTo(body));
        }
    }

    @Test
    void busyArmDropsOnePayloadButPreservesBodyOnlyProjectionAndFutureAssignment(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            fixture.arm.connectArmTo(fixture.body);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var busy = CompletableFuture.runAsync(() -> fixture.arm.update(inputs(), evaluation -> {
                evaluation.evaluate(fixture.armProgram);
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var projection = new ArrayList<Map<String, Float>>();
            fixture.body.normalUpdate(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram),
                delta -> projection.add(delta.values()));
            assertEquals(Map.of("shared", 7F, "body_only", 9F), projection.get(0));
            assertFalse(fixture.body.hasRoamingDelta());
            release.countDown();
            busy.get(10, TimeUnit.SECONDS);
            assertEquals(99F, fixture.arm.roamingState().orElseThrow().read("shared"));
            fixture.body.normalUpdate(inputs(), evaluation -> null, delta -> fail("old payload retried"));
            assertEquals(99F, fixture.arm.roamingState().orElseThrow().read("shared"));
            fixture.body.normalUpdate(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram),
                delta -> projection.add(delta.values()));
            assertEquals(2, projection.size());
            assertEquals(7F, fixture.arm.roamingState().orElseThrow().read("shared"));
        }
    }

    @Test
    void busyBodyInitializationHasOneAttemptAndNormalDeltaIsIndependent(@TempDir Path root) throws Exception {
        try (var fixture = fixture(root)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var busy = CompletableFuture.runAsync(() -> fixture.body.action(inputs(), evaluation -> {
                evaluation.evaluate(fixture.bodyProgram);
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            fixture.arm.connectArmTo(fixture.body);
            assertEquals(0F, fixture.arm.roamingState().orElseThrow().read("shared"));
            release.countDown();
            busy.get(10, TimeUnit.SECONDS);
            assertEquals(0F, fixture.arm.roamingState().orElseThrow().read("shared"));
            fixture.body.normalUpdate(inputs(), evaluation -> null, delta -> assertEquals(2, delta.values().size()));
            assertEquals(7F, fixture.arm.roamingState().orElseThrow().read("shared"));
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
            fixture.body.normalUpdate(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram), delta -> {
            });
            assertEquals(0F, fixture.arm.roamingState().orElseThrow().read("shared"));

            TestExpressionModules.replace(fixture.arm, fixture.armModule, Map.of(), RoamingRole.CONSUMER);
            fixture.arm.connectArmTo(fixture.body);
            fixture.body.normalUpdate(inputs(), evaluation -> evaluation.evaluate(fixture.bodyProgram),
                delta -> fixture.arm.clear());
            assertTrue(fixture.arm.module().isEmpty());
            assertFalse(fixture.body.hasRoamingDelta());
            assertEquals(9F, fixture.body.snapshot().orElseThrow().payload().values().get("body_only"));
        }
    }

    private static Fixture fixture(Path root) throws Exception {
        var host = ExpressionHost.create(HostAvailability.none());
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(root, track(new AtomicSharedCache(root)))), host,
            new ExpressionEngineMetrics());
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("main", animation("r.shared=7; r.body_only=9; return r.shared;"))
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
        return new EntityExpressionRuntime.InvocationInputs(new InvocationInputs() {
            @Override
            public void prevalidateEffect(EffectCommand command, MolangContext<?> scope) {}
        }, (commands, provenance) -> () -> {
        }, () -> false);
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
            body.initializeRoaming(new RoamingPayload(new TreeMap<>()));
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
