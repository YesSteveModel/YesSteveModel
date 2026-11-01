package com.elfmcys.ysm.client.entity;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;
import static org.junit.jupiter.api.Assertions.*;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator.InstallationResult;
import com.elfmcys.ysm.client.gui.GuiFrameInput;
import com.elfmcys.ysm.expression.EncodedProgramMapper;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.expression.frontend.MolangFrontend;
import com.elfmcys.ysm.expression.host.ExpressionHost;
import com.elfmcys.ysm.expression.host.HostAvailability;
import com.elfmcys.ysm.expression.inventory.ExpressionSet;
import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.IBoneAnimationQueue;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.*;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFileProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import com.elfmcys.ysm.proto.mixel.asset.model.data.ExpressionValueProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class GuiEntityExecutionTest {
    private FirstPersonOwnerIntegrationTest host;

    @BeforeAll static void initialize() throws Exception { FirstPersonOwnerIntegrationTest.initializeHost(); }
    @AfterAll static void restore() throws Exception { FirstPersonOwnerIntegrationTest.restoreHost(); }
    @BeforeEach void create() throws Exception {
        host = new FirstPersonOwnerIntegrationTest();
        host.createHost();
    }
    @AfterEach void release() { host.releaseHost(); }

    @Test
    void publicApiRejectsNonGuiOwnersAndEscapedExecutionCapabilities() throws Exception {
        var world = new FirstPersonOwnerIntegrationTest.Timeline(host.player);
        var gui = new GuiOwner(subject(1));
        try {
            assertThrows(IllegalStateException.class, () -> world.reserveGuiTask(execution -> null));
            var subject = gui.getEntity();
            var escaped = new java.util.concurrent.atomic.AtomicReference<AnimatableEntity<LocalPlayer>.GuiExecution>();
            var task = gui.reserveGuiTask(execution -> {
                assertSame(subject, gui.getEntity());
                escaped.set(execution);
                assertThrows(IllegalStateException.class, gui::reset);
                assertThrows(IllegalStateException.class, gui::reloadGeoModel);
                assertThrows(IllegalStateException.class, gui::retire);
                assertThrows(IllegalStateException.class, gui::assignment);
                return null;
            });
            assertThrows(IllegalStateException.class, gui::getEntity);
            task.run();
            assertThrows(IllegalStateException.class, gui::getEntity);
            assertThrows(IllegalStateException.class, () -> escaped.get().update(0.5f,
                com.elfmcys.ysm.geckolib3.geo.RenderContext.levelImmutable()));
            task.join();
            assertSame(subject, gui.getEntity());
            assertThrows(IllegalStateException.class, () -> escaped.get().update(0.5f,
                com.elfmcys.ysm.geckolib3.geo.RenderContext.levelImmutable()));
            var next = gui.reserveGuiTask(execution -> {
                assertThrows(IllegalStateException.class, () -> escaped.get().update(0.5f,
                    com.elfmcys.ysm.geckolib3.geo.RenderContext.levelImmutable()));
                return null;
            });
            next.run();
            next.join();
        } finally {
            world.retire();
            gui.retire();
        }
    }

    @Test
    void sharedReadyTargetAndCompiledModuleKeepSerialAndParallelGuiInputsAndNativeOutputsEquivalent(
        @TempDir Path cache) throws Exception {
        try (var geometry = new FirstPersonOwnerIntegrationTest.Fixture("parts-a"); var module = module(cache)) {
            var serialA = new GuiOwner(subject(2));
            var serialB = new GuiOwner(subject(7));
            var parallelA = new GuiOwner(subject(2));
            var parallelB = new GuiOwner(subject(7));
            var owners = List.of(serialA, serialB, parallelA, parallelB);
            var target = serialA.resources.target(1, module, geometry.model);
            try {
                for (var owner : owners) {
                    owner.resources.targets.put(target.modelHash(), target);
                    owner.select(1);
                }
                for (int frame = 0; frame < 5; frame++) {
                    float partial = frame % 2 == 0 ? 0.125f : 0.625f;
                    boolean bind = frame == 0;
                    int index = frame;
                    for (var owner : owners)
                        owner.entity.tickCount = 100 + frame;
                    var first = prepare(serialA, bind, partial, index, null);
                    first.run();
                    var expectedA = first.join();
                    var second = prepare(serialB, bind, partial, index, null);
                    second.run();
                    var expectedB = second.join();
                    var barrier = new CyclicBarrier(2);
                    var left = prepare(parallelA, bind, partial, index, barrier);
                    var right = prepare(parallelB, bind, partial, index, barrier);
                    var workerA = new Thread(left);
                    var workerB = new Thread(right);
                    workerA.start();
                    workerB.start();
                    assertThrows(IllegalStateException.class, parallelA::getLoadedGeoModel);
                    assertThrows(IllegalStateException.class, parallelA::assignment);
                    assertThrows(IllegalStateException.class, parallelA::reset);
                    compare(expectedA, left.join());
                    compare(expectedB, right.join());
                    workerA.join(5000);
                    workerB.join(5000);
                    assertFalse(workerA.isAlive());
                    assertFalse(workerB.isAlive());
                    for (var owner : owners) {
                        assertEquals(23, owner.entity.yBodyRot);
                        assertEquals(41, owner.entity.getYRot());
                        assertEquals(11, owner.entity.getXRot());
                        assertEquals(owner.equipmentCount, owner.entity.getItemBySlot(EquipmentSlot.MAINHAND).getCount());
                        assertSame(target, owner.getModelRenderTarget());
                        assertSame(module, owner.getExpressionRuntime().module().orElseThrow());
                        assertEquals(frame + 1, owner.values.size());
                        assertEquals((float)(frame + 1), owner.values.get(frame));
                    }
                }
                assertNotSame(serialA.getAnimationProcessor(), parallelA.getAnimationProcessor());
                assertNotSame(parallelA.getAnimationData(), parallelB.getAnimationData());
                assertNotSame(parallelA.getLoadedGeoModel(), parallelB.getLoadedGeoModel());
                assertNotSame(parallelA.getLoadedGeoModel().getBoneAttributes(),
                    parallelB.getLoadedGeoModel().getBoneAttributes());
                assertNotSame(parallelA.exactComponent(com.elfmcys.ysm.expression.host.builtin.PositionComponent.class)
                    .orElseThrow(), parallelB.exactComponent(com.elfmcys.ysm.expression.host.builtin.PositionComponent.class)
                    .orElseThrow());
            } finally {
                for (var owner : owners)
                    owner.retire();
            }
        }
    }

    @Test
    void fullGuiBindingRetainsHealthyCandidatesAndEndsFailedCompatibleActivationOnlyAfterJoin(
        @TempDir Path cache) throws Exception {
        try (var geometry = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var alternate = new FirstPersonOwnerIntegrationTest.Fixture("parts-b"); var module = module(cache)) {
            var owner = new GuiOwner(subject(3));
            try {
                var key = new RenderTargetKey("player", "a", OwnerResourceFixtures.PROFILE, module.expressionBinding(),
                    ModelResourcePurpose.GUI);
                var identity = new ModelRenderTarget.StateIdentity(
                    new ModelFileIdentity(OwnerResourceFixtures.hash(1), OwnerResourceFixtures.hash(122)),
                    key.stateKey(), new Object());
                var a = owner.resources.target(1, module, new ResourceLocation("ysm", "gui-a"), identity, geometry.model);
                var b = owner.resources.target(1, module, new ResourceLocation("ysm", "gui-b"), identity, alternate.model);
                owner.resources.targets.put(a.modelHash(), a);
                owner.select(1);
                var initial = prepare(owner, true, 0.25f, 0, null);
                initial.run();
                assertThrows(IllegalStateException.class, owner::getModelRenderTarget);
                initial.join();
                var oldState = owner.getLoadedGeoModel();
                var oldAnimations = owner.getAnimationData();
                var oldComponent = owner.exactComponent(com.elfmcys.ysm.expression.host.builtin.PositionComponent.class)
                    .orElseThrow();
                owner.selectedTexture = "b";
                owner.resources.targets.put(b.modelHash(), b);
                var replacement = prepare(owner, true, 0.5f, 1, null);
                replacement.run();
                replacement.join();
                assertSame(b, owner.getModelRenderTarget());
                assertSame(oldState, owner.getLoadedGeoModel());
                assertSame(oldAnimations, owner.getAnimationData());
                assertSame(oldComponent, owner.exactComponent(oldComponent.getClass()).orElseThrow());
                assertSame(alternate.model, oldState.getModel());

                var healthyLease = owner.resources.leases.get(owner.resources.leases.size() - 1);
                owner.resources.target(2, module, geometry.model);
                owner.select(2);
                owner.failCandidate = true;
                var candidate = binding(owner);
                candidate.run();
                assertEquals(InstallationResult.REJECTED, candidate.join());
                assertSame(b, owner.getModelRenderTarget());
                assertEquals(0, healthyLease.closes);
                assertNotNull(owner.modelLoadFailure());

                owner.failCandidate = false;
                owner.select(1);
                owner.selectedTexture = "a";
                owner.resources.targets.put(a.modelHash(), a);
                owner.failActivation = true;
                var activation = binding(owner);
                activation.run();
                assertEquals(0, healthyLease.closes);
                assertThrows(IllegalStateException.class, owner::getLoadedGeoModel);
                assertEquals(InstallationResult.REJECTED, activation.join());
                assertFalse(owner.hasInstalledModel());
                assertEquals(1, healthyLease.closes);
                assertNotNull(owner.modelLoadFailure());
                owner.failActivation = false;
                var remembered = binding(owner);
                remembered.run();
                assertEquals(InstallationResult.REJECTED, remembered.join());
                assertFalse(owner.hasInstalledModel());
            } finally { owner.retire(); }
        }
    }

    private static AnimatableEntity.GuiTask<InstallationResult> binding(GuiOwner owner) {
        var request = owner.assignment().request();
        var lease = owner.resources.findReady(request).orElseThrow();
        return owner.reserveGuiTask(execution -> execution.install(request, lease, false, false));
    }

    private static AnimatableEntity.GuiTask<Sample> prepare(GuiOwner owner, boolean bind, float partial, int frame,
        CyclicBarrier barrier) {
        var input = GuiFrameInput.capture(owner.entity, partial, frame % 2 == 0, frame == 2,
            frame % 2 != 0, frame % 3 == 0, 1000L + frame * 50L);
        var request = owner.assignment().request();
        var lease = bind ? owner.resources.findReady(request).orElseThrow() : null;
        return owner.reserveGuiTask(execution -> {
            if (barrier != null) {
                try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            try (var ignored = input.apply()) {
                owner.hovered = input.hovered();
                owner.focused = input.focused();
                if (lease != null)
                    assertEquals(InstallationResult.INSTALLED, execution.install(request, lease, false, false));
                var data = execution.update(input.partialTick(), input.context());
                assertNotNull(data);
                assertTrue(data.ctx.inventory());
                var nativeState = data.modelState.getNativeState();
                var poses = new float[data.modelState.getModel().sortedBones().size() * 16];
                var matrix = new org.joml.Matrix4f();
                for (int bone = 0; bone < poses.length / 16; bone++)
                    nativeState.getBonePoses().getPose(bone, matrix).get(poses, bone * 16);
                var indices = nativeState.getRenderBoneIndices().duplicate();
                var rendered = new short[indices.remaining()];
                indices.get(rendered);
                return new Sample(owner.value, owner.getLoadedGeoModel().getBoneAttributes().clone(), poses,
                    rendered, nativeState.getTotalVertexCount(), data.partialTicks);
            }
        });
    }

    private static void compare(Sample expected, Sample actual) {
        assertEquals(expected.value, actual.value);
        assertArrayEquals(expected.attributes, actual.attributes);
        assertArrayEquals(expected.poses, actual.poses);
        assertArrayEquals(expected.indices, actual.indices);
        assertEquals(expected.vertices, actual.vertices);
        assertEquals(expected.partial, actual.partial);
    }

    private record Sample(float value, float[] attributes, float[] poses, short[] indices, int vertices, float partial) {}

    private static RenderTargetExpressionModule module(Path cache) throws Exception {
        var source = new MolangFrontend().compile("v.counter=v.counter+1;return v.counter;",
            MolangFrontend.SourceMode.EXPRESSION, new MolangFrontend.SourceLocation("gui-execution-test")).program();
        var animation = AnimationProto.newBuilder().setName("counter")
            .setBlendWeight(ExpressionValueProto.newBuilder().setProgram(EncodedProgramMapper.toProto(source)).build())
            .build();
        var definition = ModelDataProto.newBuilder()
            .putAnimationFiles("main", AnimationFileProto.newBuilder().addAnimations(animation).build()).build();
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(cache, track(new AtomicSharedCache(cache)))),
            ExpressionHost.create(HostAvailability.none()), new ExpressionEngineMetrics());
        return factory.createSet(() -> false, OwnerResourceFixtures.hash(1), "player", ExpressionSet.MAIN,
            factory.expressionBinding(LocalPlayer.class), definition, StringDataProto.newBuilder().build(),
            SettingsProto.newBuilder().build(), new byte[0], false, ModelResourcePurpose.GUI);
    }

    private static GuiSubject subject(int count) throws Exception {
        var player = FirstPersonOwnerIntegrationTest.allocate(GuiSubject.class);
        FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.Entity.class, "position")
            .set(player, net.minecraft.world.phys.Vec3.ZERO);
        FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.LivingEntity.class, "walkAnimation")
            .set(player, new net.minecraft.world.entity.WalkAnimationState());
        FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.player.Player.class, "inventory")
            .set(player, new Inventory(player));
        player.getInventory().items.set(0, new ItemStack(Items.STICK, count));
        player.yBodyRot = 23;
        player.setYRot(41);
        player.setXRot(11);
        return player;
    }

    private static final class GuiSubject extends LocalPlayer {
        private GuiSubject() { super(null, null, null, null, null, false, false); }
        @Override public boolean isAlive() { return false; }
        @Override public boolean isUsingItem() { return false; }
        @Override public boolean isSleeping() { return false; }
        @Override public Pose getPose() { return Pose.STANDING; }
        @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
        @Override public net.minecraft.world.entity.Entity getFirstPassenger() { return null; }
        @Override public ItemStack getItemBySlot(EquipmentSlot slot) {
            return switch (slot.getType()) {
                case HAND -> slot == EquipmentSlot.MAINHAND ? getInventory().getSelected()
                    : getInventory().offhand.get(0);
                case ARMOR -> getInventory().armor.get(slot.getIndex());
            };
        }
    }

    private static final class GuiOwner extends FirstPersonOwnerIntegrationTest.Timeline {
        final int equipmentCount;
        final List<Float> values = new ArrayList<>();
        String selectedTexture = "a";
        boolean failCandidate;
        boolean failActivation;
        boolean hovered;
        boolean focused;
        float value;

        GuiOwner(GuiSubject player) throws Exception {
            super(player);
            equipmentCount = player.getItemBySlot(EquipmentSlot.MAINHAND).getCount();
        }

        @Override protected ModelResourcePurpose resourcePurpose() { return ModelResourcePurpose.GUI; }
        @Override protected boolean determineImmutableContext(com.elfmcys.ysm.geckolib3.geo.RenderContext context) {
            return true;
        }
        @Override protected String requestedTextureName() { return selectedTexture; }
        @Override public boolean isFakePlayer() { return true; }
        @Override public ResourceLocation getTextureLocation() { return getModelRenderTarget().textureId(); }

        @Override protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {
            if (failCandidate)
                throw new IllegalStateException("private GUI candidate failed");
            var location = new ModelProgramInventory.SemanticLocation("player", ExpressionSet.MAIN,
                "animation/main/counter/blend-weight", ProgramRole.ANIMATION_SAMPLE, "");
            var program = target.expressions().requireFloatProgram(location);
            animations.addAnimationController(new IAnimationController<GuiOwner>() {
                public String getName() { return "gui-counter"; }
                public String getStateName() { return "running"; }
                public void updateModel(List<BoneTopLevelSnapshot> bones,
                    Object2ReferenceMap<String, List<EventProgram>> events,
                    Function<String, AnimationControllerData> definitions) {}
                public Runnable prepareResourceBinding(Object2ReferenceMap<String, List<EventProgram>> events,
                    Function<String, AnimationControllerData> definitions) {
                    return () -> {
                        if (failActivation)
                            throw new IllegalStateException("shared GUI activation failed");
                    };
                }
                public void process(AnimationEvent<GuiOwner> event, EntityExpressionRuntime.Evaluation evaluation,
                    boolean executeEvents) {
                    value = evaluation.evaluate(program);
                    values.add(value);
                }
                public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {}
                public void clear() {}
            });
        }

        @Override protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<LocalPlayer>> event,
            boolean shouldUpdate) {
            float input = entity.yBodyRot / 100 + event.getRequestedPartialTick()
                + entity.getItemBySlot(EquipmentSlot.MAINHAND).getCount() + (hovered ? 3 : 0) + (focused ? 7 : 0);
            getLoadedGeoModel().getSortedBones().get(0).setPosition(value + input, 0, 0);
        }
    }
}
