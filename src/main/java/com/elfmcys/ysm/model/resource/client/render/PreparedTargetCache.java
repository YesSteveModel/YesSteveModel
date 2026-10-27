package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.work.ClientResourceScheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Dependency pins inherit the existing target cache's retention and terminal boundaries. */
final class PreparedTargetCache<K, V extends AutoCloseable> implements AutoCloseable {
    private final Map<K, Entry> entries = new HashMap<>();
    private final Set<Entry> allEntries = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final ClientResourceScheduler scheduler;
    private boolean closed;

    PreparedTargetCache(ClientResourceScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    CompletableFuture<Pin> acquire(K key, ClientResourceScheduler.Dispatch consumer, ProducerFactory<V> producer) {
        return acquire(key, false, consumer, producer);
    }

    CompletableFuture<Pin> acquire(K key, boolean cacheOnly, ClientResourceScheduler.Dispatch consumer,
        ProducerFactory<V> producer) {
        final Entry entry;
        final Pin pin;
        final boolean produce;
        final boolean retryOnCacheMiss;
        synchronized (this) {
            if (closed)
                return CompletableFuture.failedFuture(new CancellationException("preparation owner stopped"));
            var current = entries.get(key);
            if (current != null && current.value == null && cacheOnly)
                return CompletableFuture.failedFuture(
                    com.elfmcys.ysm.format.AssetLoadException.access("Target preparation is still pending"));
            retryOnCacheMiss = current != null && current.value == null && current.cacheOnly && !cacheOnly;
            produce = current == null;
            entry = produce ? new Entry(key, cacheOnly, scheduler.dispatch(consumer.priority())) : current;
            if (produce) {
                entries.put(key, entry);
                allEntries.add(entry);
            }
            pin = new Pin(entry);
            entry.pins.add(pin);
            if (entry.value == null)
                pin.promotion = consumer.forwardPromotionTo(entry.dispatch);
            if (entry.value != null)
                pin.result.complete(pin);
        }
        pin.result.whenComplete((ignored, failure) -> {
            pin.unlinkPromotion();
            if (pin.result.isCancelled())
                pin.close();
        });
        if (produce) {
            try {
                var work = Objects.requireNonNull(producer.create(), "producer");
                var job = new Production(entry, work);
                synchronized (this) {
                    entry.production = job;
                }
                if (entry.abandoned.get()) {
                    job.discard();
                } else {
                    try {
                        job.register(entry.dispatch.enqueue(job));
                    } catch (RuntimeException rejected) {
                        try {
                            job.discard();
                        } catch (Throwable cleanup) {
                            rejected.addSuppressed(cleanup);
                        }
                        throw rejected;
                    }
                }
            } catch (Throwable rejected) {
                produce(entry, cancelled -> {
                    if (rejected instanceof Error fatal)
                        throw fatal;
                    if (rejected instanceof Exception failure)
                        throw failure;
                    throw new IllegalStateException(rejected);
                });
            }
        }
        return retryOnCacheMiss ? retryReadOnly(pin, key, consumer, producer) : pin.result;
    }

    private CompletableFuture<Pin> retryReadOnly(Pin pin, K key, ClientResourceScheduler.Dispatch consumer,
        ProducerFactory<V> producer) {
        var result = new CompletableFuture<Pin>();
        var retry = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Pin>>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                pin.result.cancel(false);
                var pending = retry.get();
                if (pending != null)
                    pending.cancel(false);
            }
        });
        pin.result.whenComplete((accepted, failure) -> {
            if (failure == null) {
                if (!result.complete(accepted))
                    accepted.close();
                return;
            }
            pin.close();
            if (result.isDone())
                return;
            var cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            boolean miss = cause instanceof java.io.IOException
                && (!(cause instanceof com.elfmcys.ysm.format.AssetLoadException asset)
                    || asset.reason() == com.elfmcys.ysm.format.AssetLoadException.Reason.ACCESS);
            if (!miss) {
                result.completeExceptionally(cause);
                return;
            }
            var pending = acquire(key, false, consumer, producer);
            retry.set(pending);
            if (result.isCancelled())
                pending.cancel(false);
            pending.whenComplete((ready, rejected) -> {
                if (rejected != null)
                    result.completeExceptionally(rejected);
                else if (!result.complete(ready))
                    ready.close();
            });
        });
        return result;
    }

    private void produce(Entry entry, Producer<V> producer) {
        V value = null;
        Throwable failure = null;
        try (producer) {
            value = Objects.requireNonNull(producer.produce(entry.abandoned::get), "prepared target");
        } catch (Throwable rejected) {
            failure = rejected;
        }
        final ArrayList<Pin> accepted;
        final boolean publish;
        synchronized (this) {
            publish = !closed && entries.get(entry.key) == entry && !entry.pins.isEmpty() && failure == null;
            accepted = new ArrayList<>(entry.pins);
            if (publish)
                entry.value = value;
            else
                entries.remove(entry.key, entry);
        }
        if (publish) {
            for (var pin : accepted)
                if (!pin.result.complete(pin))
                    pin.close();
        } else {
            var cause = failure == null ? new CancellationException("preparation abandoned") : failure;
            if (value != null) {
                try {
                    release(value);
                } catch (Throwable cleanup) {
                    cause.addSuppressed(cleanup);
                }
            }
            for (var pin : accepted) {
                pin.result.completeExceptionally(cause);
                pin.close();
            }
        }
    }

    @Override
    public void close() {
        final ArrayList<Pin> pins = new ArrayList<>();
        synchronized (this) {
            if (closed)
                return;
            closed = true;
            // Ready pins have already transferred to targets and outlive this admission owner.
            allEntries.forEach(entry -> {
                if (entry.value == null)
                    pins.addAll(entry.pins);
            });
            entries.clear();
            allEntries.clear();
        }
        Throwable cleanup = null;
        for (var pin : pins) {
            pin.result.completeExceptionally(new CancellationException("preparation owner stopped"));
            try {
                pin.close();
            } catch (Throwable failure) {
                if (cleanup == null)
                    cleanup = failure;
                else
                    cleanup.addSuppressed(failure);
            }
        }
        if (cleanup != null)
            throw new IllegalStateException("Preparation owner close failed", cleanup);
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized boolean containsReady(K key) {
        var entry = entries.get(key);
        return entry != null && entry.value != null;
    }

    private final class Production implements Runnable {
        private final Entry entry;
        private Producer<V> producer;
        private final java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
        private ClientResourceScheduler.QueuedTask queued;

        private Production(Entry entry, Producer<V> producer) {
            this.entry = entry;
            this.producer = producer;
        }

        private void register(ClientResourceScheduler.QueuedTask task) {
            synchronized (PreparedTargetCache.this) {
                queued = task;
            }
            if (entry.abandoned.get())
                discard();
        }

        @Override
        public void run() {
            if (!phase.compareAndSet(0, 1))
                return;
            try {
                produce(entry, producer);
            } finally {
                producer = null;
                phase.set(2);
                synchronized (PreparedTargetCache.this) {
                    if (entry.production == this)
                        entry.production = null;
                }
            }
        }

        private void discard() {
            synchronized (PreparedTargetCache.this) {
                if (queued != null)
                    queued.withdraw();
            }
            // A claimed node can still be before run(); phase owns the input handoff.
            if (!phase.compareAndSet(0, 2))
                return;
            var abandoned = producer;
            producer = null;
            synchronized (PreparedTargetCache.this) {
                if (entry.production == this)
                    entry.production = null;
            }
            release(abandoned);
        }
    }

    final class Pin implements AutoCloseable {
        private final Entry entry;
        private final CompletableFuture<Pin> result = new CompletableFuture<>();
        private final AtomicBoolean released = new AtomicBoolean();
        private ClientResourceScheduler.PromotionLink promotion;

        private Pin(Entry entry) {
            this.entry = entry;
        }

        V value() {
            synchronized (PreparedTargetCache.this) {
                if (released.get() || entry.value == null)
                    throw new IllegalStateException("preparation pin is inactive");
                return entry.value;
            }
        }

        private void unlinkPromotion() {
            synchronized (PreparedTargetCache.this) {
                if (promotion != null) {
                    promotion.close();
                    promotion = null;
                }
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true))
                return;
            unlinkPromotion();
            V value = null;
            Production production = null;
            synchronized (PreparedTargetCache.this) {
                entry.pins.remove(this);
                if (entry.pins.isEmpty()) {
                    entries.remove(entry.key, entry);
                    allEntries.remove(entry);
                    entry.abandoned.set(true);
                    value = entry.value;
                    entry.value = null;
                    production = entry.production;
                    entry.production = null;
                }
            }
            try {
                if (production != null)
                    production.discard();
            } finally {
                if (value != null)
                    release(value);
            }
        }
    }

    private final class Entry {
        private final K key;
        private final boolean cacheOnly;
        private final ClientResourceScheduler.Dispatch dispatch;
        private final Set<Pin> pins = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private V value;
        private Production production;

        private Entry(K key, boolean cacheOnly, ClientResourceScheduler.Dispatch dispatch) {
            this.key = key;
            this.cacheOnly = cacheOnly;
            this.dispatch = dispatch;
        }
    }

    @FunctionalInterface
    interface Producer<V> extends AutoCloseable {
        V produce(BooleanSupplier cancelled) throws Exception;

        @Override
        default void close() throws Exception {}
    }

    @FunctionalInterface
    interface ProducerFactory<V> {
        Producer<V> create() throws Exception;
    }

    private static void release(AutoCloseable value) {
        try {
            value.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("preparation close failed", failure);
        }
    }
}
