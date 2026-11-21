package com.elfmcys.ysm.testutil;

import java.nio.file.Path;

/** Faults the actual provider call when the read-handle agent is enabled. */
public final class CacheReadFaults {
    private CacheReadFaults() {}

    public static void set(Path target, String failure) throws ReflectiveOperationException {
        Class.forName("com.elfmcys.ysm.benchmark.ReadHandleProbe").getMethod("fail", Path.class, String.class)
            .invoke(null, target, failure);
    }
}
