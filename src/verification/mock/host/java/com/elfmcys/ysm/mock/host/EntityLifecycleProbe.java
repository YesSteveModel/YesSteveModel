package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.api.molang.MolangContextView;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Controlled inputs on the real connected owner, scheduler, generated programs and leases. */
final class EntityLifecycleProbe {
    private static final List<Map<String, Object>> EVENTS = new ArrayList<>();
    private static volatile Gate gate;

    static synchronized void trace(float value) {
        EVENTS.add(Map.of("value", value, "thread", Thread.currentThread().getName()));
    }

    static synchronized void clearEvents() {
        EVENTS.clear();
    }

    static synchronized List<Map<String, Object>> events() {
        return List.copyOf(EVENTS);
    }

    static void block(MolangContextView<Entity> context) {
        var current = gate;
        if (current == null)
            return;
        current.thread = Thread.currentThread().getName();
        try {
            current.owner.retire();
            throw new AssertionError("Worker lifecycle reentry was accepted");
        } catch (IllegalStateException expected) {
            current.reentryRejected = true;
        }
        current.entered.countDown();
        try {
            if (!current.release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Worker gate was not released");
            var attempts = new ArrayList<String>();
            @SuppressWarnings("unchecked")
            var typedOwner = (AnimatableEntity<Entity>)(Object)current.owner;
            var model = new com.elfmcys.ysm.expression.host.builtin.ModelBuiltins(typedOwner);
            lateInput(attempts, "sound",
                () -> model.playSound(context, 5, "late_retired_worker", "minecraft:block.note_block.harp", 6, 1, 1));
            lateInput(attempts, "sync", () -> model.sync(context, 1, 9876));
            lateInput(attempts, "particle", () -> model.particle(context, 4, "minecraft:flame", 0, 0, 0));
            lateInput(attempts, "deferred", () -> model.defer(context, 2, 1, 9876));
            lateInput(attempts, "controller", () -> com.elfmcys.ysm.expression.host.builtin.ControlBuiltins.setAnimation(context, 2, "trace", 0));
            current.staged = List.copyOf(attempts);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        } finally {
            current.exited.countDown();
        }
    }

    private static void lateInput(List<String> attempts, String label, Runnable input) {
        try {
            input.run();
            attempts.add(label + ":returned");
        } catch (RuntimeException failure) {
            attempts.add(label + ":" + failure.getClass().getSimpleName());
        }
    }

    static Map<String, ?> timeline(Minecraft minecraft) throws Exception {
        var owner = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        var tick = minecraft.player.tickCount;
        var hidden = ClientConfig.DISABLE_SELF_MODEL.get();
        var result = new LinkedHashMap<String, Object>();
        try {
            for (var suppress : new boolean[] {false, true}) {
                ClientConfig.DISABLE_SELF_MODEL.set(suppress);
                minecraft.player.tickCount = 0;
                owner.waitForAsyncUpdate();
                owner.getExpressionRuntime().roamingState().orElseThrow().write("trace", 0f);
                owner.playExtraAnimation("trace");
                synchronized (EntityLifecycleProbe.class) {
                    EVENTS.clear();
                }
                var values = new ArrayList<Float>();
                for (var sample : new float[] {0, 0.01f, 2.5f, 4.5f, 3.5f}) {
                    minecraft.player.tickCount = (int)sample;
                    com.elfmcys.ysm.client.animation.AnimationParallelTicker.scheduleAll(sample - minecraft.player.tickCount);
                    owner.waitForAsyncUpdate();
                    com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
                    values.add(owner.roamingSnapshot(owner.localRoamingKey()).values()
                        .get(com.elfmcys.ysm.util.StringPool.computeIfAbsent("trace")));
                }
                result.put(suppress ? "hidden" : "visible", Map.of("values", values, "events", List.copyOf(EVENTS)));
            }
            result.put("samples", new float[] {0, 0.01f, 2.5f, 4.5f, 3.5f});
            result.put("opportunities",
                "Explicit invocation schedule/get/drain; no host draw between samples");
            return result;
        } finally {
            minecraft.player.tickCount = tick;
            ClientConfig.DISABLE_SELF_MODEL.set(hidden);
            owner.stopExtraAnimation();
        }
    }

    static Map<String, ?> worker(Minecraft minecraft) throws Exception {
        var owner = minecraft.player.getCapability(PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        var tick = minecraft.player.tickCount;
        minecraft.player.tickCount = tick + 1;
        owner.waitForAsyncUpdate();
        owner.playExtraAnimation("worker");
        var borrowed = update(owner, 0);
        if (borrowed == null)
            throw new IllegalStateException("Worker input requires installed native output");
        var binding = field(AnimatableEntity.class, "modelBinding").get(owner);
        var holder = field(binding.getClass(), "resourceHolder").get(binding);
        var leaseField = field(holder.getClass(), "lease");
        var delegate = (ResourceLease)leaseField.get(holder);
        var closes = new AtomicInteger();
        leaseField.set(holder, new ResourceLease() {
            @Override
            public com.elfmcys.ysm.model.resource.client.ModelResourcePurpose purpose() {
                return delegate.purpose();
            }

            @Override
            public AcquireResult poll() {
                return delegate.poll();
            }

            @Override
            public boolean isCurrent(ResourceRequest request) {
                return delegate.isCurrent(request);
            }

            @Override
            public void cancelPending() {
                delegate.cancelPending();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
                delegate.close();
            }
        });
        var current = new Gate(owner);
        var installation = owner.getExpressionRuntime().sampleInstallation(value -> value).orElseThrow();
        var snapshot = owner.getExpressionRuntime().snapshot().orElseThrow();
        var planner = new com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner();
        planner.beginSession(com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy.gameServer(true));
        planner.acceptAuthority(owner.getModelHash(), owner.localRoamingKey());
        var reportCallbacks = new AtomicInteger();
        var reporter = Class.forName("com.elfmcys.ysm.network.forge.LocalPlayerStateReporter").getDeclaredMethod(
            "submitOpportunity", com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner.class, long.class, int.class,
            com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner.RoamingSample.class,
            com.elfmcys.ysm.expression.aot.EntityExpressionRuntime.Installation.class,
            java.util.function.Consumer.class);
        reporter.setAccessible(true);
        var sample = new com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner.RoamingSample(
            owner.localRoamingKey(), snapshot.payload().values());
        java.util.function.Consumer<com.elfmcys.ysm.proto.network.PlayerStateReportProto> sender =
            report -> reportCallbacks.incrementAndGet();
        reporter.invoke(null, planner, System.nanoTime(), minecraft.player.getId(), sample, installation, sender);
        var initialReportCallbacks = reportCallbacks.get();
        gate = current;
        var result = new LinkedHashMap<String, Object>();
        try {
            minecraft.player.tickCount = tick + 3;
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.scheduleAll(0.5f);
            if (!current.entered.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Actual worker did not reach the gate");
            result.put("beforeExit",
                Map.of("assigned", owner.isAssigned(), "unfinished", owner.hasUnfinishedWork(), "leaseCloses",
                    closes.get(), "workerReentryRejected", current.reentryRejected, "workerThread",
                    current.thread));
            var main = Thread.currentThread();
            var releaser = new Thread(() -> {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (main.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
                    Thread.onSpinWait();
                current.closeBeforeRelease = closes.get();
                current.release.countDown();
            }, "YSM Ownership Gate");
            releaser.start();
            owner.retire();
            releaser.join(5000);
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
            result.put("afterExit",
                Map.of("assigned", owner.isAssigned(), "unfinished", owner.hasUnfinishedWork(), "leaseCloses",
                    closes.get(), "borrowedNativeValid", borrowed.modelState.isValid(), "callbackExited",
                    current.exited.getCount() == 0, "installed", owner.hasInstalledModel(), "expressionInstalled",
                    owner.getExpressionRuntime().module().isPresent()));
            var accepted = new AtomicInteger();
            var late = new LinkedHashMap<String, Object>();
            for (var name : List.of("effect", "report", "callback"))
                late.put(name, installation.commit(accepted::incrementAndGet));
            result.put("lateAdmission", late);
            result.put("lateCallbacksExecuted", accepted.get());
            result.put("stagedBeforeRetireReturn", current.staged);
            result.put("leaseClosesBeforeWorkerRelease", current.closeBeforeRelease);
            reporter.invoke(null, planner, System.nanoTime() + TimeUnit.SECONDS.toNanos(60), minecraft.player.getId(),
                sample, installation, sender);
            result.put("qualifiedReportCallbacks",
                Map.of("beforeSeal", initialReportCallbacks, "afterLateOpportunity", reportCallbacks.get()));
            return result;
        } finally {
            current.release.countDown();
            owner.waitForAsyncUpdate();
            com.elfmcys.ysm.client.animation.AnimationParallelTicker.waitAll();
            minecraft.player.tickCount = tick;
            gate = null;
        }
    }

    private static GeoRenderData update(PlayerAnimatableCapability owner, float partial) throws Exception {
        var method = AnimatableEntity.class.getDeclaredMethod("update", float.class, RenderContext.class);
        method.setAccessible(true);
        return (GeoRenderData)method.invoke(owner, partial, RenderContext.levelImmutable());
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Gate {
        final PlayerAnimatableCapability owner;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        volatile String thread;
        volatile List<String> staged = List.of();
        volatile boolean reentryRejected;
        volatile int closeBeforeRelease = -1;

        Gate(PlayerAnimatableCapability owner) {
            this.owner = owner;
        }
    }
}
