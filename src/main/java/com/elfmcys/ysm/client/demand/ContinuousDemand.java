package com.elfmcys.ysm.client.demand;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Tracks one owner's real, continuous user intent without retaining intent history. */
public final class ContinuousDemand<T> {
    public static final long HOVER_DWELL_MILLIS = 300;
    public static final long LOCAL_SWITCH_DWELL_MILLIS = 300;
    public static final long NETWORK_SWITCH_DWELL_MILLIS = 700;
    public static final long PAGE_PREVIOUS_DWELL_MILLIS = 700;

    private T current;
    private boolean active;
    private long startedAtMillis;
    private boolean hasPrevious;
    private long previousDurationMillis;
    private long generation;

    public boolean observe(T next, long nowMillis) {
        Objects.requireNonNull(next, "next");
        if (active && next.equals(current)) {
            return false;
        }
        if (active) {
            previousDurationMillis = elapsed(nowMillis);
            hasPrevious = true;
        }
        current = next;
        active = true;
        startedAtMillis = nowMillis;
        generation++;
        return true;
    }

    public void end(long nowMillis) {
        if (!active) {
            return;
        }
        previousDurationMillis = elapsed(nowMillis);
        hasPrevious = true;
        current = null;
        active = false;
        generation++;
    }

    public boolean isCurrent(T expected) {
        return active && Objects.equals(current, expected);
    }

    public @Nullable T current() {
        return current;
    }

    public long generation() {
        return generation;
    }

    public boolean elapsedStrictlyExceeds(long nowMillis, long thresholdMillis) {
        requireThreshold(thresholdMillis);
        return active && elapsed(nowMillis) > thresholdMillis;
    }

    public boolean elapsedAtLeast(long nowMillis, long thresholdMillis) {
        requireThreshold(thresholdMillis);
        return active && elapsed(nowMillis) >= thresholdMillis;
    }

    public boolean requiresDwell(boolean missing, long thresholdMillis) {
        requireThreshold(thresholdMillis);
        return active && missing && hasPrevious && previousDurationMillis <= thresholdMillis;
    }

    public boolean effectEligible(boolean missing, long nowMillis, long thresholdMillis) {
        return !requiresDwell(missing, thresholdMillis) || elapsedStrictlyExceeds(nowMillis, thresholdMillis);
    }

    /** Page switches share one previous-page test while each effect keeps its own current-page threshold. */
    public boolean pageEffectEligible(boolean missing, long nowMillis, long thresholdMillis) {
        requireThreshold(thresholdMillis);
        return active && (!requiresDwell(missing, PAGE_PREVIOUS_DWELL_MILLIS)
            || elapsedAtLeast(nowMillis, thresholdMillis));
    }

    public void reset() {
        current = null;
        active = false;
        startedAtMillis = 0;
        hasPrevious = false;
        previousDurationMillis = 0;
        generation++;
    }

    private long elapsed(long nowMillis) {
        return Math.max(0, nowMillis - startedAtMillis);
    }

    private static void requireThreshold(long thresholdMillis) {
        if (thresholdMillis < 0) {
            throw new IllegalArgumentException("Demand threshold cannot be negative");
        }
    }
}
