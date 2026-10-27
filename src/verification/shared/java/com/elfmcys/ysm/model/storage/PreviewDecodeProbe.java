package com.elfmcys.ysm.model.storage;

import java.util.concurrent.atomic.AtomicInteger;

/** Independent observation at the real native codec call site. */
public final class PreviewDecodeProbe {
    private static final java.util.concurrent.atomic.AtomicBoolean TAMPER =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final AtomicInteger DECODES = new AtomicInteger();
    private static final ThreadLocal<Boolean> ORACLE = ThreadLocal.withInitial(() -> false);

    private static volatile boolean scope;
    private static final java.util.Map<String, Integer> GEOMETRY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Integer> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();

    public static void beginScope() {
        GEOMETRY.clear();
        TEXTURES.clear();
        scope = true;
    }

    public static void endScope() {
        scope = false;
    }

    public static void geometry(String resource) {
        if (scope)
            GEOMETRY.merge(resource, 1, Integer::sum);
    }

    public static void texture(String component) {
        if (scope)
            TEXTURES.merge(component, 1, Integer::sum);
    }

    public static java.util.Map<String, Integer> geometry() {
        return java.util.Map.copyOf(GEOMETRY);
    }

    public static java.util.Map<String, Integer> textures() {
        return java.util.Map.copyOf(TEXTURES);
    }

    private PreviewDecodeProbe() {}

    public static void decoded() {
        if (!ORACLE.get())
            DECODES.incrementAndGet();
    }

    public static void tamperNextWrite() {
        TAMPER.set(true);
    }

    public static void stagingWritten(CacheFile file) {
        if (TAMPER.compareAndSet(true, false)) {
            try {
                file.position(0);
                file.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    public static void reset() {
        DECODES.set(0);
    }

    public static int count() {
        return DECODES.get();
    }

    public static void oracle(Runnable action) {
        ORACLE.set(true);
        try {
            action.run();
        } finally {
            ORACLE.remove();
        }
    }
}
