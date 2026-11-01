package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.format.schema.file.ChannelReadPhase;
import com.elfmcys.ysm.format.schema.file.ReadPhaseSet;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationProto;
import it.unimi.dsi.fastutil.objects.AbstractObject2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Per-render-target animation ownership with local-first default fallback. */
public final class AnimationStore extends AbstractObject2ReferenceMap<String, Animation> implements AutoCloseable {
    private ReadContext readContext;
    private final Object2ReferenceOpenHashMap<String, Animation> local = new Object2ReferenceOpenHashMap<>();
    private final AnimationLoader source;
    private final AnimationStore fallback;
    private final Function<String, ModelResourceFailureGate> failureGates;
    private final Map<String, Slot> slots = new HashMap<>();
    private final Map<String, Throwable> failures = new HashMap<>();
    private AnimationStore backing;
    private boolean sharedBacking;
    private boolean completeNames;
    private volatile boolean closed;

    public AnimationStore() {
        this(null, null, ignored -> ModelResourceFailureGate.none());
    }

    private AnimationStore(AnimationLoader source, AnimationStore fallback,
        Function<String, ModelResourceFailureGate> failureGates) {
        this.source = source;
        this.fallback = fallback;
        this.failureGates = failureGates;
    }

    public static AnimationStore lazy(Iterable<String> names, AnimationSource source, AnimationBinder binder) {
        return lazy(names, source, binder, null);
    }

    public static AnimationStore lazy(Iterable<String> names, AnimationSource source, AnimationBinder binder,
        AnimationStore fallback) {
        return lazy(names, source, binder, fallback, ignored -> ModelResourceFailureGate.none());
    }

    public static AnimationStore lazy(Iterable<String> names, AnimationSource source, AnimationBinder binder,
        AnimationStore fallback, Function<String, ModelResourceFailureGate> failureGates) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(binder, "binder");
        return lazyBound(names, name -> {
            var proto = source.load(name);
            return proto == null ? null : Objects.requireNonNull(binder.bind(proto), "bound animation");
        }, fallback, failureGates);
    }

    public static AnimationStore lazyBound(Iterable<String> names, AnimationLoader source, AnimationStore fallback,
        Function<String, ModelResourceFailureGate> failureGates) {
        var result = new AnimationStore(Objects.requireNonNull(source, "source"), fallback,
            Objects.requireNonNull(failureGates, "failureGates"));
        for (var name : names) {
            Objects.requireNonNull(name, "animation name");
            if (result.containsLocal(name)) {
                throw new IllegalArgumentException("Duplicate animation name: " + name);
            }
            result.put(name, null);
            result.slots.put(name, new Slot(State.UNLOADED, null));
        }
        result.completeNames = true;
        return result;
    }

    public static AnimationStore eager(Map<String, Animation> animations) {
        var result = new AnimationStore();
        animations.forEach((name, animation) -> {
            var checkedName = Objects.requireNonNull(name, "animation name");
            var checkedAnimation = Objects.requireNonNull(animation, "animation");
            result.put(checkedName, checkedAnimation);
            result.slots.put(checkedName, new Slot(State.READY, checkedAnimation));
        });
        result.completeNames = true;
        return result;
    }

    public synchronized AnimationStore shared() {
        requireOpen();
        sharedBacking = true;
        completeNames = true;
        return this;
    }

    public static AnimationStore consumerView(AnimationStore backing,
        Function<String, ModelResourceFailureGate> failureGates) {
        var view = new AnimationStore(null, null, Objects.requireNonNull(failureGates));
        view.backing = Objects.requireNonNull(backing);
        return view;
    }

    public ReadBatch openReadPhase() throws IOException {
        var phases = new ReadPhaseSet();
        try {
            var view = consumerView(this, failureGates);
            view.backing = backing == null ? this : backing;
            view.readContext = readContext(phases);
            return new ReadBatch(view, phases);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                phases.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private synchronized ReadContext readContext(ReadPhaseSet phases) throws IOException {
        requireOpen();
        if (backing != null)
            return backing.readContext(phases);
        return new ReadContext(source, fallback == null ? null : fallback.readContext(phases), phases);
    }

    @Override
    public synchronized Animation get(Object key) {
        requireOpen();
        return key instanceof String name ? resolve(name).animation() : null;
    }

    public Resolution resolve(String name) {
        return resolve(Objects.requireNonNull(name, "animation name"), readContext);
    }

    private synchronized Resolution resolve(String name, ReadContext context) {
        requireOpen();
        if (backing != null) {
            var gate = Objects.requireNonNull(failureGates.apply(name), "failure gate");
            if (gate.failure().isPresent())
                return backing.fallbackAnimation(name, context).unstable();
            var value = backing.resolve(name, context);
            Throwable failure;
            synchronized (backing) {
                failure = backing.failures.get(name);
            }
            if (failure != null)
                gate.fail(failure);
            return value;
        }
        if (containsLocal(name)) {
            var slot = slots.get(name);
            if (slot == null || slot.state() == State.READY || source == null) {
                var animation = slot == null ? local.get(name) : slot.animation();
                return new Resolution(animation, false, completeNames && animation != null);
            }
            if (slot.state() == State.FAILED) {
                return fallbackAnimation(name, context).unstable();
            }
            var failureGate = Objects.requireNonNull(failureGates.apply(name), "failure gate");
            if (failureGate.failure().isPresent()) {
                slots.put(name, new Slot(State.FAILED, null));
                return fallbackAnimation(name, context).unstable();
            }
            try {
                var value = context == null ? source.load(name) : context.load(name);
                if (value == null) {
                    var missing = new FileNotFoundException("Baked animation payload is missing: " + name);
                    if (context != null)
                        context.endFailedPhase(missing);
                    failureGate.fail(missing);
                    failures.put(name, missing);
                    slots.put(name, new Slot(sharedBacking ? State.UNLOADED : State.FAILED, null));
                    return fallbackAnimation(name, context).unstable();
                }
                failures.remove(name);
                local.put(name, value);
                slots.put(name, new Slot(State.READY, value));
                return new Resolution(value, false, completeNames);
            } catch (Exception error) {
                if (error instanceof ReadBatchCloseFailure closeFailure)
                    throw closeFailure;
                if (error instanceof IOException io && (io instanceof ChannelReadPhase.CloseFailure
                    || io.getCause() instanceof ChannelReadPhase.CloseFailure)) {
                    if (context != null)
                        context.abort(io);
                    throw new ReadBatchCloseFailure(io);
                }
                if (error instanceof CancellationException cancelled) {
                    if (context != null)
                        context.abort(cancelled);
                    throw cancelled;
                }
                if (Thread.currentThread().isInterrupted()) {
                    var cancelled = new CancellationException("Animation read was interrupted");
                    cancelled.initCause(error);
                    if (context != null)
                        context.abort(cancelled);
                    throw cancelled;
                }
                if (context != null)
                    context.endFailedPhase(error);
                failureGate.fail(error);
                failures.put(name, error);
                var deterministic = error instanceof com.elfmcys.ysm.format.AssetLoadException asset
                    ? asset.reason() == com.elfmcys.ysm.format.AssetLoadException.Reason.CONTENT
                    : !(error instanceof IOException);
                slots.put(name, new Slot(sharedBacking && !deterministic ? State.UNLOADED : State.FAILED, null));
                return fallbackAnimation(name, context).unstable();
            }
        }
        var value = fallbackAnimation(name, context);
        return completeNames ? value : value.unstable();
    }

    private Resolution fallbackAnimation(String name, ReadContext context) {
        if (fallback == null)
            return new Resolution(null, false, true);
        var result = fallback.resolve(name, context == null ? null : context.fallback);
        return new Resolution(result.animation(), result.animation() != null, result.cacheable());
    }

    @Override
    public Animation getOrDefault(Object key, Animation defaultValue) {
        var value = get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        requireOpen();
        if (backing != null)
            return backing.containsKey(key);
        return containsLocal(key) || fallback != null && fallback.containsKey(key);
    }

    private boolean containsLocal(Object key) {
        return local.containsKey(key);
    }

    @Override
    public synchronized ObjectSet<String> keySet() {
        requireOpen();
        if (backing != null)
            return backing.keySet();
        if (fallback == null) {
            return ObjectSets.unmodifiable(local.keySet());
        }
        var names = new ObjectOpenHashSet<>(local.keySet());
        names.addAll(fallback.keySet());
        return ObjectSets.unmodifiable(names);
    }

    public synchronized State state(String name) {
        requireOpen();
        if (backing != null)
            return backing.state(name);
        var slot = slots.get(name);
        return slot == null ? null : slot.state();
    }

    public synchronized boolean hasFailures() {
        requireOpen();
        if (backing != null)
            return backing.hasFailures();
        return slots.values().stream().anyMatch(slot -> slot.state() == State.FAILED);
    }

    @Override
    public synchronized int size() {
        requireOpen();
        return backing == null ? local.size() : backing.size();
    }

    @Override
    public synchronized ObjectSet<Object2ReferenceMap.Entry<String, Animation>> object2ReferenceEntrySet() {
        requireOpen();
        return Object2ReferenceMaps.unmodifiable(backing == null ? local : backing).object2ReferenceEntrySet();
    }

    @Override
    public synchronized Animation put(String name, Animation animation) {
        requireMutable();
        return local.put(name, animation);
    }

    @Override
    public synchronized Animation remove(Object name) {
        requireMutable();
        slots.remove(name);
        failures.remove(name);
        return local.remove(name);
    }

    @Override
    public synchronized void clear() {
        requireMutable();
        slots.clear();
        failures.clear();
        local.clear();
    }

    private void requireMutable() {
        requireOpen();
        if (backing != null)
            throw new UnsupportedOperationException("Consumer animation views are read-only");
        if (completeNames)
            throw new UnsupportedOperationException("Published animation names are immutable");
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Animation store is closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        slots.clear();
        failures.clear();
        backing = null;
        local.clear();
    }

    @FunctionalInterface
    public interface AnimationSource {
        AnimationProto load(String animationName) throws Exception;
    }

    @FunctionalInterface
    public interface AnimationBinder {
        Animation bind(AnimationProto animation) throws Exception;
    }

    @FunctionalInterface
    public interface AnimationLoader {
        Animation load(String animationName) throws Exception;

        default AnimationLoader inReadPhase(ReadPhaseSet phases) throws IOException {
            return this;
        }
    }

    private static final class ReadContext {
        private final AnimationLoader source;
        private final ReadContext fallback;
        private final ReadPhaseSet phases;
        private AnimationLoader reader;
        private long generation = -1;

        private ReadContext(AnimationLoader source, ReadContext fallback, ReadPhaseSet phases) {
            this.source = source;
            this.fallback = fallback;
            this.phases = phases;
        }

        private Animation load(String name) throws Exception {
            var current = phases.generation();
            if (generation != current) {
                reader = source.inReadPhase(phases);
                generation = current;
            }
            return reader.load(name);
        }

        private void endFailedPhase(Exception original) {
            try {
                phases.endPhase();
            } catch (IOException cleanup) {
                cleanup.addSuppressed(original);
                throw new ReadBatchCloseFailure(cleanup);
            }
        }

        private void abort(Throwable original) {
            try {
                phases.close();
            } catch (IOException cleanup) {
                original.addSuppressed(cleanup);
            }
        }
    }

    private static final class ReadBatchCloseFailure extends UncheckedIOException {
        private ReadBatchCloseFailure(IOException cause) {
            super("Failed to end an animation read phase", cause);
        }
    }

    public record ReadBatch(AnimationStore animations, ReadPhaseSet phases) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            // Sealing must not wait for a consumer monitor held across an active get.
            animations.closed = true;
            phases.close();
        }
    }

    public enum State {
        UNLOADED, READY, FAILED
    }

    /** A null value is proven absent only when the full resolution is cacheable. */
    public record Resolution(Animation animation, boolean fallback, boolean cacheable) {
        public Resolution unstable() {
            return cacheable ? new Resolution(animation, fallback, false) : this;
        }
    }

    private record Slot(State state, Animation animation) {
    }
}
