package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.expression.aot.VoidProgram;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerState;
import com.elfmcys.ysm.geckolib3.core.controller.AnimationPlayer;
import com.elfmcys.ysm.geckolib3.core.controller.BedrockAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.transition.LinearBlendTransition;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@EnabledIfEnvironmentVariable(named = "YSM_FIRST_PERSON_FIXTURES", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class ControllerAnimationCacheIntegrationTest {
    private FirstPersonOwnerIntegrationTest host;

    @BeforeAll static void initialize() throws Exception { FirstPersonOwnerIntegrationTest.initializeHost(); }
    @AfterAll static void restore() throws Exception { FirstPersonOwnerIntegrationTest.restoreHost(); }
    @BeforeEach void create() throws Exception {
        host = new FirstPersonOwnerIntegrationTest();
        host.createHost();
    }
    @AfterEach void release() { host.releaseHost(); }

    @Test
    void codedSwitchReplayAndProvenAbsenceUseTheControllerCacheWithoutReadBatches() throws Exception {
        var queries = new AtomicInteger();
        var a = animation("a");
        var b = animation("b");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var backing = AnimationStore.eager(Map.of("a", a, "b", b)).shared();
            var source = counted(backing, queries)) {
            var owner = new CacheOwner(host.player, source, null);
            try {
                owner.install(fixture.model, module);
                owner.step("a");
                assertSame(a, player(owner.controller).getCurrentAnim());
                owner.step("b");
                assertSame(b, player(owner.controller).getCurrentAnim());
                owner.step("a");
                assertSame(a, player(owner.controller).getCurrentAnim());
                assertEquals(2, queries.get());
                assertFalse(owner.readBatchObserved, "Warm A comes from controller state");
                var time = player(owner.controller).getAnimationContext().animTime();
                owner.step("a");
                assertTrue(player(owner.controller).getAnimationContext().animTime() > time);
                assertEquals(2, queries.get());
                ((CodedAnimationController<?>)owner.controller).indicateReload();
                owner.step("a");
                assertEquals(0, player(owner.controller).getAnimationContext().animTime());
                assertEquals(2, queries.get(), "Explicit replay does not discard resolved content");
                owner.step("missing");
                owner.step("a");
                owner.step("missing");
                assertEquals(3, queries.get());
                assertNull(player(owner.controller).getCurrentAnim());
                assertFalse(owner.readBatchObserved);
                owner.getAnimation("a");
                assertEquals(4, queries.get(), "Direct resource queries are outside the controller cache");
                System.out.println("T02 coded: A/B/A/replay=2, plus repeated proven absence=3, direct bypass=4");
            } finally { owner.retire(); }
        }
    }

    @Test
    void bedrockPlayersShareResolutionButKeepTheirOwnPlayback() throws Exception {
        var queries = new AtomicInteger();
        var a = animation("a");
        var b = animation("b");
        var definition = definition("active", "a", "a", "missing", "missing");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var backing = AnimationStore.eager(Map.of("a", a, "b", b)).shared();
            var source = counted(backing, queries)) {
            var owner = new CacheOwner(host.player, source, definition);
            try {
                owner.install(fixture.model, module);
                owner.step(null);
                var players = bedrockPlayers(owner.controller);
                assertEquals(4, players.size());
                assertNotSame(players.get(0), players.get(1));
                assertSame(a, players.get(0).getCurrentAnim());
                assertSame(a, players.get(1).getCurrentAnim());
                assertNull(players.get(2).getCurrentAnim());
                assertEquals(2, queries.get());
                players.get(0).setAnimation("b");
                owner.step(null);
                assertSame(b, players.get(0).getCurrentAnim());
                assertSame(a, players.get(1).getCurrentAnim());
                players.get(0).setAnimation("a");
                owner.step(null);
                assertSame(a, players.get(0).getCurrentAnim());
                assertEquals(3, queries.get());
                assertFalse(owner.readBatchObserved);
                System.out.println("T02 Bedrock: four players / two names=2, switch B/A=3");
            } finally { owner.retire(); }
        }
    }

    @Test
    void bedrockRetriesTemporaryFailureWithoutAStateTransitionOrRestartingFallback() throws Exception {
        var loads = new AtomicInteger();
        var readable = new AtomicBoolean(false);
        var inherited = animation("fallback");
        var recovered = animation("recovered");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var fallback = AnimationStore.eager(Map.of("a", inherited));
            var source = AnimationStore.lazyBound(List.of("a"), name -> {
                loads.incrementAndGet();
                if (!readable.get()) throw new IOException("temporary");
                return recovered;
            }, fallback, ignored -> ModelResourceFailureGate.none()).shared()) {
            var owner = new CacheOwner(host.player, source, definition("active", "a"));
            try {
                owner.install(fixture.model, module);
                owner.step(null);
                var player = bedrockPlayers(owner.controller).get(0);
                assertSame(inherited, player.getCurrentAnim());
                owner.step(null);
                assertSame(inherited, player.getCurrentAnim());
                assertTrue(player.getAnimationContext().animTime() > 0,
                    "Retrying the same fallback must preserve its timeline");
                readable.set(true);
                owner.step(null);
                assertSame(recovered, player.getCurrentAnim());
                var verifiedLoads = loads.get();
                readable.set(false);
                owner.step(null);
                assertEquals(verifiedLoads, loads.get(), "Verified content remains usable");
                assertSame(recovered, player.getCurrentAnim());
            } finally { owner.retire(); }
        }
    }

    @Test
    void legacyHandLoopUsesResolutionOriginEvenWhenAnimationContentIsShared() throws Exception {
        var shared = animation("shared");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var defaults = AnimationStore.eager(Map.of("a", shared)).shared();
            var inherited = AnimationStore.lazyBound(List.of(), name -> null, defaults,
                ignored -> ModelResourceFailureGate.none());
            var local = AnimationStore.eager(Map.of("a", shared))) {
            var owner = new CacheOwner(host.player, inherited, null);
            owner.compatLoop = true;
            try {
                owner.install(fixture.model, module);
                owner.step("a");
                var player = player(owner.controller);
                assertSame(shared, player.getCurrentAnim());
                assertEquals(LoopType.LOOP, value(player, "currentLoopType"));
                var cached = (AnimationStore.Resolution)cacheEntries(owner.controller).get("a");
                assertTrue(cached.fallback());
                ((CodedAnimationController<?>)owner.controller).indicateReload();
                owner.step("a");
                assertEquals(LoopType.LOOP, value(player, "currentLoopType"));
                owner.rebind(local);
                ((CodedAnimationController<?>)owner.controller).indicateReload();
                owner.step("a");
                assertSame(shared, player.getCurrentAnim());
                assertEquals(LoopType.PLAY_ONCE, value(player, "currentLoopType"));
                assertFalse(((AnimationStore.Resolution)cacheEntries(owner.controller).get("a")).fallback());
            } finally { owner.retire(); }
        }
    }

    @Test
    void dynamicNamesRemainUnboundedUntilSourceInvalidationOrEndOfUse() throws Exception {
        var queries = new AtomicInteger();
        var names = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> "missing-" + i).toList();
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var backing = AnimationStore.eager(Map.of()).shared();
            var source = counted(backing, queries)) {
            var owner = new CacheOwner(host.player, source, null);
            try {
                owner.install(fixture.model, module);
                var controller = (CodedAnimationController<?>)owner.controller;
                for (var name : names) controller.setAnimation(name);
                assertEquals(1000, queries.get());
                assertEquals(1000, cacheEntries(controller).size());
                for (var name : names) controller.setAnimation(name);
                assertEquals(1000, queries.get());
                owner.rebind(source);
                assertEquals(0, cacheEntries(controller).size());
                assertNull(cacheSource(controller));
                for (var name : names) controller.setAnimation(name);
                assertEquals(2000, queries.get());
                owner.retire();
                assertEquals(0, cacheEntries(controller).size());
                assertNull(cacheSource(controller));
                assertNull(source.get("missing-0"));
                System.out.println("T02 retention: 1000 distinct absent names -> 1000 entries; invalidation/end -> 0");
            } finally { owner.retire(); }
        }
    }

    @Test
    void childControllerOwnsItsCacheAndRebindingClearsTheWholeTree() throws Exception {
        var queries = new AtomicInteger();
        var a = animation("a");
        var found = animation("found");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var backing = AnimationStore.eager(Map.of("a", a)).shared();
            var nextBacking = AnimationStore.eager(Map.of("a", a, "missing", found)).shared();
            var source = counted(backing, queries)) {
            var owner = new CacheOwner(host.player, source, definition("ysm-entry-sub"));
            owner.subDefinitions.put("test.sub", definition("active", "a", "missing"));
            try {
                owner.install(fixture.model, module);
                owner.step(null);
                var child = value(owner.controller, "subController");
                assertNotNull(child);
                assertEquals(2, queries.get());
                assertTrue(cacheEntries(owner.controller).isEmpty());
                assertEquals(2, cacheEntries(child).size());
                owner.step(null);
                assertEquals(2, queries.get());
                owner.rebind(nextBacking);
                assertNull(cacheSource(child));
                assertTrue(cacheEntries(child).isEmpty());
                owner.step(null);
                var players = bedrockPlayers(child);
                assertSame(a, players.get(0).getCurrentAnim());
                assertSame(found, players.get(1).getCurrentAnim());
                owner.retire();
                assertNull(cacheSource(child));
                assertTrue(cacheEntries(child).isEmpty());
                assertNull(players.get(0).getCurrentAnim());
            } finally { owner.retire(); }
        }
    }

    @Test
    void compatibleTextureInstallationClearsCachesAndPreservesPlaybackAndRandomHistory() throws Exception {
        var oldQueries = new AtomicInteger();
        var newQueries = new AtomicInteger();
        var oldA = animation("old-a");
        var newA = animation("new-a");
        var found = animation("found");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var alternate = new FirstPersonOwnerIntegrationTest.Fixture("parts-b");
            var module = TestExpressionModules.create();
            var oldBacking = AnimationStore.eager(Map.of("a", oldA)).shared();
            var newBacking = AnimationStore.eager(Map.of("a", newA, "missing", found)).shared();
            var oldSource = counted(oldBacking, oldQueries);
            var newSource = counted(newBacking, newQueries)) {
            var owner = new CacheOwner(host.player, oldSource, null);
            try {
                var key = new com.elfmcys.ysm.model.resource.client.render.RenderTargetKey("player", "a",
                    OwnerResourceFixtures.PROFILE, module.expressionBinding());
                var identity = new ModelRenderTarget.StateIdentity(
                    new com.elfmcys.ysm.model.domain.ModelFileIdentity(OwnerResourceFixtures.hash(1),
                        OwnerResourceFixtures.hash(122)), key.stateKey(), new Object());
                var a = owner.resources.target(1, module, new net.minecraft.resources.ResourceLocation("ysm", "a"),
                    identity, fixture.model);
                var b = owner.resources.target(1, module, new net.minecraft.resources.ResourceLocation("ysm", "b"),
                    identity, alternate.model);
                owner.sources.put(a, oldSource);
                owner.sources.put(b, newSource);
                owner.selectTexture("a", a);
                owner.step("missing");
                owner.step("a");
                owner.step("a");
                var player = player(owner.controller);
                var time = player.getAnimationContext().animTime();
                var expectedRandom = new net.minecraft.world.level.levelgen.XoroshiroRandomSource(291);
                var random = owner.getAnimationProcessor().randomSource();
                random.setSeed(291);
                assertEquals(expectedRandom.nextLong(), random.nextLong());
                var previousController = owner.controller;
                owner.selectTexture("b", b);
                assertSame(b, owner.getModelRenderTarget());
                assertSame(previousController, owner.controller);
                assertEquals(time, player.getAnimationContext().animTime());
                assertEquals(expectedRandom.nextLong(), random.nextLong());
                assertNull(cacheSource(owner.controller));
                assertTrue(cacheEntries(owner.controller).isEmpty());
                owner.step("a");
                assertSame(oldA, player.getCurrentAnim());
                assertTrue(player.getAnimationContext().animTime() > time);
                owner.step("missing");
                assertSame(found, player.getCurrentAnim());
                owner.step("a");
                assertSame(newA, player.getCurrentAnim());
                assertEquals(2, oldQueries.get());
                assertEquals(2, newQueries.get());
                assertEquals(1, owner.resources.leases.stream().filter(lease -> lease.target == a)
                    .mapToInt(lease -> lease.closes).sum());
            } finally { owner.retire(); }
        }
    }

    @Test
    void resourceRebindingPreservesPlaybackAndInvalidatesOldAbsenceAndReferences() throws Exception {
        var oldQueries = new AtomicInteger();
        var newQueries = new AtomicInteger();
        var oldA = animation("old-a");
        var newA = animation("new-a");
        var newMissing = animation("new-missing");
        try (var fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            var module = TestExpressionModules.create();
            var oldBacking = AnimationStore.eager(Map.of("a", oldA)).shared();
            var newBacking = AnimationStore.eager(Map.of("a", newA, "missing", newMissing)).shared();
            var oldSource = counted(oldBacking, oldQueries);
            var newSource = counted(newBacking, newQueries);
            var otherConsumer = AnimationStore.consumerView(oldBacking, ignored -> ModelResourceFailureGate.none())) {
            var owner = new CacheOwner(host.player, oldSource, null);
            try {
                owner.install(fixture.model, module);
                owner.step("missing");
                owner.step("a");
                var player = player(owner.controller);
                var before = player.getAnimationContext().animTime();
                owner.rebind(newSource);
                assertNull(cacheSource(owner.controller));
                assertTrue(cacheEntries(owner.controller).isEmpty());
                owner.step("a");
                assertSame(oldA, player.getCurrentAnim());
                assertTrue(player.getAnimationContext().animTime() > before);
                owner.step("missing");
                assertSame(newMissing, player.getCurrentAnim());
                owner.step("a");
                assertSame(newA, player.getCurrentAnim());
                assertEquals(2, oldQueries.get());
                assertEquals(2, newQueries.get());
                owner.step("absent");
                owner.rebind(oldSource);
                owner.step("absent");
                assertEquals(3, oldQueries.get(), "Rebinding drops same-name absence eligibility");
                owner.retire();
                assertNull(cacheSource(owner.controller));
                assertTrue(cacheEntries(owner.controller).isEmpty());
                assertNull(player.getCurrentAnim());
                assertSame(oldA, otherConsumer.get("a"), "Ending one owner never closes shared backing");
                assertTrue(owner.resources.leases.stream().allMatch(lease -> lease.closes == 1));
            } finally { owner.retire(); }
        }
    }

    private static AnimationStore counted(AnimationStore source, AtomicInteger queries) {
        return AnimationStore.consumerView(source, name -> {
            queries.incrementAndGet();
            return ModelResourceFailureGate.none();
        });
    }

    private static Animation animation(String sentinel) {
        return new Animation(sentinel, 400, LoopType.LOOP, null, List.of(), List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static AnimationControllerData definition(String state, String... names) {
        var animations = new Pair[names.length];
        for (int i = 0; i < names.length; i++) animations[i] = Pair.of(names[i], null);
        return new AnimationControllerData(state, new AnimationControllerState[] {
            new AnimationControllerState(state, animations, new Pair[0], new String[0], new VoidProgram[0],
                new VoidProgram[0], new LinearBlendTransition(0), false)});
    }

    private static Object value(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static AnimationPlayer player(Object controller) throws Exception {
        return (AnimationPlayer)value(controller, "animationPlayer");
    }

    private static List<AnimationPlayer> bedrockPlayers(Object controller) throws Exception {
        var result = new ArrayList<AnimationPlayer>();
        for (var holder : (List<?>)value(controller, "animationPlayers"))
            result.add(player(holder));
        return result;
    }

    private static Object cacheSource(Object controller) throws Exception {
        return value(value(controller, "animationCache"), "source");
    }

    private static Map<?, ?> cacheEntries(Object controller) throws Exception {
        return (Map<?, ?>)value(value(controller, "animationCache"), "entries");
    }

    private static final class CacheOwner extends FirstPersonOwnerIntegrationTest.Timeline {
        private final AnimationControllerData definition;
        private IAnimationController<CacheOwner> controller;
        private String request;
        private boolean readBatchObserved;
        private boolean compatLoop;
        private String selectedTexture = "";
        private final Map<ModelRenderTarget, AnimationStore> sources = new java.util.IdentityHashMap<>();
        private final Map<String, AnimationControllerData> subDefinitions = new java.util.HashMap<>();

        private CacheOwner(LocalPlayer player, AnimationStore source, AnimationControllerData definition)
            throws Exception {
            super(player);
            readBatchStore = source;
            this.definition = definition;
        }

        @Override protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData animations) {
            controller = definition == null ? new CodedAnimationController<>(this, "test", 0, event -> {
                if (compatLoop)
                    return com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playCompatAnimation(event,
                        request, LoopType.PLAY_ONCE);
                event.getCodedController().setAnimation(request);
                return PlayState.CONTINUE;
            }) : new BedrockAnimationController<>(this, "test", 0);
            animations.addAnimationController(controller);
        }

        @Override protected AnimationControllerData resolveControllerData(ModelRenderTarget target, String name) {
            return definition;
        }

        @Override public AnimationControllerData getAnimationControllerData(String name) {
            return subDefinitions.get(name);
        }

        @Override protected AnimationStore animationSource() {
            return sources.getOrDefault(getModelRenderTarget(), readBatchStore);
        }

        @Override public Animation getAnimation(String name) {
            return readAnimation(animationSource(), name);
        }

        @Override protected String requestedTextureName() { return selectedTexture; }

        private void selectTexture(String texture, ModelRenderTarget target) {
            selectedTexture = texture;
            resources.targets.put(target.modelHash(), target);
            updateModelHash(target.modelHash());
            DemandFixtures.advance(this);
        }

        @Override protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<LocalPlayer>> event,
            boolean shouldUpdate) {
            try {
                var field = AnimatableEntity.class.getDeclaredField("animationReads");
                field.setAccessible(true);
                readBatchObserved = field.get(this) != null;
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        private void step(String name) {
            request = name;
            getEntity().tickCount++;
            assertTrue(advance(0.5f));
        }

        private void rebind(AnimationStore source) {
            var install = getAnimationProcessor().prepareResourceBinding(assets(getModelRenderTarget()).eventHandlers(),
                getAnimationData(), name -> definition);
            readBatchStore = source;
            install.run();
        }
    }
}
