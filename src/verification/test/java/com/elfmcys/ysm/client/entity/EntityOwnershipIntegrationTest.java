package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.testutil.LogCapture;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.AnimationPlayer;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class EntityOwnershipIntegrationTest {
    FirstPersonOwnerIntegrationTest host;
    @BeforeAll static void initialize() throws Exception { FirstPersonOwnerIntegrationTest.initializeHost(); }
    @AfterAll static void restore() throws Exception { FirstPersonOwnerIntegrationTest.restoreHost(); }
    @BeforeEach void create() throws Exception {
        host = new FirstPersonOwnerIntegrationTest();
        host.createHost();
    }
    @AfterEach void release() { host.releaseHost(); }

    @Test
    void logicalSuccessThenExtractFailureJoinsBeforeRetirementAndDoesNotReplayActions() throws Exception {
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create()) {
            var owner = new FirstPersonOwnerIntegrationTest.Timeline(host.player);
            var failure = new AssertionError("extract locator fixture");
            var data = failingExtractData(() -> { throw failure; });
            try {
                owner.install(fixture.model, module);
                FirstPersonOwnerIntegrationTest.field(AnimatableEntity.class, "mainRenderData").set(owner, data);
                FirstPersonOwnerIntegrationTest.startAsync(owner, 0.25f);
                assertSame(failure, assertThrows(AssertionError.class, owner::waitForAsyncUpdate));
                assertEquals(1, owner.actions);
                assertEquals(1, owner.normalUpdates);
                assertFalse(data.modelState.isValid());
                assertNull(data.installation);
                assertNull(FirstPersonOwnerIntegrationTest.field(AnimatableEntity.class, "animationReads").get(owner));
                AnimationParallelTicker.waitAll();
                owner.retire();
                assertEquals(1, owner.actions);
                assertTrue(owner.resources.leases.stream().allMatch(lease -> lease.closes == 1));
                assertNull(owner.produce(0.25f, RenderContext.levelImmutable()));
                assertEquals(1, owner.actions);
            } finally {
                AnimationParallelTicker.waitAll();
                owner.retire();
                data.modelState.close();
            }
        }
    }

    @Test
    void requiredDefaultExtractFailureStillEscapesWithItsOriginalCause() throws Exception {
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create()) {
            var owner = new FirstPersonOwnerIntegrationTest.Timeline(host.player);
            var original = new IllegalStateException("required default extract fixture");
            var data = failingExtractData(() -> { throw original; });
            try {
                var target = owner.resources.target(0, module, fixture.model);
                owner.updateModelHash(target.modelHash());
                DemandFixtures.advance(owner);
                FirstPersonOwnerIntegrationTest.field(AnimatableEntity.class, "mainRenderData").set(owner, data);
                FirstPersonOwnerIntegrationTest.startAsync(owner, 0.25f);
                assertSame(original, assertThrows(IllegalStateException.class, AnimationParallelTicker::waitAll));
                assertFalse(data.modelState.isValid());
                assertEquals(1, owner.actions);
            } finally {
                AnimationParallelTicker.waitAll();
                owner.retire();
                data.modelState.close();
            }
        }
    }

    @Test
    void ordinaryExtractFailureDoesNotEscapeTheInvocationDrain() throws Exception {
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create(Set.of("shared", "body_only"));
            var log = new LogCapture(com.elfmcys.ysm.YesSteveModel.LOGGER)) {
            var batches = new ArrayList<Map<String, Float>>();
            var owner = new FirstPersonOwnerIntegrationTest.Timeline(host.player) {
                @Override protected dev.semaweave.RoamingRole roamingRole() {
                    return dev.semaweave.RoamingRole.PRODUCER;
                }
                @Override protected void onNormalRoamingChanges(dev.semaweave.RoamingPayload changes) {
                    batches.add(changes.values());
                }
            };
            owner.producer = true;
            var original = new IllegalStateException("ordinary extract projection fixture");
            var data = failingExtractData(() -> { throw original; });
            try {
                owner.install(fixture.model, module);
                var roaming = owner.getExpressionRuntime().roamingState().orElseThrow();
                owner.beforeAnimation = () -> {
                    roaming.write("shared", 7);
                    roaming.write("body_only", 9);
                };
                FirstPersonOwnerIntegrationTest.field(AnimatableEntity.class, "mainRenderData").set(owner, data);
                FirstPersonOwnerIntegrationTest.startAsync(owner, 0.25f);
                assertDoesNotThrow(AnimationParallelTicker::waitAll);
                assertEquals(List.of(Map.of("shared", 7f, "body_only", 9f)), batches);
                assertEquals(1, owner.actions);
                assertFalse(data.modelState.isValid());
                assertNull(data.installation);
                var reported = log.events().stream()
                    .filter(event -> event.getMessage().getFormattedMessage().equals("Entity output extraction failed"))
                    .map(org.apache.logging.log4j.core.LogEvent::getThrown).findFirst().orElseThrow();
                assertSame(original, reported.getCause());
                assertTrue(reported.getMessage().contains(owner.getModelRenderTarget().modelHash().toString()));
                for (int pass = 0; pass < 3; pass++)
                    assertNull(owner.produce(0.25f, RenderContext.levelImmutable()));
                assertEquals(1, owner.actions);
                assertEquals(1, batches.size());
            } finally {
                AnimationParallelTicker.waitAll();
                owner.retire();
                data.modelState.close();
            }
        }
    }

    @Test
    void differentInputsAndFixedSeedsKeepPoseRandomAndControllerHistoriesIndependent() throws Exception {
        var aAnimation = new Animation("a", 400, LoopType.LOOP, null, List.of(), List.of(), List.of());
        var bAnimation = new Animation("b", 400, LoopType.LOOP, null, List.of(), List.of(), List.of());
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var source = AnimationStore.eager(Map.of("a", aAnimation, "b", bAnimation))) {
            var serialA = new TraceOwner(host.player, source, 17, 291);
            var serialB = new TraceOwner(host.player, source, -23, 735);
            var parallelA = new TraceOwner(host.player, source, 17, 291);
            var parallelB = new TraceOwner(host.player, source, -23, 735);
            var owners = List.of(serialA, serialB, parallelA, parallelB);
            var expectedA = new net.minecraft.world.level.levelgen.XoroshiroRandomSource(291);
            var expectedB = new net.minecraft.world.level.levelgen.XoroshiroRandomSource(735);
            try {
                for (var owner : owners) {
                    owner.install(fixture.model, module);
                    owner.getAnimationProcessor().randomSource().setSeed(owner.seed);
                }
                assertNotSame(parallelA.getLoadedGeoModel(), parallelB.getLoadedGeoModel());
                for (int round = 0; round < 12; round++) {
                    host.player.tickCount = round * 3;
                    for (var owner : owners) owner.round = round;
                    var referenceA = serialA.produce(0.25f, RenderContext.levelImmutable());
                    var referenceB = serialB.produce(0.75f, RenderContext.levelImmutable());
                    FirstPersonOwnerIntegrationTest.assertNativeConsumption(serialA, referenceA);
                    FirstPersonOwnerIntegrationTest.assertNativeConsumption(serialB, referenceB);
                    var expectedPoseA = pose(referenceA);
                    var expectedPoseB = pose(referenceB);
                    FirstPersonOwnerIntegrationTest.startAsync(parallelA, 0.25f);
                    AnimationParallelTicker.schedule(parallelB, 0.75f);
                    parallelA.waitForAsyncUpdate();
                    for (int pass = 0; pass < 3; pass++) {
                        var repeated = parallelA.produce(0.25f, RenderContext.levelImmutable());
                        assertArrayEquals(expectedPoseA, pose(repeated));
                        FirstPersonOwnerIntegrationTest.assertNativeConsumption(parallelA, repeated);
                    }
                    AnimationParallelTicker.waitAll();
                    var resultB = parallelB.produce(0.75f, RenderContext.levelImmutable());
                    assertArrayEquals(expectedPoseB, pose(resultB));
                    FirstPersonOwnerIntegrationTest.assertNativeConsumption(parallelB, resultB);
                    assertArrayEquals(serialA.getLoadedGeoModel().getBoneAttributes(),
                        parallelA.getLoadedGeoModel().getBoneAttributes());
                    assertArrayEquals(serialB.getLoadedGeoModel().getBoneAttributes(),
                        parallelB.getLoadedGeoModel().getBoneAttributes());
                    assertEquals(serialA.controllerHistory(), parallelA.controllerHistory());
                    assertEquals(serialB.controllerHistory(), parallelB.controllerHistory());
                    assertEquals(expectedA.nextLong(), parallelA.randomHistory.get(round));
                    assertEquals(expectedB.nextLong(), parallelB.randomHistory.get(round));
                    for (var owner : owners) assertEquals(round + 1, owner.actions);
                }
                assertEquals(serialA.randomHistory, parallelA.randomHistory);
                assertEquals(serialB.randomHistory, parallelB.randomHistory);
                assertNotEquals(parallelA.randomHistory, parallelB.randomHistory);
                assertNotEquals(serialA.controllerHistory(), serialB.controllerHistory());
                assertNotEquals(serialA.getLoadedGeoModel().getSortedBones().get(0).getPositionX(),
                    serialB.getLoadedGeoModel().getSortedBones().get(0).getPositionX());
            } finally {
                AnimationParallelTicker.waitAll();
                for (var owner : owners) owner.retire();
            }
        }
    }

    private static float[] pose(GeoRenderData data) {
        assertNotNull(data);
        var matrix = new org.joml.Matrix4f();
        data.modelState.getNativeState().getBonePoses().getPose(0, matrix);
        return matrix.get(new float[16]);
    }

    private static GeoRenderData failingExtractData(Runnable failure) throws Exception {
        var data = new GeoRenderData();
        var locatorMap = data.modelState.getClass().getDeclaredField("activeLocatorMap");
        locatorMap.setAccessible(true);
        // Fail after native extraction so the geometry validity guard remains part of the oracle.
        locatorMap.set(data.modelState,
            new it.unimi.dsi.fastutil.objects.ReferenceArrayList<it.unimi.dsi.fastutil.shorts.ShortArrayList>() {
                @Override public void size(int size) { failure.run(); }
            });
        return data;
    }

    private static final class TraceOwner extends FirstPersonOwnerIntegrationTest.Timeline {
        final int input;
        final long seed;
        final List<Long> randomHistory = new ArrayList<>();
        CodedAnimationController<TraceOwner> controller;
        int round;

        TraceOwner(net.minecraft.client.player.LocalPlayer player, AnimationStore source, int input, long seed)
            throws Exception {
            super(player);
            readBatchStore = source;
            this.input = input;
            this.seed = seed;
        }

        @Override protected void prepareAnimationControllers(
            com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {
            controller = new CodedAnimationController<>(this, "trace", 0, event -> {
                if (round % 5 == 3) controller.indicateReload();
                controller.setAnimation((round + (input > 0 ? 0 : 1)) % 4 < 2 ? "a" : "b");
                return com.elfmcys.ysm.geckolib3.core.PlayState.CONTINUE;
            });
            animations.addAnimationController(controller);
        }

        @Override protected void codeAnimation(
            com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<? extends AnimatableEntity<net.minecraft.client.player.LocalPlayer>> event,
            boolean shouldUpdate) {
            if (shouldUpdate) {
                var random = getAnimationProcessor().randomSource().nextLong();
                randomHistory.add(random);
                getLoadedGeoModel().getSortedBones().get(0).setPosition(input + (random & 7), round, 0);
            }
        }

        List<Object> controllerHistory() throws Exception {
            var field = CodedAnimationController.class.getDeclaredField("animationPlayer");
            field.setAccessible(true);
            var player = (AnimationPlayer)field.get(controller);
            return List.of(controller.getStateName(), player.getState(), player.getAnimationContext().animTime());
        }
    }

}
