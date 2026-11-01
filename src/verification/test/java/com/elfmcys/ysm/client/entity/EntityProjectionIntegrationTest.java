package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.capability.VehicleAnimatableCapability;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class EntityProjectionIntegrationTest {
    FirstPersonOwnerIntegrationTest host;
    @BeforeAll static void initialize() throws Exception { FirstPersonOwnerIntegrationTest.initializeHost(); }
    @AfterAll static void restore() throws Exception { FirstPersonOwnerIntegrationTest.restoreHost(); }
    @BeforeEach void create() throws Exception {
        host = new FirstPersonOwnerIntegrationTest();
        host.createHost();
    }
    @AfterEach void release() { host.releaseHost(); }

    @Test
    void bodySessionVehicleAndArmShareOnlyTheJoinedProjectionAndTheNextLegalRound() throws Exception {
        for (boolean exitBeforeSource : new boolean[] {false, true}) {
            var builder = new net.minecraftforge.common.ForgeConfigSpec.Builder();
            var oldVehicle = ClientConfig.DISABLE_VEHICLE_MODEL;
            ClientConfig.DISABLE_VEHICLE_MODEL = builder.define("vehicle", false);
            builder.build().setConfig(com.electronwill.nightconfig.core.CommentedConfig.inMemory());
            try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
                var bodyModule = TestExpressionModules.create(Set.of("shared", "body_only"));
                var armModule = TestExpressionModules.create(Set.of("shared", "arm_only"))) {
                var boat = (ProjectionBoat)com.elfmcys.ysm.util.UnsafeUtil.getUnsafe()
                    .allocateInstance(ProjectionBoat.class);
                boat.passenger = host.player;
                var target = new ProjectionVehicle(boat);
                boat.owner = target;
                var vehicleResources = new OwnerResourceFixtures();
                vehicleResources.attach(target);
                var vehicleModel = vehicleResources.scopedTarget(1, bodyModule, fixture.model,
                    com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind.VEHICLE);
                FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.Entity.class, "position")
                    .set(boat, net.minecraft.world.phys.Vec3.ZERO);
                var sessionType = sessionLoader().loadClass("com.elfmcys.ysm.capability.ClientRoamingSession");
                var constructor = sessionType.getDeclaredConstructor(net.minecraft.world.entity.player.Player.class,
                    boolean.class, Runnable.class);
                constructor.setAccessible(true);
                var session = constructor.newInstance(host.player, true, (Runnable)() -> {});
                var body = new ProjectionBody(host.player, session);
                var arm = new FirstPersonOwnerIntegrationTest.Timeline(host.player) {
                    @Override protected Runnable prepareRoleInstallation(
                        com.elfmcys.ysm.model.resource.client.ModelRenderTarget model,
                        com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
                        prepared.connectArmTo(body.getExpressionRuntime());
                        return null;
                    }
                    @Override protected void codeAnimation(
                        com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<? extends AnimatableEntity<net.minecraft.client.player.LocalPlayer>> event,
                        boolean shouldUpdate) {
                        getLoadedGeoModel().getSortedBones().get(0)
                            .setPosition(getExpressionRuntime().roamingState().orElseThrow().read("shared"), 0, 0);
                    }
                };
                try (var catalog = vehicleResources.useCatalog()) {
                    target.init(vehicleModel.modelHash());
                    DemandFixtures.advance(target);
                    var vehicleState = target.getExpressionRuntime().roamingState().orElseThrow();
                    vehicleState.write("shared", 2);
                    FirstPersonOwnerIntegrationTest.startAsync(target, 0.25f);
                    assertTrue(target.entered.await(10, TimeUnit.SECONDS));
                    if (exitBeforeSource) {
                        target.release.countDown();
                        awaitExecutionExit(target);
                    }
                    body.install(fixture.model, bodyModule);
                    var bodyModel = body.getModelRenderTarget();
                    arm.install(fixture.model, armModule);
                    FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.Entity.class, "vehicle")
                        .set(host.player, boat);
                    var bodyState = body.getExpressionRuntime().roamingState().orElseThrow();
                    bodyState.write("shared", 7);
                    bodyState.write("body_only", 9);
                    AnimationParallelTicker.schedule(body, 0.25f);
                    body.waitForAsyncUpdate();
                    assertEquals(0, target.adoptions);
                    assertTrue(target.hasPendingUpdate(), "Execution exit alone must not open the target window");
                    FirstPersonOwnerIntegrationTest.assertNativeConsumption(body,
                        body.update(0.25f, RenderContext.levelImmutable()));
                    assertEquals(1, body.actions);
                    assertEquals(7f, arm.getExpressionRuntime().roamingState().orElseThrow().read("shared"));
                    assertFalse(arm.getExpressionRuntime().roamingState().orElseThrow().fields().contains("body_only"));
                    var stored = session.getClass().getDeclaredMethod("storedValues", int.class);
                    stored.setAccessible(true);
                    var values = (it.unimi.dsi.fastutil.ints.Int2FloatMap)stored.invoke(session,
                        bodyModel.modelHash().roamingHash());
                    assertEquals(9f, values.get(com.elfmcys.ysm.util.StringPool.computeIfAbsent("body_only")));
                    target.release.countDown();
                    awaitExecutionExit(target);
                    body.finishProjections(false);
                    assertEquals(0, target.adoptions);
                    target.waitForAsyncUpdate();
                    AnimationParallelTicker.waitAll();
                    assertEquals(1, target.adoptions);
                    assertEquals(List.of(2f), target.inputs);
                    assertEquals(7f, vehicleState.read("shared"));
                    assertEquals(9f, vehicleState.read("body_only"));
                    body.finishProjections(true);
                    assertEquals(1, target.adoptions);
                    host.player.tickCount += 10;
                    boat.tickCount += 10;
                    assertNotNull(target.update(0.25f, RenderContext.levelImmutable()));
                    assertEquals(List.of(2f, 7f), target.inputs);
                    assertEquals(7f, target.getLoadedGeoModel().getSortedBones().get(0).getPositionX());
                    FirstPersonOwnerIntegrationTest.assertNativeConsumption(arm,
                        arm.update(0.25f, RenderContext.levelImmutable()));
                    assertEquals(1, arm.actions);
                    assertEquals(7f, arm.getLoadedGeoModel().getSortedBones().get(0).getPositionX());
                    assertEquals(7f, arm.getExpressionRuntime().roamingState().orElseThrow().read("shared"));
                } finally {
                    target.release.countDown();
                    AnimationParallelTicker.waitAll();
                    FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.Entity.class, "vehicle")
                        .set(host.player, null);
                    arm.retire();
                    target.retire();
                    body.retire();
                }
            } finally { ClientConfig.DISABLE_VEHICLE_MODEL = oldVehicle; }
        }
    }

    private static ClassLoader sessionLoader() {
        // Forge initializes CapabilityToken through a transformer that this standalone JVM does not run.
        var provider = "com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider";
        var session = "com.elfmcys.ysm.capability.ClientRoamingSession";
        var payloads = "com.elfmcys.ysm.capability.RoamingPayloads";
        return new ClassLoader(EntityProjectionIntegrationTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(provider) && !name.equals(session) && !name.startsWith(session + "$")
                    && !name.equals(payloads) && !name.startsWith(payloads + "$"))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    try (var source = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        byte[] code = java.util.Objects.requireNonNull(source).readAllBytes();
                        if (name.equals(provider)) {
                            var node = new org.objectweb.asm.tree.ClassNode();
                            new org.objectweb.asm.ClassReader(code).accept(node, 0);
                            var initialization = node.methods.stream().filter(method -> method.name.equals("<clinit>"))
                                .findFirst().orElseThrow();
                            initialization.instructions.clear();
                            initialization.tryCatchBlocks.clear();
                            if (initialization.localVariables != null) initialization.localVariables.clear();
                            initialization.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
                            var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
                            node.accept(writer);
                            code = writer.toByteArray();
                        }
                        var result = defineClass(name, code, 0, code.length);
                        if (resolve) resolveClass(result);
                        return result;
                    } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
            }
        };
    }

    private static final class ProjectionBody extends FirstPersonOwnerIntegrationTest.Timeline {
        final Object session;
        ProjectionBody(net.minecraft.client.player.LocalPlayer player, Object session) throws Exception {
            super(player);
            this.session = session;
        }
        @Override protected dev.semaweave.RoamingRole roamingRole() { return dev.semaweave.RoamingRole.PRODUCER; }
        @Override protected Runnable prepareRoleInstallation(
            com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.PreparedInstallation prepared) {
            try {
                var method = session.getClass().getDeclaredMethod("prepare", int.class,
                    com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule.class, prepared.getClass(), boolean.class);
                method.setAccessible(true);
                return (Runnable)method.invoke(session, target.modelHash().roamingHash(), target.expressions(), prepared,
                    true);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        @Override protected com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.ProjectionSink prepareLocalProjection() {
            try {
                var method = session.getClass().getDeclaredMethod("prepareLocalProjection");
                method.setAccessible(true);
                return (com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.ProjectionSink)method.invoke(session);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static void awaitExecutionExit(AnimatableEntity<?> owner) throws Exception {
        var lifecycle = FirstPersonOwnerIntegrationTest.field(AnimatableEntity.class, "lifecycle").get(owner);
        var task = (Future<?>)FirstPersonOwnerIntegrationTest.field(lifecycle.getClass(), "asyncTask").get(lifecycle);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!task.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(task.isDone());
    }

    private static final class ProjectionVehicle extends VehicleAnimatableCapability {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<Float> inputs = new ArrayList<>();
        int adoptions;
        ProjectionVehicle(net.minecraft.world.entity.Entity entity) { super(entity); }
        @Override protected void prepareAnimationControllers(
            com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {}
        @Override protected void dispatchPersistentEvents(
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Evaluation evaluation, boolean shouldUpdate) {
            inputs.add(getExpressionRuntime().roamingState().orElseThrow().read("shared"));
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        }
        @Override protected void codeAnimation(
            com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<? extends AnimatableEntity<net.minecraft.world.entity.Entity>> event,
            boolean shouldUpdate) {
            getLoadedGeoModel().getSortedBones().get(0)
                .setPosition(getExpressionRuntime().roamingState().orElseThrow().read("shared"), 0, 0);
        }
        @Override public void updateRoamingVars(it.unimi.dsi.fastutil.ints.Int2FloatMap vars) {
            super.updateRoamingVars(vars);
            adoptions++;
        }
        @Override public int getFrameRateLimit() { return 60; }
    }

    private static final class ProjectionBoat extends net.minecraft.world.entity.vehicle.Boat {
        net.minecraft.world.entity.Entity passenger;
        VehicleAnimatableCapability owner;
        private ProjectionBoat() { super(net.minecraft.world.entity.EntityType.BOAT, null); }
        @Override public net.minecraft.world.entity.Entity getFirstPassenger() { return passenger; }
        @Override public boolean shouldRiderSit() { return false; }
        @Override public net.minecraft.world.entity.EntityType<?> getType() {
            return net.minecraft.world.entity.EntityType.BOAT;
        }
        @Override public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction side) {
            return net.minecraftforge.common.util.LazyOptional.of(() -> owner).cast();
        }
    }
}
