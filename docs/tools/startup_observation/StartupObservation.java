package com.elfmcys.ysm.verification;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.expression.aot.ExpressionEngineMetrics;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import net.minecraft.client.Minecraft;
import java.util.Collection;
import java.util.TreeSet;

/** Installed only in an isolated measurement tree, never in the production artifact. */
public final class StartupObservation {
    private static final ThreadLocal<String> CURRENT_SCOPE = new ThreadLocal<>();
    private static ExpressionEngineMetrics metrics;
    private StartupObservation() {}

    public static ExpressionEngineMetrics metrics() {
        metrics = new ExpressionEngineMetrics();
        return metrics;
    }

    @Name("ysm.StartupBoundary") @StackTrace(false)
    public static final class Boundary extends Event { public String label; }

    @Name("ysm.ExpressionPreparation") @StackTrace(false)
    public static final class Scope extends Event implements AutoCloseable {
        public String label;
        private final boolean observed;
        private final String previous;
        public Scope(String label, boolean observed) {
            this.label = label; this.observed = observed;
            this.previous = CURRENT_SCOPE.get();
            if (observed) { CURRENT_SCOPE.set(label); begin(); }
        }
        @Override public void close() {
            if (observed) {
                end(); commit();
                if (previous == null) CURRENT_SCOPE.remove(); else CURRENT_SCOPE.set(previous);
            }
        }
    }

    @Name("ysm.ExpressionWork") @StackTrace(false)
    public static final class Work extends Event { public String label; }

    @Name("ysm.ExpressionDuration") @StackTrace(false)
    public static final class StageDuration extends Event {
        public String label;
        public String scope;
        public String compilationKind;
        public long nanoseconds;
    }

    @Name("ysm.AnalysisWork") @StackTrace(false)
    public static final class Analysis extends Event {
        public String counter;
        public String scope;
        public String compilationKind;
        public long delta;
    }

    public static void duration(String stage, long nanoseconds) {
        var event = new StageDuration();
        event.label = stage; event.nanoseconds = nanoseconds; event.scope = CURRENT_SCOPE.get();
        event.compilationKind = stage.startsWith("TRANSIENT_") ? "TRANSIENT" : "PERSISTENT";
        event.commit();
    }

    public static void analysis(String kind, String counter, long delta) {
        var event = new Analysis();
        event.compilationKind = kind; event.counter = counter; event.delta = delta;
        event.scope = CURRENT_SCOPE.get(); event.commit();
    }

    public static void boundary(String label) {
        var event = new Boundary(); event.label = label; event.commit();
        YesSteveModel.LOGGER.info("[startup-observation] boundary={} nano={}", label, System.nanoTime());
    }

    public static void work(String label) {
        var event = new Work(); event.label = label; event.commit();
    }

    private static String names(Collection<?> values) {
        var names = new TreeSet<String>(); values.forEach(value -> names.add(value.toString()));
        return String.join("|", names);
    }

    public static void published(ClientModelService service) {
        boundary("REQUIRED_READY");
        var snapshot = metrics.snapshot();
        long[] analysis = new long[0];
        try {
            analysis = (long[]) snapshot.getClass().getMethod("analysisWork").invoke(snapshot);
        } catch (NoSuchMethodException absentInOriginal) {
            // The boundary-only original arm has the pre-observation metrics surface.
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        YesSteveModel.LOGGER.info("[startup-observation] sink stageNanos={} analysisWork={} portableDecodes={}",
                java.util.Arrays.toString(snapshot.stageNanos()), java.util.Arrays.toString(analysis),
                snapshot.portableProgramDecodes());
        for (var type : new Class<?>[] {YesSteveModel.class, dev.semaweave.compilation.EngineMetrics.class,
                dev.semaweave.backend.janino.JaninoJavaCompilerBackend.class}) {
            YesSteveModel.LOGGER.info("[startup-observation] loaded class={} source={}", type.getName(),
                    type.getProtectionDomain().getCodeSource().getLocation());
        }
        var entry = service.catalog().models().values().stream()
                .filter(value -> value.builtin() && (value.displayPath().equals("default")
                        || value.displayPath().equals("default.mxc"))).findFirst().orElseThrow();
        for (var target : entry.displayRepresentation().view().getRenderTargets()) {
            for (var texture : target.getTextureNames()) {
                var request = service.resourceRequest(entry.modelHash(), target.id(), texture);
                try (var lease = service.findReady(request).orElseThrow()) {
                    if (!(lease.poll() instanceof AcquireResult.Ready ready))
                        throw new IllegalStateException("Required target is not Ready: " + request);
                    var value = ready.target();
                    value.textureId();
                    YesSteveModel.LOGGER.info("[startup-observation] inventory target={} texture={} kind={} programs={} sounds={} functions={} events={} sources={}",
                            target.id(), texture, target.kind(), names(value.expressions().programLocations().values()),
                            names(value.assets().sounds().keySet()), names(value.assets().userFunctions().keySet()),
                            names(value.assets().eventHandlers().keySet()), names(value.defaultSources().keySet()));
                    if (value.playerResources() != null) {
                        var player = value.playerResources();
                        YesSteveModel.LOGGER.info("[startup-observation] player target={} texture={} mainAnimations={} armAnimations={} mainControllers={} armControllers={} armPrograms={}",
                                target.id(), texture, names(player.animations().keySet()), names(player.fpArmAnimations().keySet()),
                                names(player.animationControllers().keySet()), names(player.fpArmAnimationControllers().keySet()),
                                names(value.armExpressions().programLocations().values()));
                    }
                }
            }
        }
        boundary("INVENTORY_COMPLETE");
        Minecraft.getInstance().tell(() -> {
            boundary("NORMAL_STOP_REQUESTED");
            Minecraft.getInstance().stop();
        });
    }
}
