package com.elfmcys.ysm.client.entity;

import static com.elfmcys.ysm.testutil.DerivedStorageTestScope.track;

import com.elfmcys.ysm.api.internal.processor.UnifiedDeclarationProcessor;
import com.elfmcys.ysm.expression.aot.*;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.expression.host.*;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelDataProto;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringDataProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.SettingsProto;
import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Generated declarations cross admission, target installation, owner sampling and failure reduction. */
@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
class EntityComponentOwnerLifecycleTest {
    @Test
    void generatedLifecycleUsesTheRealOwnerAndRetiresFailedInstallations(@TempDir Path temporary) throws Exception {
        var output = Files.createDirectory(temporary.resolve("provider"));
        compileProbe(output);
        var log = temporary.resolve("owner-lifecycle.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-cp", output + File.pathSeparator + System.getProperty("java.class.path"),
            Probe.class.getName(), output.toString(), temporary.resolve("cache").toString()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owner lifecycle probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    private static void compileProbe(Path output) throws Exception {
        String source =
            """
                package independent;
                import com.elfmcys.ysm.api.annotation.*;
                import com.elfmcys.ysm.api.molang.*;
                import com.elfmcys.ysm.expression.host.*;
                import com.elfmcys.ysm.client.entity.AnimatableEntity;
                import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
                import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
                import net.minecraft.world.entity.Entity;
                @YsmEntityComponent("lifecycle_probe")
                public final class LifecycleProbe implements EntityComponent<Entity> {
                    public static AnimatableEntity<?> failedOwner;
                    public static String failPhase = "";
                    private static final java.util.Map<AnimatableEntity<?>,java.util.Map<String,Integer>> counts = new java.util.IdentityHashMap<>();
                    public final AnimatableEntity<Entity> owner;
                    public AnimationEvent<?> borrowed;
                    public ModelRenderTarget target;
                    public final java.util.List<int[]> ticks = new java.util.ArrayList<>();
                    public final java.util.List<float[]> updates = new java.util.ArrayList<>();
                    public final java.util.List<Float> observations = new java.util.ArrayList<>();
                    public Runnable beginCallback = () -> {};
                    public Runnable updateCallback = () -> {};
                    public LifecycleProbe(AnimatableEntity<Entity> owner) {this.owner=owner;hit("CONSTRUCT");}
                    public static int count(AnimatableEntity<?> owner,String phase) {
                        return counts.getOrDefault(owner,java.util.Map.of()).getOrDefault(phase,0);
                    }
                    private void hit(String phase) {
                        var ownerCounts=counts.computeIfAbsent(owner,ignored->new java.util.HashMap<>());
                        ownerCounts.merge(phase,1,Integer::sum);
                        if(owner==failedOwner && phase.equals(failPhase))throw new IllegalStateException("probe-"+phase);
                    }
                    public void setRenderTarget(ModelRenderTarget target) {hit("SET_RENDER_TARGET");this.target=target;}
                    public void tick(int previous,int current) {ticks.add(new int[]{previous,current});hit("TICK");}
                    public void update(float previous,float current,float partial) {
                        updates.add(new float[]{previous,current,partial});hit("UPDATE");updateCallback.run();
                    }
                    public void beginEval(AnimationEvent<?> event) {
                        borrowed=event;observations.add(event.getPartialTick());
                        try {hit("BEGIN_EVAL");beginCallback.run();}catch(RuntimeException failure){borrowed=null;throw failure;}
                    }
                    public void endEval() {try{hit("END_EVAL");}finally{borrowed=null;}}
                    @MolangFunction(effect=MolangEffect.CONTEXT)
                    public int read(MolangContextView<Entity> context){return updates.size();}
                }
                """;
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var unit = new SimpleJavaFileObject(URI.create("string:///independent/LifecycleProbe.java"),
                JavaFileObject.Kind.SOURCE) {
                public CharSequence getCharContent(boolean ignored) {
                    return source;
                }
            };
            var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "17", "-classpath",
                System.getProperty("java.class.path"), "-d", output.toString()), null, List.of(unit));
            task.setProcessors(List.of(new UnifiedDeclarationProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            com.elfmcys.ysm.util.NativeLibUtil.load();
            FirstPersonOwnerIntegrationTest.initializeHost();
            var harness = new FirstPersonOwnerIntegrationTest();
            try {
                harness.createHost();
                verify(Path.of(args[0]), Path.of(args[1]), harness);
            } finally {
                harness.releaseHost();
                FirstPersonOwnerIntegrationTest.restoreHost();
            }
        }
    }

    private static void verify(Path provider, Path cache, FirstPersonOwnerIntegrationTest harness) throws Exception {
        var loader = EntityComponentOwnerLifecycleTest.class.getClassLoader();
        var collector = new MolangExtensions.Collector();
        collector.collectBuiltins();
        collector.collect(new MolangExtensions.Source(provider.toString(), Set.of("lifecycle_probe"),
            List.of(new MolangExtensions.DeclarationRef("independent.LifecycleProbe", "lifecycle_probe", "")), loader,
            provider));
        var admission = collector.freeze(HostAvailability.none(), new MolangExtensions.Qualification() {
            public boolean dependency(String mod, String version) {
                return true;
            }

            public boolean checker(MolangExtensions.Source source,
                com.elfmcys.ysm.api.molang.DeclarationSchema schema) {
                return true;
            }
        });
        assertTrue(admission.rejectedMods().isEmpty(), admission.diagnostics().toString());
        var probe = loader.loadClass("independent.LifecycleProbe");
        var factory = new RenderTargetExpressionModuleFactory(
            track(new ExpressionArtifactStore(cache, track(new AtomicSharedCache(cache)))), admission.host(),
            new ExpressionEngineMetrics());
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = module(factory, 1, false); var fallback = module(factory, 0, true)) {
            assertEquals(admission.host().builtinOnly().expressionBinding(), fallback.expressionBinding());
            verifySampling(harness, fixture, module, fallback, probe);
            verifyTextureReuse(harness, fixture, module, fallback, probe);
            for (var phase : ComponentLifecycleException.Phase.values())
                verifyFailure(harness, fixture, module, fallback, probe, phase);
            verifyDefaultFailure(harness, fixture, fallback);
            verifyResetAndAsync(harness, fixture, module, fallback, probe);
            verifyAsyncFailure(harness, fixture, module, fallback, probe);
            verifyDemandCleanupFailures(harness, fixture, module, fallback, probe);
            verifyDoublePendingDemandCleanup(harness, fixture, module, fallback);
        }
    }

    private static RenderTargetExpressionModule module(RenderTargetExpressionModuleFactory factory, int marker,
        boolean intrinsic) throws Exception {
        return factory.create(() -> false,
            new ModelFileIdentity(OwnerResourceFixtures.hash(marker), OwnerResourceFixtures.hash(122)), "player",
            "variant0", OwnerResourceFixtures.PROFILE, intrinsic, ModelDataProto.newBuilder().build(),
            StringDataProto.newBuilder().build(), SettingsProto.newBuilder().build(), new byte[0]);
    }

    private static void verifySampling(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback, Class<?> probe) throws Exception {
        var owner = new Owner(harness.player);
        try {
            owner.resources.target(0, fallback, fixture.model);
            owner.install(fixture.model, module);
            Object component = owner.exactComponent(probe).orElseThrow();
            assertSame(owner, probe.getField("owner").get(component));
            harness.player.tickCount = 10;
            assertNotNull(owner.produce(0.25f, RenderContext.levelImmutable()));
            var ticks = ticks(probe, component);
            var updates = updates(probe, component);
            assertArrayEquals(new int[] {10, 10}, ticks.get(0));
            assertArrayEquals(new float[] {0, 0, 0.25f}, updates.get(0));
            int begins = count(probe, owner, "BEGIN_EVAL");
            owner.produce(0.25f, RenderContext.levelImmutable());
            assertEquals(begins, count(probe, owner, "BEGIN_EVAL"));
            owner.produce(0.5f, RenderContext.levelImmutable());
            assertEquals(1, ticks.size());
            assertArrayEquals(new float[] {0, 0.25f, 0.5f}, updates.get(1));
            var mutable = new RenderContext(false, false, false, true, false, false, false);
            owner.produce(0.6f, mutable);
            owner.produce(0.7f, mutable);
            assertEquals(2, updates.size());
            @SuppressWarnings("unchecked")
            var observations = (List<Float>)probe.getField("observations").get(component);
            assertEquals(List.of(0.25f, 0.5f, 0.6f, 0.7f), observations);
            owner.produce(0.8f, RenderContext.levelImmutable());
            assertEquals(3, updates.size());
            assertArrayEquals(new float[] {0.25f, 0.55f, 0.8f}, updates.get(2), 0.00001f);
            owner.limit = 1;
            harness.player.tickCount = 15;
            owner.produce(0.25f, RenderContext.levelImmutable());
            assertEquals(3, updates.size());
            harness.player.tickCount = 40;
            owner.produce(0.25f, RenderContext.levelImmutable());
            assertEquals(4, updates.size());
            assertArrayEquals(new int[] {10, 40}, ticks.get(1));
            assertEquals(0.55f, updates.get(3)[0], 0.00001f);
            assertEquals(30f, updates.get(3)[1], 0.00001f);
            harness.player.tickCount = 30;
            owner.produce(0.5f, mutable);
            assertEquals(4, updates.size());
            assertEquals(2, ticks.size());
            assertNull(probe.getField("borrowed").get(component));
            assertEquals(count(probe, owner, "BEGIN_EVAL"), count(probe, owner, "END_EVAL"));
        } finally {
            owner.retire();
        }
    }

    private static void verifyTextureReuse(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback, Class<?> probe) throws Exception {
        try (var alternate = new FirstPersonOwnerIntegrationTest.Fixture("parts-b")) {
            var owner = new Owner(harness.player);
            var positionField = net.minecraft.world.entity.Entity.class.getDeclaredField("position");
            positionField.setAccessible(true);
            var previousPosition = positionField.get(harness.player);
            double previousXo = harness.player.xo;
            try {
                positionField.set(harness.player, net.minecraft.world.phys.Vec3.ZERO);
                harness.player.xo = 0;
                owner.resources.target(0, fallback, fixture.model);
                var key = new com.elfmcys.ysm.model.resource.client.render.RenderTargetKey("player", "a",
                    OwnerResourceFixtures.PROFILE, module.expressionBinding());
                var identity = new com.elfmcys.ysm.model.resource.client.ModelRenderTarget.StateIdentity(
                    new ModelFileIdentity(OwnerResourceFixtures.hash(1), OwnerResourceFixtures.hash(122)),
                    key.stateKey(), new Object());
                var a = owner.resources.target(1, module,
                    new net.minecraft.resources.ResourceLocation("ysm", "texture-a"), identity, fixture.model);
                var b = owner.resources.target(1, module,
                    new net.minecraft.resources.ResourceLocation("ysm", "texture-b"), identity, alternate.model);
                var zero = new com.elfmcys.ysm.geckolib3.core.keyframe.bone.Vector3v(new FloatExpression.Literal(0),
                    new FloatExpression.Literal(0), new FloatExpression.Literal(0));
                var one = new com.elfmcys.ysm.geckolib3.core.keyframe.bone.Vector3v(new FloatExpression.Literal(1),
                    new FloatExpression.Literal(0), new FloatExpression.Literal(0));
                var track = new com.elfmcys.ysm.geckolib3.core.keyframe.BoneAnimation("Outside", List.of(),
                    List.of(new com.elfmcys.ysm.geckolib3.core.keyframe.bone.LinearKeyFrame(0, 100, zero, one, one)),
                    List.of());
                owner.animation = new com.elfmcys.ysm.geckolib3.core.builder.Animation("trace", 100,
                    com.elfmcys.ysm.geckolib3.core.builder.LoopType.LOOP, null, List.of(track), List.of(), List.of());
                owner.controller = new com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<Owner>(owner,
                    "trace", 0, event -> com.elfmcys.ysm.geckolib3.core.PlayState.CONTINUE);
                owner.selectTexture("a", a);
                owner.controller.setAnimation("trace");
                harness.player.tickCount = 100;
                var prior = owner.produce(0.25f, RenderContext.levelImmutable());
                assertEquals(a.textureId(), prior.texture);
                harness.player.tickCount++;
                positionField.set(harness.player, new net.minecraft.world.phys.Vec3(2, 0, 0));
                owner.produce(0.25f, RenderContext.levelImmutable());
                Object component = owner.exactComponent(probe).orElseThrow();
                var position =
                    owner.exactComponent(com.elfmcys.ysm.expression.host.builtin.PositionComponent.class).orElseThrow();
                assertEquals(0.5f, position.positionValue(0, true));
                float speed = position.trackedGroundSpeed();
                assertTrue(speed > 0);
                var animations = owner.getAnimationData();
                var processor = owner.getAnimationProcessor();
                var geometry = owner.getLoadedGeoModel();
                float time = owner.getSeekTime();
                var bone = geometry.getSortedBones().get(0);
                var originalBone = bone.getBoneData();
                var attributes = geometry.getBoneAttributes().clone();
                var animationPlayerField = com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController.class
                    .getDeclaredField("animationPlayer");
                animationPlayerField.setAccessible(true);
                var player = (com.elfmcys.ysm.geckolib3.core.controller.AnimationPlayer)animationPlayerField
                    .get(owner.controller);
                assertFalse(player.getActiveBoneAnimQueues().isEmpty());
                var queue = player.getActiveBoneAnimQueues().get(0);
                float animationTime = player.getAnimationContext().animTime();
                var lease = owner.resources.leases.stream().filter(item -> item.closes == 0 && item.target == a)
                    .findFirst().orElseThrow();
                int updates = updates(probe, component).size();
                owner.selectTexture("b", b);
                assertSame(b, owner.getModelRenderTarget());
                assertSame(component, owner.exactComponent(probe).orElseThrow());
                assertSame(position, owner.exactComponent(position.getClass()).orElseThrow());
                assertEquals(0.5f, position.positionValue(0, true));
                assertEquals(speed, position.trackedGroundSpeed());
                assertSame(animations, owner.getAnimationData());
                assertSame(processor, owner.getAnimationProcessor());
                assertSame(geometry, owner.getLoadedGeoModel());
                assertSame(alternate.model, geometry.getModel());
                assertSame(bone, geometry.getSortedBones().get(0));
                assertSame(alternate.model.sortedBones().get(0), bone.getBoneData());
                assertNotSame(originalBone, bone.getBoneData());
                assertArrayEquals(attributes, geometry.getBoneAttributes());
                assertEquals(time, owner.getSeekTime());
                assertEquals(animationTime, player.getAnimationContext().animTime());
                assertSame(queue, player.getActiveBoneAnimQueues().get(0));
                assertSame(b, probe.getField("target").get(component));
                assertEquals(1, count(probe, owner, "CONSTRUCT"));
                assertEquals(2, count(probe, owner, "SET_RENDER_TARGET"));
                assertEquals(1, lease.closes);
                assertFalse(owner.canConsume(prior, prior.ctx));
                var eventHandlers = processor.getClass().getDeclaredField("eventHandlers");
                eventHandlers.setAccessible(true);
                assertSame(b.assets().eventHandlers(), eventHandlers.get(processor));
                var switched = owner.produce(0.25f, RenderContext.levelImmutable());
                assertNotNull(switched);
                assertEquals(b.textureId(), switched.texture);
                assertNotEquals(a.textureId(), switched.texture);
                assertEquals(updates, updates(probe, component).size());
                harness.player.tickCount += 2;
                harness.player.xo = 2;
                positionField.set(harness.player, new net.minecraft.world.phys.Vec3(6, 0, 0));
                assertNotNull(owner.produce(0.25f, RenderContext.levelImmutable()));
                assertEquals(2.5f, position.positionValue(0, true));
                assertEquals(time, updates(probe, component).get(updates)[0]);
                assertTrue(player.getAnimationContext().animTime() > animationTime);
                var sampledAttributes = geometry.getBoneAttributes().clone();
                owner.selectTexture("a", a);
                assertSame(a, owner.getModelRenderTarget());
                assertSame(component, owner.exactComponent(probe).orElseThrow());
                assertSame(bone, geometry.getSortedBones().get(0));
                assertSame(originalBone, bone.getBoneData());
                assertArrayEquals(sampledAttributes, geometry.getBoneAttributes());
                assertSame(queue, player.getActiveBoneAnimQueues().get(0));
                assertEquals(3, count(probe, owner, "SET_RENDER_TARGET"));
                owner.selectTexture("alias-a", a);
                assertEquals(3, count(probe, owner, "SET_RENDER_TARGET"));
            } finally {
                positionField.set(harness.player, previousPosition);
                harness.player.xo = previousXo;
                owner.retire();
            }
        }
    }

    private static void verifyFailure(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback, Class<?> probe, ComponentLifecycleException.Phase phase)
        throws Exception {
        var owner = new Owner(harness.player);
        var other = new Owner(harness.player);
        try {
            owner.resources.target(0, fallback, fixture.model);
            other.install(fixture.model, module);
            var otherComponent = other.exactComponent(probe).orElseThrow();
            var otherTarget = other.getModelRenderTarget();
            assertNotNull(other.produce(0.25f, RenderContext.levelImmutable()));
            probe.getField("failedOwner").set(null, owner);
            probe.getField("failPhase").set(null, phase.name());
            var target = owner.resources.target(1, module, fixture.model);
            owner.select(1);
            DemandFixtures.advance(owner);
            Object component = owner.exactComponent(probe).orElse(null);
            OwnerResourceFixtures.Lease installed = owner.resources.leases.stream()
                .filter(lease -> lease.target == target && lease.closes == 0).findFirst().orElse(null);
            if (installed != null) {
                assertNotNull(component);
                installed.onClose = () -> {
                    try {
                        assertNull(probe.getField("borrowed").get(component), "lease closed before endEval");
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                };
                assertNull(owner.produce(0.25f, RenderContext.levelImmutable()));
                assertEquals(1, installed.closes);
                if (phase == ComponentLifecycleException.Phase.BEGIN_EVAL)
                    assertEquals(0, count(probe, owner, "END_EVAL"));
                else
                    assertEquals(1, count(probe, owner, "END_EVAL"));
            }
            for (int i = 0;
                i < 3 && owner.getInstallationRelation() != AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE;
                i++)
                DemandFixtures.advance(owner);
            assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, owner.getInstallationRelation(),
                phase.name());
            assertTrue(owner.exactComponent(probe).isEmpty());
            int constructions = count(probe, owner, "CONSTRUCT");
            int hookCalls = count(probe, owner, phase.name());
            int acquisitions = owner.resources.acquisitions;
            for (int i = 0; i < 3; i++) {
                harness.player.tickCount++;
                assertNotNull(owner.produce(0.5f, RenderContext.levelImmutable()));
            }
            assertEquals(constructions, count(probe, owner, "CONSTRUCT"));
            assertEquals(hookCalls, count(probe, owner, phase.name()));
            assertEquals(acquisitions, owner.resources.acquisitions);
            assertSame(otherTarget, other.getModelRenderTarget());
            assertSame(otherComponent, other.exactComponent(probe).orElseThrow());
            assertNotNull(other.produce(0.75f, RenderContext.levelImmutable()));
        } finally {
            probe.getField("failPhase").set(null, "");
            probe.getField("failedOwner").set(null, null);
            owner.retire();
            other.retire();
        }
    }

    private static void verifyDefaultFailure(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule fallback) throws Exception {
        var owner = new Owner(harness.player);
        var position = net.minecraft.world.entity.Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        var previousPosition = position.get(harness.player);
        try {
            var target = owner.resources.target(0, fallback, fixture.model);
            owner.select(0);
            DemandFixtures.advance(owner);
            assertSame(target, owner.getModelRenderTarget());
            var lease = owner.resources.leases.stream().filter(item -> item.closes == 0).findFirst().orElseThrow();
            assertNotNull(owner.produce(0.25f, RenderContext.levelImmutable()));
            int acquisitions = owner.resources.acquisitions;
            position.set(harness.player, null);
            harness.player.tickCount++;
            var failure = assertThrows(ComponentLifecycleException.class,
                () -> owner.produce(0.5f, RenderContext.levelImmutable()));
            assertEquals(com.elfmcys.ysm.expression.host.builtin.PositionComponent.class, failure.declaration());
            assertEquals(ComponentLifecycleException.Phase.BEGIN_EVAL, failure.phase());
            assertEquals(1, lease.closes);
            assertFalse(owner.hasInstalledModel());
            position.set(harness.player, previousPosition);
            assertNull(owner.produce(0.5f, RenderContext.levelImmutable()));
            int reports = owner.resources.failureReports;
            for (int i = 0; i < 3; i++) {
                harness.player.tickCount++;
                assertNull(owner.produce(0.5f, RenderContext.levelImmutable()));
            }
            assertEquals(acquisitions, owner.resources.acquisitions);
            assertEquals(reports, owner.resources.failureReports);
            assertFalse(owner.resources.failedContentIds.contains(OwnerResourceFixtures.hash(0)));
        } finally {
            position.set(harness.player, previousPosition);
            owner.retire();
        }
    }

    private static void verifyResetAndAsync(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback, Class<?> probe) throws Exception {
        var owner = new Owner(harness.player);
        try {
            owner.resources.target(0, fallback, fixture.model);
            owner.install(fixture.model, module);
            Object component = owner.exactComponent(probe).orElseThrow();
            var lease = owner.resources.leases.get(0);
            probe.getField("beginCallback").set(component, (Runnable)() -> {
                assertThrows(IllegalStateException.class, owner::reset);
                assertEquals(0, lease.closes);
            });
            assertNotNull(owner.produce(0.25f, RenderContext.levelImmutable()));
            owner.reset();
            assertEquals(1, lease.closes);
            assertEquals(1, count(probe, owner, "END_EVAL"));
            assertNull(probe.getField("borrowed").get(component));
            assertEquals(0, owner.resources.failureReports);
            DemandFixtures.advance(owner);
            component = owner.exactComponent(probe).orElseThrow();
            Object activeComponent = component;
            var activeLease =
                owner.resources.leases.stream().filter(item -> item.closes == 0).findFirst().orElseThrow();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            probe.getField("updateCallback").set(component, (Runnable)() -> {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS))
                        throw new AssertionError("worker gate timed out");
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            });
            harness.player.tickCount++;
            FirstPersonOwnerIntegrationTest.startAsync(owner, 0.5f);
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            var lifecycleField = AnimatableEntity.class.getDeclaredField("lifecycle");
            lifecycleField.setAccessible(true);
            var taskField = EntityExecutionLifecycle.class.getDeclaredField("asyncTask");
            taskField.setAccessible(true);
            var task = (Future<?>)taskField.get(lifecycleField.get(owner));
            activeLease.onClose = () -> {
                assertTrue(task.isDone(), "lease closed before worker terminal");
                try {
                    assertNull(probe.getField("borrowed").get(activeComponent));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            };
            var retiringThread = Thread.currentThread();
            var gate = CompletableFuture.runAsync(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (retiringThread.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
                        Thread.onSpinWait();
                    assertEquals(Thread.State.WAITING, retiringThread.getState());
                    assertEquals(0, activeLease.closes);
                    assertFalse(task.isDone());
                } finally {
                    release.countDown();
                }
            });
            owner.retire();
            gate.get(30, TimeUnit.SECONDS);
            assertEquals(1, activeLease.closes);
            assertNull(probe.getField("borrowed").get(component));
            assertFalse(owner.hasUnfinishedWork());
            assertEquals(0, owner.resources.failureReports);
        } finally {
            owner.retire();
        }
    }

    private static void verifyAsyncFailure(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback, Class<?> probe) throws Exception {
        var owner = new Owner(harness.player);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            owner.resources.target(0, fallback, fixture.model);
            owner.install(fixture.model, module);
            Object component = owner.exactComponent(probe).orElseThrow();
            var lease = owner.resources.leases.stream().filter(item -> item.closes == 0).findFirst().orElseThrow();
            probe.getField("updateCallback").set(component, (Runnable)() -> {
                entered.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS))
                        throw new AssertionError("worker failure gate timed out");
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                throw new IllegalStateException("probe-async-update");
            });
            harness.player.tickCount++;
            FirstPersonOwnerIntegrationTest.startAsync(owner, 0.5f);
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            var lifecycleField = AnimatableEntity.class.getDeclaredField("lifecycle");
            lifecycleField.setAccessible(true);
            var taskField = EntityExecutionLifecycle.class.getDeclaredField("asyncTask");
            taskField.setAccessible(true);
            var task = (Future<?>)taskField.get(lifecycleField.get(owner));
            lease.onClose = () -> {
                assertTrue(task.isDone(), "failed worker must be terminal before lease close");
                try {
                    assertNull(probe.getField("borrowed").get(component));
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            };
            assertEquals(0, lease.closes);
            release.countDown();
            owner.waitForAsyncUpdate();
            assertTrue(task.isDone());
            assertFalse(owner.hasUnfinishedWork());
            assertEquals(1, lease.closes);
            assertNull(probe.getField("borrowed").get(component));
            assertEquals(count(probe, owner, "BEGIN_EVAL"), count(probe, owner, "END_EVAL"));
            Throwable failure = assertThrows(ExecutionException.class, () -> task.get(30, TimeUnit.SECONDS)).getCause();
            while (!(failure instanceof ComponentLifecycleException) && failure.getCause() != null)
                failure = failure.getCause();
            var componentFailure = assertInstanceOf(ComponentLifecycleException.class, failure);
            assertEquals(probe, componentFailure.declaration());
            assertEquals(ComponentLifecycleException.Phase.UPDATE, componentFailure.phase());
            for (int i = 0;
                i < 3 && owner.getInstallationRelation() != AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE;
                i++)
                DemandFixtures.advance(owner);
            assertEquals(AnimatableEntity.InstallationRelation.DEFAULT_SUBSTITUTE, owner.getInstallationRelation());
            assertTrue(owner.exactComponent(probe).isEmpty());
            int updates = count(probe, owner, "UPDATE");
            assertNotNull(owner.produce(0.75f, RenderContext.levelImmutable()));
            assertEquals(updates, count(probe, owner, "UPDATE"));
            assertFalse(owner.resources.failedContentIds.contains(OwnerResourceFixtures.hash(0)));
        } finally {
            release.countDown();
            owner.retire();
        }
    }

    private static int count(Class<?> probe, AnimatableEntity<?> owner, String phase) throws Exception {
        return (int)probe.getMethod("count", AnimatableEntity.class, String.class).invoke(null, owner, phase);
    }

    private static void verifyDemandCleanupFailures(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback, Class<?> probe) throws Exception {
        for (String path : List.of("retire", "restriction", "failed-call")) {
            var owner = new Owner(harness.player);
            OwnerResourceFixtures.Lease pendingLease = null;
            try {
                owner.resources.target(0, fallback, fixture.model);
                owner.install(fixture.model, module);
                var output = owner.produce(0.25f, RenderContext.levelImmutable());
                assertNotNull(output);
                assertTrue(output.modelState.isValid());
                var installedLease =
                    owner.resources.leases.stream().filter(item -> item.closes == 0).findFirst().orElseThrow();
                var pendingTarget = owner.resources.target(2, module, fixture.model);
                var pending = owner.resources.delay(OwnerResourceFixtures.hash(2));
                owner.select(2);
                DemandFixtures.advance(owner);
                var scope = owner.demandScope();
                assertTrue(scope.hasUnfinishedWork());
                pendingLease = new OwnerResourceFixtures.Lease(
                    owner.resources.resourceRequest(OwnerResourceFixtures.hash(2), "player", ""), pendingTarget,
                    () -> false);
                var demandFailure = new IllegalStateException("pending-demand-close");
                var installationFailure = new IllegalStateException("installed-lease-close");
                pendingLease.onClose = () -> {
                    throw demandFailure;
                };
                installedLease.onClose = () -> {
                    throw installationFailure;
                };
                pending.complete(Optional.of(pendingLease));
                var originalFailure = new IllegalArgumentException("owner-call");
                var failure = assertThrows(RuntimeException.class, () -> {
                    switch (path) {
                        case "retire" -> owner.retire();
                        case "restriction" -> {
                            owner.permitted = false;
                            owner.checkModelUpdate();
                        }
                        default -> owner.failWhileEndingUse(originalFailure);
                    }
                }, path);
                if (path.equals("failed-call")) {
                    assertSame(originalFailure, failure);
                    assertArrayEquals(new Throwable[] {demandFailure}, failure.getSuppressed());
                } else {
                    assertSame(demandFailure, failure);
                }
                assertArrayEquals(new Throwable[] {installationFailure}, demandFailure.getSuppressed());
                assertEquals(1, pendingLease.closes);
                assertEquals(1, installedLease.closes);
                assertTrue(scope.isClosed());
                assertFalse(owner.hasUnfinishedWork());
                assertFalse(output.modelState.isValid());
                assertFalse(owner.hasInstalledModel());
                assertTrue(owner.getExpressionRuntime().module().isEmpty());
                assertTrue(owner.exactComponent(probe).isEmpty());
                assertEquals(1, owner.ended);
                if (!path.equals("failed-call")) {
                    owner.checkModelUpdate();
                    assertEquals(1, owner.ended);
                }
            } finally {
                if (pendingLease != null)
                    pendingLease.onClose = () -> {
                    };
                owner.resources.leases.forEach(lease -> lease.onClose = () -> {
                });
                owner.retire();
            }
        }
    }

    private static void verifyDoublePendingDemandCleanup(FirstPersonOwnerIntegrationTest harness,
        FirstPersonOwnerIntegrationTest.Fixture fixture, RenderTargetExpressionModule module,
        RenderTargetExpressionModule fallback) throws Exception {
        var owner = new Owner(harness.player);
        var primaryTarget = owner.resources.target(2, module, fixture.model);
        var fallbackTarget = owner.resources.target(0, fallback, fixture.model);
        var primaryResult = owner.resources.delay(OwnerResourceFixtures.hash(2));
        var fallbackResult = owner.resources.delay(OwnerResourceFixtures.hash(0));
        var primaryLease = new OwnerResourceFixtures.Lease(
            owner.resources.resourceRequest(OwnerResourceFixtures.hash(2), "player", ""), primaryTarget, () -> false);
        var fallbackLease = new OwnerResourceFixtures.Lease(owner.resources.defaultResourceRequest("player"),
            fallbackTarget, () -> false);
        try {
            owner.select(2);
            DemandFixtures.advance(owner);
            assertFalse(owner.hasInstalledModel());
            var scope = owner.demandScope();
            assertTrue(scope.hasUnfinishedWork());
            var primaryFailure = new IllegalStateException("primary-demand-close");
            var fallbackFailure = new IllegalStateException("fallback-demand-close");
            primaryLease.onClose = () -> {
                throw primaryFailure;
            };
            fallbackLease.onClose = () -> {
                throw fallbackFailure;
            };
            primaryResult.complete(Optional.of(primaryLease));
            fallbackResult.complete(Optional.of(fallbackLease));
            assertSame(primaryFailure, assertThrows(IllegalStateException.class, owner::retire));
            assertEquals(1, primaryLease.closes);
            assertEquals(1, fallbackLease.closes);
            assertArrayEquals(new Throwable[] {fallbackFailure}, primaryFailure.getSuppressed());
            assertTrue(scope.isClosed());
            assertFalse(owner.hasUnfinishedWork());
            assertTrue(owner.getExpressionRuntime().module().isEmpty());
            assertEquals(1, owner.ended);
        } finally {
            primaryLease.onClose = () -> {
            };
            fallbackLease.onClose = () -> {
            };
            primaryLease.close();
            fallbackLease.close();
            owner.retire();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> ticks(Class<?> probe, Object component) throws Exception {
        return (List<int[]>)probe.getField("ticks").get(component);
    }

    @SuppressWarnings("unchecked")
    private static List<float[]> updates(Class<?> probe, Object component) throws Exception {
        return (List<float[]>)probe.getField("updates").get(component);
    }

    private static final class Owner extends FirstPersonOwnerIntegrationTest.Timeline {
        int limit = 120;
        int ended;
        String selectedTexture = "";
        com.elfmcys.ysm.geckolib3.core.builder.Animation animation;
        com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController<Owner> controller;

        Owner(net.minecraft.client.player.LocalPlayer player) throws Exception {
            super(player);
        }

        protected String fallbackRenderTargetId() {
            return "player";
        }

        protected String requestedTextureName() {
            return selectedTexture;
        }

        public net.minecraft.resources.ResourceLocation getTextureLocation() {
            var target = getModelRenderTarget();
            return target == null ? super.getTextureLocation() : target.textureId();
        }

        public com.elfmcys.ysm.geckolib3.core.builder.Animation getAnimation(String name) {
            return animation;
        }

        protected void prepareAnimationControllers(com.elfmcys.ysm.model.resource.client.ModelRenderTarget target,
            com.elfmcys.ysm.geckolib3.core.manager.AnimationData animations) {
            super.prepareAnimationControllers(target, animations);
            if (controller != null)
                animations.addAnimationController(controller);
        }

        void selectTexture(String texture, com.elfmcys.ysm.model.resource.client.ModelRenderTarget target) {
            selectedTexture = texture;
            resources.targets.put(target.modelHash(), target);
            updateModelHash(target.modelHash());
            DemandFixtures.advance(this);
        }

        public int getFrameRateLimit() {
            return limit <= 0 ? 120 : limit;
        }

        protected void onModelEnded() {
            ended++;
        }

        void failWhileEndingUse(RuntimeException failure) {
            inDrawBorrow(() -> {
                reset();
                throw failure;
            });
        }
    }
}
