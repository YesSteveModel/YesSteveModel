package com.elfmcys.ysm.client.physics.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SwingLimitOutlineTest {
    private static final double EPSILON = 1e-10;

    @Test
    void clipsCornersAgainstTheReachableDiskAndRetainsItsCircularBoundary() {
        double yMin = Math.toRadians(120);
        double yMax = Math.toRadians(150);
        double zMin = Math.toRadians(45);
        double zMax = Math.toRadians(120);
        double disk = Math.PI;
        var points = new ArrayList<Point>();
        SwingLimitOutline.trace(yMin, yMax, zMin, zMax, (y1, z1, y2, z2) -> {
            points.add(new Point(y1, z1));
            points.add(new Point(y2, z2));
        });

        assertFalse(points.isEmpty());
        for (var point : points) {
            assertTrue(point.y >= yMin - EPSILON && point.y <= yMax + EPSILON);
            assertTrue(point.z >= zMin - EPSILON && point.z <= zMax + EPSILON);
            assertTrue(Math.hypot(point.y, point.z) <= disk + EPSILON);
        }
        assertTrue(contains(points, yMin, zMin));
        assertTrue(contains(points, yMax, zMin));
        assertTrue(contains(points, yMax, Math.sqrt(disk * disk - yMax * yMax)));
        assertTrue(contains(points, Math.sqrt(disk * disk - zMax * zMax), zMax));
        assertFalse(contains(points, yMax, zMax));
        assertTrue(points.stream().anyMatch(point -> point.y < yMax - EPSILON && point.z < zMax - EPSILON
            && Math.abs(Math.hypot(point.y, point.z) - disk) <= EPSILON));
    }

    @Test
    void tracesALockedAxisOnceAndPreservesASingleAllowedDirection() {
        var segments = new ArrayList<Point>();
        double extent = Math.toRadians(20);
        SwingLimitOutline.trace(-extent, extent, 0, 0, (y1, z1, y2, z2) -> {
            assertTrue(y2 > y1);
            assertEquals(0, z1);
            assertEquals(0, z2);
            segments.add(new Point(y1, y2));
        });
        assertEquals(-extent, segments.get(0).y, EPSILON);
        assertEquals(extent, segments.get(segments.size() - 1).z, EPSILON);

        var points = new ArrayList<Point>();
        SwingLimitOutline.trace(extent, extent, -extent, -extent, (y1, z1, y2, z2) -> {
            assertEquals(y1, y2);
            assertEquals(z1, z2);
            points.add(new Point(y1, z1));
        });
        assertEquals(1, points.size());
        assertTrue(contains(points, extent, -extent));
    }

    private static boolean contains(ArrayList<Point> points, double y, double z) {
        return points.stream().anyMatch(point -> Math.abs(point.y - y) <= EPSILON && Math.abs(point.z - z) <= EPSILON);
    }

    private record Point(double y, double z) {
    }
}
