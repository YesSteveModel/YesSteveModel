package com.elfmcys.ysm.natives;

import java.io.Closeable;

public final class NativeProfiler {
    private NativeProfiler() {}

    public static Scope beginAnimatableUpdate() {
        return NativeRuntime.isTracyEnabled() ? beginZone(JavaZone.ANIMATABLE_UPDATE) : Scope.NOOP;
    }

    public static Scope beginRenderer() {
        return NativeRuntime.isTracyEnabled() ? beginZone(JavaZone.RENDERER) : Scope.NOOP;
    }

    public static Scope beginFallbackVertexWrite() {
        return NativeRuntime.isTracyEnabled() ? beginZone(JavaZone.FALLBACK_VERTEX_WRITER) : Scope.NOOP;
    }

    public static boolean beginFrame() {
        return NativeRuntime.isTracyEnabled() && nBeginFrame() != 0;
    }

    public static void endFrame(boolean active) {
        if (active) {
            nEndFrame();
        }
    }

    private static Scope beginZone(JavaZone zone) {
        var token = nBeginZone(zone.sourceLocation);
        return token == 0 ? Scope.NOOP : new Scope(token);
    }

    private enum JavaZone {
        ANIMATABLE_UPDATE("YSM/Java/AnimatableEntity.update", "AnimatableEntity.update", "AnimatableEntity.java"),
        RENDERER("YSM/Java/NativeRenderer.render", "NativeRenderer.render", "NativeRenderer.java"),
        FALLBACK_VERTEX_WRITER("YSM/Java/FallbackVertexWriter.write", "FallbackVertexWriter.write",
            "FallbackVertexWriter.java");

        private final long sourceLocation;

        JavaZone(String name, String function, String file) {
            sourceLocation = nCreateSourceLocation(name, function, file, 0);
            if (sourceLocation == 0) {
                throw new IllegalStateException("Failed to create native profile source location: " + name);
            }
        }
    }

    public static final class Scope implements Closeable {
        private static final Scope NOOP = new Scope(0);

        private final long token;

        private Scope(long token) {
            this.token = token;
        }

        @Override
        public void close() {
            if (token != 0) {
                nEndZone(token);
            }
        }
    }

    private static native long nCreateSourceLocation(String name, String function, String file, int line);

    private static native long nBeginZone(long sourceLocation);

    private static native void nEndZone(long token);

    private static native long nBeginFrame();

    private static native void nEndFrame();
}
