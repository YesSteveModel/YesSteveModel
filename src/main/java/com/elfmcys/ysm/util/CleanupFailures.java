package com.elfmcys.ysm.util;

import org.jetbrains.annotations.Nullable;

/** Preserves fatal-error priority while entity animation cleanup continues across independently owned resources. */
public final class CleanupFailures {
    private CleanupFailures() {}

    public static @Nullable Throwable merge(@Nullable Throwable primary, @Nullable Throwable secondary) {
        if (primary == null)
            return secondary;
        if (secondary == null || secondary == primary)
            return primary;
        if ((secondary instanceof VirtualMachineError && !(secondary instanceof OutOfMemoryError)
            || secondary instanceof ThreadDeath)
            && !(primary instanceof VirtualMachineError && !(primary instanceof OutOfMemoryError)
                || primary instanceof ThreadDeath)) {
            var previous = primary;
            primary = secondary;
            secondary = previous;
        }
        try {
            primary.addSuppressed(secondary);
        } catch (OutOfMemoryError ignored) {
            // Diagnostic retention must not interrupt the remaining resource cleanup.
        }
        return primary;
    }
}
