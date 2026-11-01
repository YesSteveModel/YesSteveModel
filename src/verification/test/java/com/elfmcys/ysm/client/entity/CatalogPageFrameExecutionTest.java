package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.demand.ClientDemandCoordinator;
import com.elfmcys.ysm.client.event.ClientTickEvent;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.gui.GuiFrameTasks;
import com.elfmcys.ysm.client.gui.button.CatalogFrameTestAccess;
import com.elfmcys.ysm.client.gui.button.CatalogPageFrame;
import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.EventProgram;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IBoneAnimationQueue;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.model.resource.client.asset.CatalogFrameAssets;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class CatalogPageFrameExecutionTest {
    private FirstPersonOwnerIntegrationTest host;
    private int previousTick;

    @BeforeAll static void initialize() throws Exception { FirstPersonOwnerIntegrationTest.initializeHost(); }
    @AfterAll static void restore() throws Exception { FirstPersonOwnerIntegrationTest.restoreHost(); }
    @BeforeEach void create() throws Exception {
        host = new FirstPersonOwnerIntegrationTest();
        host.createHost();
        previousTick = ClientTickEvent.getTickCount();
    }
    @AfterEach void release() throws Exception {
        FirstPersonOwnerIntegrationTest.field(ClientTickEvent.class, "tickCount").setInt(null, previousTick);
        host.releaseHost();
    }

    @Test
    void actualCardWorkBindsPreparesImagesAndEvaluatesInOneTaskAndStillUpdatesEveryBoundCard(
        @TempDir Path root) throws Exception {
        try (var fixture = new FrameFixture(root)) {
            var first = fixture.card();
            var second = fixture.card();
            var third = fixture.card();
            var fourth = fixture.card();
            var fifth = fixture.card();
            var sixth = fixture.card();
            var seventh = fixture.card();
            var pageCards = List.of(first, second, third, fourth, fifth, sixth, seventh);
            fixture.activate(first);
            var trace = new Trace();
            fixture.observe(trace);
            fixture.run(pageCards, 2, 0.25f, trace);
            assertEquals(2, fixture.assets.decodes.get(), "Shared foreground and background decode once each");
            assertEquals(List.of("image", "image", "bind", "update"), trace.kinds(0));
            assertEquals(List.of("bind", "update"), trace.kinds(1));
            assertEquals(List.of("bind", "update"), trace.kinds(2));
            assertEquals(3, trace.runs.size());
            trace.assertInsideExactRun();
            assertNotSame(fixture.owners.get(0).getLoadedGeoModel(), fixture.owners.get(1).getLoadedGeoModel());
            assertSame(fixture.target, fixture.owners.get(0).getModelRenderTarget());
            assertSame(fixture.target, fixture.owners.get(1).getModelRenderTarget());
            assertPrepared(first);
            assertPrepared(second);
            assertPrepared(third);
            assertTrue(List.of(fourth, fifth, sixth, seventh).stream().allMatch(card -> card.prepared() == null));

            fixture.coordinator.tick();
            var next = new Trace();
            fixture.observe(next);
            fixture.run(pageCards, 2, 0.75f, next);
            assertEquals(6, next.runs.size(), "Three new preparations plus every already-bound card");
            assertPrepared(first);
            assertPrepared(second);
            assertPrepared(third);
            assertEquals(3, List.of(fourth, fifth, sixth, seventh).stream()
                .filter(card -> card.prepared() != null).count());
            assertEquals(3, next.events.stream().filter(event -> event.kind.equals("bind")).count());
            assertEquals(6, next.events.stream().filter(event -> event.kind.equals("update")).count());
            assertEquals(2, fixture.assets.decodes.get());
            next.assertInsideExactRun();
        }
    }

    @Test
    void firstCardPublicationCanCloseThePageWhileAnAcceptedSiblingStillOwnsItsEntity(
        @TempDir Path root) throws Exception {
        try (var fixture = new FrameFixture(root)) {
            var first = fixture.card();
            var sibling = fixture.card();
            fixture.activate(first);
            var trace = new Trace();
            fixture.observe(trace);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var siblingOwner = fixture.owners.get(1);
            trace.beforeUpdate = owner -> {
                if (owner == siblingOwner) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }
            };
            var published = new AtomicBoolean();
            fixture.assets.registering = () -> {
                if (published.compareAndSet(false, true)) {
                    try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    fixture.page.close();
                    first.close();
                    sibling.close();
                    assertTrue(siblingOwner.hasPendingUpdate());
                    assertThrows(IllegalStateException.class, siblingOwner::getLoadedGeoModel);
                    release.countDown();
                }
            };
            try {
                fixture.run(List.of(first, sibling), 2, 0.25f, trace);
            } finally { release.countDown(); }
            assertTrue(published.get());
            assertTrue(fixture.page.isClosed());
            for (var owner : fixture.owners) {
                assertFalse(owner.hasPendingUpdate());
                assertFalse(owner.hasInstalledModel());
                assertNull(owner.displayedModel());
                assertThrows(IllegalStateException.class, () -> owner.reserveGuiTask(execution -> null));
            }
            assertEquals(2, trace.runs.size());
            assertTrue(trace.runs.stream().allMatch(run -> run.joined && run.finished));
            trace.assertInsideExactRun();
        }
    }

    @Test
    void pointerRevocationDuringFirstPublicationWaitsForAcceptedTasksAndTheNextHoverCanReinstall(
        @TempDir Path root) throws Exception {
        try (var fixture = new FrameFixture(root)) {
            var first = fixture.card();
            var sibling = fixture.card();
            fixture.activate(first);
            var previousScopes = List.of(first.scope(), sibling.scope());
            var trace = new Trace();
            fixture.observe(trace);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var siblingOwner = fixture.owners.get(1);
            trace.beforeUpdate = owner -> {
                if (owner == siblingOwner) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }
            };
            var revoked = new AtomicBoolean();
            fixture.assets.registering = () -> {
                if (revoked.compareAndSet(false, true)) {
                    try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    var liveLeases = fixture.resources.leases.stream().filter(lease -> lease.closes == 0).toList();
                    assertFalse(liveLeases.isEmpty());
                    fixture.page.pointer(null);
                    assertTrue(previousScopes.stream().allMatch(ClientDemandCoordinator.EntityScope::isClosed));
                    assertTrue(siblingOwner.hasPendingUpdate());
                    assertGuiSealed(siblingOwner);
                    fixture.page.pointer(first.target());
                    fixture.clock.set(600);
                    fixture.page.advance();
                    assertNull(first.scope());
                    assertNull(sibling.scope());
                    assertTrue(liveLeases.stream().allMatch(lease -> lease.closes == 0));
                    release.countDown();
                }
            };
            try {
                fixture.run(List.of(first, sibling), 2, 0.25f, trace);
            } finally { release.countDown(); }
            assertTrue(revoked.get());
            for (var owner : fixture.owners) {
                assertFalse(owner.hasPendingUpdate());
                assertFalse(owner.isAssigned());
                assertFalse(owner.hasInstalledModel());
                assertNull(owner.displayedModel());
            }
            for (var card : List.of(first, sibling)) {
                assertNull(card.prepared());
                card.assertAnimationReferencesCleared();
            }
            assertTrue(trace.runs.stream().allMatch(run -> run.joined && run.finished));
            fixture.coordinator.tick();
            var next = new Trace();
            fixture.observe(next);
            fixture.run(List.of(first, sibling), 2, 0.75f, next);
            assertPrepared(first);
            assertPrepared(sibling);
            assertNotSame(previousScopes.get(0), first.scope());
            assertNotSame(previousScopes.get(1), sibling.scope());
            assertEquals(2, next.events.stream().filter(event -> event.kind.equals("bind")).count());
        }
    }

    @Test
    void guiComputeOutOfMemoryDropsOnlyThatPoseAndTheNextIndependentFrameCanUseTheInstallation(
        @TempDir Path root) throws Exception {
        try (var fixture = new FrameFixture(root)) {
            var card = fixture.card();
            fixture.activate(card);
            var trace = new Trace();
            fixture.observe(trace);
            var fail = new AtomicBoolean(true);
            trace.beforeUpdate = owner -> { if (fail.getAndSet(false)) throw new OutOfMemoryError("pose fixture"); };
            fixture.run(List.of(card), 1, 0.25f, trace);
            var owner = fixture.owners.get(0);
            assertNull(card.prepared());
            assertTrue(owner.hasMatchingInstallation());
            assertNull(owner.modelLoadFailure());
            assertFalse(owner.hasPendingUpdate());
            assertFalse(FirstPersonOwnerIntegrationTest.field(AnimatableEntity.class,
                "currentFrameExtracted").getBoolean(owner));
            fixture.run(List.of(card), 1, 0.75f, trace);
            assertPrepared(card);
            assertTrue(owner.hasMatchingInstallation());
            assertNull(owner.modelLoadFailure());
        }
    }

    static void verifyRetirement(FirstPersonOwnerIntegrationTest.FakePlayer subject, Path root) throws Exception {
        int previousTick = ClientTickEvent.getTickCount();
        try (var fixture = new FrameFixture(root)) {
            var card = fixture.card(subject);
            var owner = fixture.owners.get(0);
            fixture.page.pointer(card.target());
            fixture.clock.set(299);
            fixture.coordinator.tick();
            assertFalse(owner.isAssigned());
            assertFalse(owner.hasInstalledModel());
            assertEquals(0, fixture.resources.acquisitions);
            fixture.clock.set(300);
            fixture.coordinator.tick();
            assertFalse(owner.hasInstalledModel(), "Tick advances acquisition; CardWork owns installation");
            fixture.run(List.of(card), 1, 0.25f, new Trace());
            assertTrue(owner.hasInstalledModel());
            assertPrepared(card);
            var prepared = card.prepared();
            var draw = FirstPersonOwnerIntegrationTest.allocate(FirstPersonOwnerIntegrationTest.ProbePlayerRenderer.class);
            var output = new FirstPersonOwnerIntegrationTest.Probe();
            assertTrue(draw.renderPreparedReplacement(owner, prepared, null, 0, 0.25f,
                new com.mojang.blaze3d.vertex.PoseStack(), output, 123, () -> {}));
            assertFalse(output.vertices.isEmpty());
            var request = owner.assignment().request();
            try (var other = fixture.resources.findReady(request).orElseThrow()) {
                var oldScope = owner.demandScope();
                card.retainAnimationReference(fixture.target);
                fixture.page.pointer(null);
                assertTrue(oldScope.isClosed());
                assertFalse(owner.isAssigned());
                assertFalse(owner.hasInstalledModel());
                assertFalse(owner.hasUnfinishedWork());
                assertNull(owner.displayedModel());
                card.assertAnimationReferencesCleared();
                assertFalse(draw.renderPreparedReplacement(owner, prepared, null, 0, 0.25f,
                    new com.mojang.blaze3d.vertex.PoseStack(), new FirstPersonOwnerIntegrationTest.Probe(), 123, () -> {}));
                fixture.page.pointer(card.target());
                fixture.clock.set(599);
                fixture.coordinator.tick();
                assertFalse(owner.hasInstalledModel());
                fixture.clock.set(600);
                fixture.coordinator.tick();
                assertFalse(owner.hasInstalledModel());
                fixture.run(List.of(card), 1, 0.75f, new Trace());
                assertTrue(owner.hasInstalledModel());
                assertPrepared(card);
                assertNotSame(oldScope, owner.demandScope());
                fixture.page.close();
                assertNull(owner.displayedModel());
                card.assertAnimationReferencesCleared();
                assertInstanceOf(AcquireResult.Ready.class, other.poll());
            }
        } finally {
            FirstPersonOwnerIntegrationTest.field(ClientTickEvent.class, "tickCount").setInt(null, previousTick);
        }
    }

    private static void assertPrepared(CatalogFrameTestAccess.Card card) {
        var data = card.prepared();
        assertNotNull(data);
        assertTrue(data.modelState.isValid());
        assertTrue(data.modelState.getNativeState().getTotalVertexCount() > 0);
    }

    private static void assertGuiSealed(CustomGuiPlayerEntity owner) {
        assertThrows(IllegalStateException.class, owner::getPreviewInfo);
        assertThrows(IllegalStateException.class, () -> owner.getPhysicsManager(null));
        assertThrows(IllegalStateException.class, () -> owner.setAllowEmitting(true));
        assertThrows(IllegalStateException.class, () ->
            owner.setResourceDemandMode(com.elfmcys.ysm.client.demand.ModelResourceDemand.Mode.READY));
    }

    private static final class FrameFixture implements AutoCloseable {
        final CatalogFrameAssets assets;
        final FirstPersonOwnerIntegrationTest.Fixture geometry;
        final com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module;
        final OwnerResourceFixtures resources = new OwnerResourceFixtures();
        final AtomicLong clock = new AtomicLong();
        final ClientDemandCoordinator coordinator = resources.coordinator(clock::get);
        final ClientDemandCoordinator.PageScope page = coordinator.openPage("catalog-frame-test");
        final CatalogPageFrame frame;
        final ModelRenderTarget target;
        final List<CustomGuiPlayerEntity> owners = new ArrayList<>();
        final List<CatalogFrameTestAccess.Card> cards = new ArrayList<>();
        final BiConsumer<CustomPlayerEntity, AnimationData> baseFactory;
        volatile Trace trace;

        FrameFixture(Path root) throws Exception {
            assets = new CatalogFrameAssets(root);
            geometry = new FirstPersonOwnerIntegrationTest.Fixture("parts-a",
                com.elfmcys.ysm.client.model.locator.PlayerLocator.get());
            module = TestExpressionModules.create();
            target = resources.target(assets.entry.modelHash(), module, ResourceLocation.tryParse("ysm:frame"),
                null, geometry.model);
            FirstPersonOwnerIntegrationTest.field(ModelRenderTarget.class, "modelInfo")
                .set(target, assets.entry.displayRepresentation().view().getMetadata());
            baseFactory = target.playerResources().playerControllerFactory();
            FirstPersonOwnerIntegrationTest.field(PlayerModelResources.class, "playerControllerFactory")
                .set(target.playerResources(), (BiConsumer<CustomPlayerEntity, AnimationData>)(owner, data) -> {
                    if (trace != null) trace.event("bind", owner);
                    baseFactory.accept(owner, data);
                    data.addAnimationController(new ObservationController(owner, () -> trace));
                });
            frame = new CatalogPageFrame(page, assets::batch);
            page.action(frame);
        }

        CatalogFrameTestAccess.Card card() throws Exception {
            var player = FirstPersonOwnerIntegrationTest.allocate(FirstPersonOwnerIntegrationTest.FakePlayer.class);
            FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.Entity.class, "position")
                .set(player, net.minecraft.world.phys.Vec3.ZERO);
            FirstPersonOwnerIntegrationTest.field(net.minecraft.world.entity.LivingEntity.class, "walkAnimation")
                .set(player, new net.minecraft.world.entity.WalkAnimationState());
            return card(player);
        }

        CatalogFrameTestAccess.Card card(FirstPersonOwnerIntegrationTest.FakePlayer player) throws Exception {
            FirstPersonOwnerIntegrationTest.field(Player.class, "inventory").set(player, new Inventory(player));
            var constructor = CustomGuiPlayerEntity.class.getDeclaredConstructor(Player.class);
            constructor.setAccessible(true);
            var owner = constructor.newInstance(player);
            resources.attach(owner, clock::get);
            owners.add(owner);
            var card = CatalogFrameTestAccess.card(frame, page, assets.entry, owner);
            cards.add(card);
            return card;
        }

        void activate(CatalogFrameTestAccess.Card card) {
            page.pointer(card.target());
            clock.set(300);
            coordinator.tick();
            assertTrue(cards.stream().allMatch(value -> value.scope() != null && value.scope().hasReadyInstallation()));
        }

        void observe(Trace next) {
            trace = next;
            assets.decoding = () -> next.event("image", null);
        }

        void run(List<CatalogFrameTestAccess.Card> active, int poolSize, float partial, Trace observed) throws Exception {
            var workers = new ArrayList<Thread>();
            FirstPersonOwnerIntegrationTest.field(ClientTickEvent.class, "tickCount")
                .setInt(null, ClientTickEvent.getTickCount() + 1);
            try (var ignored = page.beginFrame()) {
                active.forEach(card -> card.input(partial, clock.get()));
                var jobs = CatalogFrameTestAccess.plan(frame, active, poolSize);
                owners.stream().filter(AnimatableEntity::hasPendingUpdate)
                    .forEach(CatalogPageFrameExecutionTest::assertGuiSealed);
                observed.afterRun = () -> owners.stream().filter(AnimatableEntity::hasPendingUpdate)
                    .forEach(CatalogPageFrameExecutionTest::assertGuiSealed);
                var wrapped = new ArrayList<GuiFrameTasks.Job>();
                for (var job : jobs) wrapped.add(observed.wrap(job));
                assertNull(GuiFrameTasks.execute(wrapped, job -> {
                    var thread = new Thread(job, "catalog-frame-fixture");
                    workers.add(thread);
                    thread.start();
                }));
            } finally {
                for (var worker : workers) {
                    worker.join(5000);
                    assertFalse(worker.isAlive());
                }
            }
        }

        @Override public void close() {
            page.close();
            cards.forEach(CatalogFrameTestAccess.Card::close);
            owners.forEach(AnimatableEntity::retire);
            coordinator.close();
            module.close();
            geometry.close();
            assets.close();
        }
    }

    private static final class ObservationController implements IAnimationController<CustomPlayerEntity> {
        private final CustomPlayerEntity owner;
        private final java.util.function.Supplier<Trace> trace;
        ObservationController(CustomPlayerEntity owner, java.util.function.Supplier<Trace> trace) {
            this.owner = owner;
            this.trace = trace;
        }
        public String getName() { return "card-work-observer"; }
        public String getStateName() { return "running"; }
        public void updateModel(List<BoneTopLevelSnapshot> bones, Object2ReferenceMap<String, List<EventProgram>> events,
            Function<String, AnimationControllerData> definitions) {}
        public void process(AnimationEvent<CustomPlayerEntity> event, EntityExpressionRuntime.Evaluation evaluation,
            boolean executeEvents) {
            var current = trace.get();
            if (current != null) {
                var gui = (CustomGuiPlayerEntity)owner;
                assertNotNull(gui.getPreviewInfo());
                gui.setAllowEmitting(false);
                assertThrows(IllegalStateException.class, () ->
                    gui.setResourceDemandMode(com.elfmcys.ysm.client.demand.ModelResourceDemand.Mode.READY));
                current.event("update", owner);
                current.beforeUpdate.accept(owner);
            }
        }
        public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {}
        public void clear() {}
    }

    private static final class Trace {
        final List<Run> runs = new ArrayList<>();
        final List<Event> events = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<Thread, Run> executing = new ConcurrentHashMap<>();
        Consumer<CustomPlayerEntity> beforeUpdate = owner -> {};
        Runnable afterRun = () -> {};
        GuiFrameTasks.Job wrap(GuiFrameTasks.Job actual) {
            var run = new Run(runs.size(), actual);
            runs.add(run);
            return new GuiFrameTasks.Job() {
                public void run() {
                    run.start = System.nanoTime();
                    assertNull(executing.put(Thread.currentThread(), run));
                    try { actual.run(); }
                    finally {
                        assertSame(run, executing.remove(Thread.currentThread()));
                        run.end = System.nanoTime();
                        afterRun.run();
                    }
                }
                public void join() { actual.join(); run.joined = true; }
                public void reject() { actual.reject(); }
                public void publish() { actual.publish(); }
                public void finish() { actual.finish(); run.finished = true; }
            };
        }
        void event(String kind, CustomPlayerEntity owner) {
            var run = executing.get(Thread.currentThread());
            assertNotNull(run, kind + " escaped actual CardWork.run");
            events.add(new Event(kind, run, Thread.currentThread(), System.nanoTime(), owner));
        }
        List<String> kinds(int id) {
            return events.stream().filter(event -> event.run.id == id).map(Event::kind).toList();
        }
        void assertInsideExactRun() {
            assertFalse(events.isEmpty());
            for (var event : events) {
                assertTrue(event.run.start <= event.time && event.time <= event.run.end);
                assertNotNull(event.run.actual);
                assertTrue(event.run.joined);
            }
            assertTrue(executing.isEmpty());
        }
    }

    private static final class Run {
        final int id;
        final GuiFrameTasks.Job actual;
        volatile long start;
        volatile long end;
        boolean joined;
        boolean finished;
        Run(int id, GuiFrameTasks.Job actual) { this.id = id; this.actual = actual; }
    }
    private record Event(String kind, Run run, Thread thread, long time, CustomPlayerEntity owner) {}
}
