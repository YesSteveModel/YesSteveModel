package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.client.entity.AnimatableEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Temporal probe only; this synchronized log is not a visibility oracle. */
public final class OwnershipHostObservation {
    private static boolean enabled;
    private static final List<Map<String, Object>> EVENTS = new ArrayList<>();
    private static int frames;
    private static int polls;
    private static int currentCase;
    private static volatile Object failSubmit;
    private static volatile Object failExecute;
    private static java.util.concurrent.Future<?> compiler;
    private static java.util.concurrent.CountDownLatch compilerRelease;
    private static boolean compilerObservedAtReturn;
    private static AnimatableEntity<?> controlledOwner;
    private static final java.util.concurrent.ExecutorService COMPILER =
        java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "ownership-independent-compiler");
            thread.setDaemon(true);
            return thread;
        });

    public static synchronized void event(String name, Object subject) {
        if (!enabled) return;
        if (name.equals("render-enter")) {
            currentCase = Math.min(frames, 4);
            controlledOwner = null;
        }
        boolean main = net.minecraft.client.Minecraft.getInstance().isSameThread();
        boolean pending = main && subject instanceof AnimatableEntity<?> owner && owner.hasPendingUpdate();
        EVENTS.add(Map.of("sequence", EVENTS.size(), "event", name, "subject", System.identityHashCode(subject),
            "thread", Thread.currentThread().getName(), "pending", pending,
            "invocation", main && AnimationParallelTicker.isInvocationActive(), "case", currentCase));
        if (name.equals("render-return")) {
            if (AnimationParallelTicker.isInvocationActive()) throw new AssertionError("World invocation escaped return");
            if (controlledOwner != null && controlledOwner.hasPendingUpdate())
                throw new AssertionError("Queued owner escaped a normal render return without its get");
            if (currentCase == 3) {
                if (compiler == null || compiler.isDone())
                    throw new AssertionError("Independent compilation did not outlive the normal render return");
                compilerObservedAtReturn = true;
                compilerRelease.countDown();
            }
            frames++;
        }
    }

    public static void beforeSubmit(Object owner) {
        if (failSubmit == owner) {
            failSubmit = null;
            event("submission-injected-failure", owner);
            throw new IllegalStateException("ownership-caught-submission");
        }
    }

    public static void beforeExecute(Object owner) {
        if (failExecute == owner) {
            failExecute = null;
            event("execution-injected-failure", owner);
            throw new IllegalStateException("ownership-caught-reduction");
        }
    }

    public static boolean afterSchedule() {
        if (!enabled || currentCase == 4) return false;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var owner = minecraft.player.getCapability(com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP)
            .orElseThrow(IllegalStateException::new);
        controlledOwner = owner;
        try {
            owner.waitForAsyncUpdate();
            var update = AnimatableEntity.class.getDeclaredMethod("update", float.class,
                com.elfmcys.ysm.geckolib3.geo.RenderContext.class);
            update.setAccessible(true);
            var data = (com.elfmcys.ysm.geckolib3.geo.GeoRenderData)update.invoke(owner, minecraft.getFrameTime(),
                com.elfmcys.ysm.geckolib3.geo.RenderContext.levelImmutable());
            if (!owner.consumePrepared(data, borrowed -> true))
                throw new AssertionError("Early consumption requires a current real native result");
            event("early-consumed", owner);
            var lifecycleField = AnimatableEntity.class.getDeclaredField("lifecycle");
            lifecycleField.setAccessible(true);
            var lifecycle = lifecycleField.get(owner);
            var executor = lifecycle.getClass().getDeclaredField("executor");
            executor.setAccessible(true);
            var original = executor.get(lifecycle);
            var queued = new ArrayList<Runnable>();
            try {
                executor.set(lifecycle, (java.util.concurrent.Executor)queued::add);
                AnimationParallelTicker.schedule(owner, minecraft.getFrameTime());
            } finally { executor.set(lifecycle, original); }
            if (queued.size() != 1 || !owner.hasPendingUpdate())
                throw new AssertionError("Expected one accepted, queued, undrawn task");
            event("queued-undrawn", owner);
            if (currentCase == 1) {
                failSubmit = owner;
                try {
                    AnimationParallelTicker.schedule(owner, minecraft.getFrameTime());
                    throw new AssertionError("Partial submission failure was not injected");
                } catch (IllegalStateException expected) {
                    if (!expected.getMessage().equals("ownership-caught-submission")) throw expected;
                    event("submission-caught", owner);
                }
            }
            if (currentCase == 2) failExecute = owner;
            if (currentCase == 3) {
                var request = owner.getExpressionRuntime().prepareTransient(owner.getExpressionRuntime().generation(),
                    "return 1;", com.elfmcys.ysm.expression.aot.TransientExpressionSession.Privilege.LOCAL_WATCH,
                    com.elfmcys.ysm.expression.aot.TransientExpressionSession.Purpose.WATCH, "host:return-boundary");
                var entered = new java.util.concurrent.CountDownLatch(1);
                compilerRelease = new java.util.concurrent.CountDownLatch(1);
                var first = new java.util.concurrent.atomic.AtomicBoolean(true);
                compiler = COMPILER.submit(() -> {
                    try (request; var session = request.compile(() -> {
                        if (first.compareAndSet(true, false)) {
                            entered.countDown();
                            try {
                                if (!compilerRelease.await(15, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new IllegalStateException("Compiler gate timed out");
                            } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        }
                        return false;
                    })) { event("independent-compiler-complete", owner); }
                    return null;
                });
                if (!entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Independent compiler did not enter");
                event("independent-compiler-running", owner);
            }
            var main = Thread.currentThread();
            var release = new Thread(() -> {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (main.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
                    Thread.onSpinWait();
                event("queued-start", owner);
                queued.get(0).run();
                event("queued-exit", owner);
            }, "ownership-queued-worker");
            release.setDaemon(true);
            release.start();
            event("controlled-normal-return", owner);
            return true;
        } catch (ReflectiveOperationException | InterruptedException failure) { throw new AssertionError(failure); }
    }

    public static void caughtDrain(Throwable failure) {
        if (currentCase != 2 || !"ownership-caught-reduction".equals(failure.getMessage()))
            throw new AssertionError("Unexpected render drain failure", failure);
        if (AnimationParallelTicker.isInvocationActive())
            throw new AssertionError("Failure reduction escaped before closing the invocation");
        com.elfmcys.ysm.util.RenderUtil.setRenderingLevel(false);
        event("reduction-caught", net.minecraft.client.Minecraft.getInstance().levelRenderer);
    }

    static synchronized Map<String, ?> poll() {
        if (!enabled) {
            EVENTS.clear();
            frames = 0;
            polls = 0;
            compiler = null;
            compilerObservedAtReturn = false;
            enabled = true;
            return null;
        }
        if (frames < 20 && ++polls < 200) return null;
        if (compiler != null && !compiler.isDone()) return null;
        if (compiler != null) {
            try { compiler.get(); }
            catch (Exception failure) { throw new AssertionError("Independent compilation failed", failure); }
        }
        enabled = false;
        if (frames < 20 || EVENTS.stream().noneMatch(event -> event.get("event").equals("execute-return")))
            throw new AssertionError("Actual render/worker hooks were not observed");
        for (var required : List.of("early-consumed", "queued-undrawn", "submission-caught", "reduction-caught"))
            if (EVENTS.stream().noneMatch(event -> event.get("event").equals(required)))
                throw new AssertionError("Missing controlled host case: " + required);
        if (!compilerObservedAtReturn) throw new AssertionError("Compiler return boundary was not observed");
        return Map.of("frames", frames, "events", List.copyOf(EVENTS), "compilerOutlivedReturn", true, "boundary",
            "Actual transformed renderLevel; controlled early normal returns call the unchanged production RETURN handler; "
                + "submission and drain failures are caught before normal return; host-crash exits remain excluded");
    }
}
