package com.elfmcys.ysm.model.resource.client.render;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Optional benchmark-agent observations; producer entry follows the scheduler's atomic claim. */
public final class ResourcePriorityProbe {
    static volatile Sample active;
    private static final ThreadLocal<Long> PRODUCER_STARTED = new ThreadLocal<>();
    private static final ThreadLocal<Long> BUILD_STARTED = new ThreadLocal<>();

    private ResourcePriorityProbe() {}

    public static void producerStarted() {
        var sample = active;
        if (sample == null)
            return;
        var now = System.nanoTime();
        sample.producers.incrementAndGet();
        workStarted(sample);
        sample.producerStarted.compareAndSet(0, now);
        sample.producerThread = Thread.currentThread().getName();
        PRODUCER_STARTED.set(now);
    }

    public static void producerFinished() {
        var started = PRODUCER_STARTED.get();
        PRODUCER_STARTED.remove();
        var sample = active;
        if (sample != null && started != null) {
            sample.computeNanos.addAndGet(System.nanoTime() - started);
            sample.activeWork.decrementAndGet();
        }
    }

    public static void buildStarted() {
        var sample = active;
        if (sample == null)
            return;
        sample.builds.incrementAndGet();
        workStarted(sample);
        sample.buildThread = Thread.currentThread().getName();
        BUILD_STARTED.set(System.nanoTime());
    }

    public static void buildFinished() {
        var started = BUILD_STARTED.get();
        BUILD_STARTED.remove();
        var sample = active;
        if (sample != null && started != null) {
            sample.computeNanos.addAndGet(System.nanoTime() - started);
            sample.activeWork.decrementAndGet();
        }
    }

    public static void baked() {
        var sample = active;
        if (sample != null)
            sample.bakes.incrementAndGet();
    }

    static void backgroundStarted() {
        workStarted(active);
    }

    static void backgroundFinished() {
        active.activeWork.decrementAndGet();
    }

    private static void workStarted(Sample sample) {
        sample.peakWork.accumulateAndGet(sample.activeWork.incrementAndGet(), Math::max);
        sample.workThreads.add(Thread.currentThread().getName());
    }

    static final class Sample {
        final AtomicInteger producers = new AtomicInteger();
        final AtomicInteger builds = new AtomicInteger();
        final AtomicInteger bakes = new AtomicInteger();
        final AtomicInteger activeWork = new AtomicInteger();
        final AtomicInteger peakWork = new AtomicInteger();
        final java.util.Set<String> workThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicLong producerStarted = new AtomicLong();
        final AtomicLong computeNanos = new AtomicLong();
        volatile String producerThread;
        volatile String buildThread;
    }
}
