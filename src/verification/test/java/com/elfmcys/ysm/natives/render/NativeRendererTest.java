package com.elfmcys.ysm.natives.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRendererTest {
    @Test
    void convertsJavaArgbToNativeLowRedRgba() {
        assertEquals(0xAADDCCBB, NativeRenderer.packColor(0xAABBCCDD));
        assertEquals(0xFF0000FF, NativeRenderer.packColor(0xFFFF0000));
        assertEquals(0xFFFF0000, NativeRenderer.packColor(0xFF0000FF));
    }

    @Test
    void fallbackConsumerFailurePropagatesWithoutAStageRelay() {
        com.elfmcys.ysm.testutil.NativeLibraryExtension.loadNativeLibrary();
        FallbackVertexWriter.getVertexData(1);
        for (var failure : new Throwable[] {new OutOfMemoryError("host consumer"),
            new IllegalStateException("host consumer")}) {
            var consumer = (com.mojang.blaze3d.vertex.VertexConsumer)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {com.mojang.blaze3d.vertex.VertexConsumer.class},
                (proxy, method, arguments) -> { throw failure; });
            assertSame(failure, assertThrows(failure.getClass(), () -> FallbackVertexWriter.write(consumer, 1, 0)));
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var next = (com.mojang.blaze3d.vertex.VertexConsumer)java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {com.mojang.blaze3d.vertex.VertexConsumer.class},
            (proxy, method, arguments) -> { calls.incrementAndGet(); return null; });
        FallbackVertexWriter.write(next, 1, 0);
        assertEquals(1, calls.get());
    }
}
