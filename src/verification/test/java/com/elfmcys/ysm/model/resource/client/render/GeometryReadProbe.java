package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.format.container.AssetContainerView;

/** Thread-confined observations inserted by the verification agent, outside production code. */
public final class GeometryReadProbe {
    private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();

    static Observation start(Runnable beforePayload, Runnable beforeDecode) {
        var observation = new Observation();
        observation.beforePayload = beforePayload;
        observation.beforeDecode = beforeDecode;
        CURRENT.set(observation);
        return observation;
    }

    static void stop() {
        CURRENT.remove();
    }

    public static void metadata() {
        var current = CURRENT.get();
        if (current != null)
            current.metadata++;
    }

    public static void payload() {
        var current = CURRENT.get();
        if (current != null) {
            current.payload++;
            if (current.beforePayload != null)
                current.beforePayload.run();
        }
    }

    public static void hash(AssetContainerView.ChunkInfo chunk) {
        var current = CURRENT.get();
        if (current != null && chunk.type().equals("model") && chunk.hash() != null)
            current.hash++;
    }

    public static void decode() {
        var current = CURRENT.get();
        if (current != null) {
            current.decode++;
            if (current.beforeDecode != null)
                current.beforeDecode.run();
        }
    }

    static final class Observation {
        int metadata;
        int payload;
        int hash;
        int decode;
        Runnable beforePayload;
        Runnable beforeDecode;
    }
}
