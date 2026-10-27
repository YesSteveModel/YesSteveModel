package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.util.ResourceDiagnostics;
import dev.semaweave.compilation.EngineMetrics;
import dev.semaweave.compilation.LoaderCollectionProbe;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Bounded process metrics for compile/load work and persistent resource ownership. */
public final class ExpressionEngineMetrics implements EngineMetrics {
    private static final int ANALYSIS_COUNTER_COUNT = AnalysisCounter.values().length;
    private static final AtomicLong NEXT_LOADER_ID = new AtomicLong();
    // Probes contain only weak references; process diagnostics must never pin generated code or its owners.
    private static final ConcurrentHashMap<Long, LoaderCollectionProbe> LOADERS = new ConcurrentHashMap<>();
    private final AtomicLongArray stageCalls = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray stageNanos = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray activeOwners = new AtomicLongArray(Owner.values().length);
    private final AtomicLongArray events = new AtomicLongArray(Event.values().length);
    private final AtomicLong portableProgramDecodes = new AtomicLong();
    private final AtomicLongArray analysisWork =
        new AtomicLongArray(CompilationKind.values().length * ANALYSIS_COUNTER_COUNT);

    @Override
    public void recordAnalysisWork(CompilationKind kind, AnalysisCounter counter, long delta) {
        if (kind != null && counter != null && delta >= 0) {
            analysisWork.addAndGet(kind.ordinal() * ANALYSIS_COUNTER_COUNT + counter.ordinal(), delta);
        }
    }

    @Override
    public void recordDuration(Stage stage, long nanoseconds) {
        if (stage == null || nanoseconds < 0) {
            return;
        }
        stageCalls.incrementAndGet(stage.ordinal());
        stageNanos.addAndGet(stage.ordinal(), nanoseconds);
    }

    @Override
    public void changeActive(Owner owner, long delta) {
        if (owner != null) {
            var active = activeOwners.addAndGet(owner.ordinal(), delta);
            ResourceDiagnostics.log("Molang owner: metrics={} kind={} delta={} active={}",
                System.identityHashCode(this), owner, delta, active);
        }
    }

    @Override
    public void trackLoader(LoaderCollectionProbe probe) {
        if (ResourceDiagnostics.isEnabled()) {
            var id = NEXT_LOADER_ID.incrementAndGet();
            LOADERS.put(id, probe);
            ResourceDiagnostics.log("Molang loader track: loader={} live={}", id, LOADERS.size());
        }
    }

    public static void pollCollectedLoaders() {
        if (!ResourceDiagnostics.isEnabled()) {
            return;
        }
        LOADERS.forEach((id, probe) -> {
            if (probe.collected() && LOADERS.remove(id, probe)) {
                ResourceDiagnostics.log("Molang loader collected: loader={} live={}", id, LOADERS.size());
            }
        });
    }

    @Override
    public void record(Event event) {
        if (event != null) {
            events.incrementAndGet(event.ordinal());
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(copy(stageCalls), copy(stageNanos), copy(activeOwners), copy(events),
            portableProgramDecodes.get(), copy(analysisWork));
    }

    void recordPortableProgramDecodeInventory() {
        portableProgramDecodes.incrementAndGet();
    }

    private static long[] copy(AtomicLongArray source) {
        var result = new long[source.length()];
        Arrays.setAll(result, source::get);
        return result;
    }

    public record Snapshot(long[] stageCalls, long[] stageNanos, long[] activeOwners, long[] events,
        long portableProgramDecodes, long[] analysisWork) {
        public Snapshot {
            stageCalls = stageCalls.clone();
            stageNanos = stageNanos.clone();
            activeOwners = activeOwners.clone();
            events = events.clone();
            analysisWork = analysisWork.clone();
        }

        @Override
        public long[] stageCalls() {
            return stageCalls.clone();
        }

        @Override
        public long[] stageNanos() {
            return stageNanos.clone();
        }

        @Override
        public long[] activeOwners() {
            return activeOwners.clone();
        }

        @Override
        public long[] events() {
            return events.clone();
        }

        @Override
        public long[] analysisWork() {
            return analysisWork.clone();
        }

        public long stageCalls(Stage stage) {
            return stageCalls[stage.ordinal()];
        }

        public long active(Owner owner) {
            return activeOwners[owner.ordinal()];
        }

        public long events(Event event) {
            return events[event.ordinal()];
        }

        public long analysisWork(CompilationKind kind, AnalysisCounter counter) {
            return analysisWork[kind.ordinal() * ANALYSIS_COUNTER_COUNT + counter.ordinal()];
        }
    }
}
