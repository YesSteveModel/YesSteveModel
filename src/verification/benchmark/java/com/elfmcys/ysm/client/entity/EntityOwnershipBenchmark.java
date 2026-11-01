package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.controller.CodedAnimationController;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Runs the production owner, processor and controller with an immutable resource fixture. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
public class EntityOwnershipBenchmark {
    @State(Scope.Thread)
    public static class Workload {
        @Param({"1", "64"}) public int owners;
        @Param({"same", "switch", "replay", "absent", "dynamic"}) public String query;
        BenchOwner[] instances;
        FirstPersonOwnerIntegrationTest host;
        FirstPersonOwnerIntegrationTest.Fixture fixture;
        com.elfmcys.ysm.expression.aot.RenderTargetExpressionModule module;
        AnimationStore store;
        String[] missing;
        long rounds;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeLibraryExtension.loadNativeLibrary();
            FirstPersonOwnerIntegrationTest.initializeHost();
            host = new FirstPersonOwnerIntegrationTest();
            host.createHost();
            fixture = new FirstPersonOwnerIntegrationTest.Fixture("parts-a");
            module = TestExpressionModules.create();
            store = AnimationStore.eager(Map.of("a", animation("a"), "b", animation("b")));
            missing = new String[10000];
            for (int i = 0; i < missing.length; i++) missing[i] = "missing_" + i;
            instances = new BenchOwner[owners];
            for (int i = 0; i < owners; i++) {
                var owner = new BenchOwner(host.player);
                owner.readBatchStore = store;
                owner.install(fixture.model, module);
                instances[i] = owner;
                com.elfmcys.ysm.client.animation.AnimationParallelTicker.add(owner);
            }
            // The action and geometry checks are outside the timed observations.
            for (int i = 0; i < 6; i++) {
                frame();
                for (var owner : instances) {
                    if (owner.actions != i + 1 || owner.normalUpdates != i + 1)
                        throw new AssertionError("logical round count");
                    var animation = current(owner.controller);
                    var absent = query.equals("absent") || query.equals("dynamic");
                    if (absent ? animation != null : animation == null || !animation.name.equals(owner.request))
                        throw new AssertionError("animation truth: " + owner.request);
                    FirstPersonOwnerIntegrationTest.assertNativeConsumption(owner,
                        owner.produce(framePartialTick(), RenderContext.levelImmutable()));
                    if (owner.actions != i + 1) throw new AssertionError("extract repeated actions");
                }
            }
            earlyFrame();
            for (var owner : instances)
                if (owner.actions != 7) throw new AssertionError("early-consumption action count");
        }

        long frame() {
            int index = (int)(rounds++ % missing.length);
            String name = switch (query) {
                case "switch" -> index % 3 == 1 ? "b" : "a";
                case "absent" -> "absent";
                case "dynamic" -> missing[index];
                default -> "a";
            };
            host.player.tickCount++;
            long checksum = 0;
            for (var owner : instances) {
                owner.request = name;
                if (query.equals("replay")) owner.controller.indicateReload();
                if (!owner.updateAnimationOnly(framePartialTick(), RenderContext.levelImmutable()))
                    throw new AssertionError("owner did not execute");
                checksum += owner.actions;
            }
            return checksum;
        }

        private float framePartialTick() { return owners == 1 ? 0f : 0.5f; }

        @Setup(Level.Iteration)
        public void resetSingleOwnerClock() throws Exception {
            if (owners != 1) return;
            for (var owner : instances) owner.retire();
            host.player.tickCount = 0;
            rounds = 0;
            var owner = new BenchOwner(host.player);
            owner.readBatchStore = store;
            owner.install(fixture.model, module);
            instances = new BenchOwner[] {owner};
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.add(owner);
            for (int i = 0; i < 6; i++) frame();
        }

        @TearDown(Level.Iteration)
        public void verifyLogicalRounds() {
            for (var owner : instances)
                if (owner.actions != rounds || owner.normalUpdates != rounds)
                    throw new AssertionError("Logical rounds diverged from benchmark operations: "
                        + owner.actions + "/" + rounds);
        }

        long earlyFrame() {
            host.player.tickCount++;
            for (var owner : instances) owner.request = (rounds & 1) == 0 ? "a" : "b";
            rounds++;
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.scheduleAll(0.5f);
            instances[0].waitForAsyncUpdate();
            var early = instances[0].produce(0.5f, RenderContext.levelImmutable());
            if (early == null || !early.modelState.isValid()) throw new AssertionError("early output");
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
            long checksum = 0;
            for (var owner : instances) checksum += owner.actions;
            return checksum;
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (instances != null) for (var owner : instances) if (owner != null) owner.retire();
            if (store != null) store.close();
            if (module != null) module.close();
            if (fixture != null) fixture.close();
            if (host != null) host.releaseHost();
            FirstPersonOwnerIntegrationTest.restoreHost();
        }
    }

    static final class BenchOwner extends FirstPersonOwnerIntegrationTest.Timeline {
        String request = "a";
        CodedAnimationController<BenchOwner> controller;
        BenchOwner(net.minecraft.client.player.LocalPlayer player) throws Exception { super(player); }
        @Override protected void prepareAnimationControllers(ModelRenderTarget target, AnimationData data) {
            controller = new CodedAnimationController<>(this, "measured", 0, event -> {
                event.getCodedController().setAnimation(request);
                return PlayState.CONTINUE;
            });
            data.addAnimationController(controller);
        }
    }

    private static Animation animation(String name) {
        return new Animation(name, 400, LoopType.LOOP, null, List.of(), List.of(), List.of());
    }

    private static Animation current(Object controller) throws Exception {
        var field = controller.getClass().getDeclaredField("animationPlayer");
        field.setAccessible(true);
        return ((com.elfmcys.ysm.geckolib3.core.controller.AnimationPlayer)field.get(controller)).getCurrentAnim();
    }

    @Benchmark public long frame(Workload state) { return state.frame(); }
    @Benchmark public long earlyConsumption(Workload state) { return state.earlyFrame(); }

    public static void main(String[] args) throws Exception {
        int[] sizes = args.length >= 2 ? new int[] {Integer.parseInt(args[0])} : new int[] {1, 64};
        String[] queries = args.length >= 2 ? new String[] {args[1]}
            : new String[] {"same", "switch", "replay", "absent", "dynamic"};
        for (int owners : sizes) {
            for (var query : queries) {
                var state = new Workload();
                state.owners = owners;
                state.query = query;
                try {
                    state.setup();
                    System.out.println("TRUTH " + owners + " " + query + " passed");
                    if (java.util.Arrays.asList(args).contains("probe")) {
                        EntityOwnershipProbeAgent.ownerSync.set(0);
                        EntityOwnershipProbeAgent.resolution.set(0);
                        EntityOwnershipProbeAgent.sharedStoreMonitors.set(0);
                        for (int i = 0; i < 100; i++) state.frame();
                        System.out.println("COUNTS " + owners + " " + query + " frames=100 ownerSync="
                            + EntityOwnershipProbeAgent.ownerSync.get() + " resolution="
                            + EntityOwnershipProbeAgent.resolution.get() + " sharedStoreMonitors="
                            + EntityOwnershipProbeAgent.sharedStoreMonitors.get());
                    }
                    state.verifyLogicalRounds();
                }
                finally { state.close(); }
            }
        }
    }
}
