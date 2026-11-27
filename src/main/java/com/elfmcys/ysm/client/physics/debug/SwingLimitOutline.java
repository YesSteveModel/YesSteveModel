package com.elfmcys.ysm.client.physics.debug;

import java.util.Arrays;

final class SwingLimitOutline {
    static final double RADIUS = Math.PI;
    private static final double FULL_CIRCLE = 2 * Math.PI;
    private static final double ARC_STEP = Math.PI / 24;

    private SwingLimitOutline() {}

    static void trace(double minY, double maxY, double minZ, double maxZ, Segment output) {
        side(minY, minZ, maxZ, true, output);
        if (maxY != minY) {
            side(maxY, minZ, maxZ, true, output);
        }
        if (maxY != minY) {
            side(minZ, minY, maxY, false, output);
            if (maxZ != minZ) {
                side(maxZ, minY, maxY, false, output);
            }
        } else if (maxZ == minZ && Math.hypot(minY, minZ) <= RADIUS) {
            output.accept(minY, minZ, minY, minZ);
        }

        if (maxY == minY || maxZ == minZ) {
            return;
        }
        // Circle membership can only change at a rectangle side. Splitting there keeps the arc endpoints exact.
        double[] cuts = {0, FULL_CIRCLE, Math.acos(minY / RADIUS), FULL_CIRCLE - Math.acos(minY / RADIUS),
            Math.acos(maxY / RADIUS), FULL_CIRCLE - Math.acos(maxY / RADIUS), wrap(Math.asin(minZ / RADIUS)),
            Math.PI - Math.asin(minZ / RADIUS), wrap(Math.asin(maxZ / RADIUS)), Math.PI - Math.asin(maxZ / RADIUS)};
        Arrays.sort(cuts);
        for (int index = 1; index < cuts.length; index++) {
            double start = cuts[index - 1];
            double end = cuts[index];
            double middle = (start + end) * 0.5;
            double y = RADIUS * Math.cos(middle);
            double z = RADIUS * Math.sin(middle);
            if (end > start && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                int segments = (int)Math.ceil((end - start) / ARC_STEP);
                for (int i = 0; i < segments; i++) {
                    double a = start + (end - start) * i / segments;
                    double b = start + (end - start) * (i + 1) / segments;
                    output.accept(RADIUS * Math.cos(a), RADIUS * Math.sin(a), RADIUS * Math.cos(b),
                        RADIUS * Math.sin(b));
                }
            }
        }
    }

    private static void side(double fixed, double min, double max, boolean fixedY, Segment output) {
        double extent = Math.sqrt((RADIUS - Math.abs(fixed)) * (RADIUS + Math.abs(fixed)));
        double low = Math.max(min, -extent);
        double high = Math.min(max, extent);
        if (low > high || min == max) {
            return;
        }
        int segments = Math.max(1, (int)Math.ceil((high - low) / ARC_STEP));
        for (int i = 0; i < segments; i++) {
            double a = low + (high - low) * i / segments;
            double b = low + (high - low) * (i + 1) / segments;
            if (fixedY) {
                output.accept(fixed, a, fixed, b);
            } else {
                output.accept(a, fixed, b, fixed);
            }
        }
    }

    private static double wrap(double angle) {
        return angle < 0 ? FULL_CIRCLE + angle : angle;
    }

    @FunctionalInterface
    interface Segment {
        void accept(double y1, double z1, double y2, double z2);
    }
}
