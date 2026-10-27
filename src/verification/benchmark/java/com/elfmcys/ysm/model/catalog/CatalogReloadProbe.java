package com.elfmcys.ysm.model.catalog;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLongArray;

/** Benchmark-only counters; the timing agent never enters a production run. */
public final class CatalogReloadProbe {
    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[8]);
    private static final AtomicLongArray CALLS = new AtomicLongArray(4);
    private static final AtomicLongArray NANOS = new AtomicLongArray(4);
    private static final AtomicLongArray BYTES = new AtomicLongArray(4);
    private static volatile boolean enabled;

    private CatalogReloadProbe() {}

    public static void enter(int stage) {
        if (enabled) {
            var start = START.get();
            start[stage * 2 + 1] = THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
            start[stage * 2] = System.nanoTime();
        }
    }

    public static void exit(int stage) {
        if (enabled) {
            var end = System.nanoTime();
            var bytes = THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
            var start = START.get();
            NANOS.addAndGet(stage, end - start[stage * 2]);
            BYTES.addAndGet(stage, bytes - start[stage * 2 + 1]);
            CALLS.incrementAndGet(stage);
        }
    }

    public static void reset() {
        enabled = false;
        for (var stage = 0; stage < 4; stage++) {
            CALLS.set(stage, 0);
            NANOS.set(stage, 0);
            BYTES.set(stage, 0);
        }
        enabled = true;
    }

    public static long calls(int stage) {
        return CALLS.get(stage);
    }

    public static String report() {
        var result = new StringBuilder();
        var names = new String[] {"build", "publishIncludingObservers", "observer", "metadataParse"};
        for (var stage = 0; stage < names.length; stage++) {
            result.append(names[stage]).append("={calls=").append(CALLS.get(stage)).append(",ns=")
                .append(NANOS.get(stage)).append(",heapBytes=").append(BYTES.get(stage)).append("} ");
        }
        return result.toString();
    }
}
