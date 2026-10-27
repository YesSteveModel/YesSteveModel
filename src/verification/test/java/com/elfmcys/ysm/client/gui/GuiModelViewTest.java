package com.elfmcys.ysm.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiModelViewTest {
    @Test
    void fitsTranslatedScaledAndDeepPosesInsideTheViewport() {
        var bounds = bounds(-71, -76, -900, -67, -68, 9_100);
        var camera = new GuiModelView().fit(bounds, 52, 70);
        for (var x : new double[] {-71, -67}) {
            for (var y : new double[] {-76, -68}) {
                for (var z : new double[] {-900, 9_100}) {
                    assertTrue(Math.abs((x - camera.centerX()) * camera.scale()) <= 52 * 0.44);
                    assertTrue(Math.abs((y - camera.centerY()) * camera.scale()) <= 70 * 0.44);
                    assertTrue(Math.abs((z - camera.centerZ()) * camera.scale()) <= 800);
                }
            }
        }
    }

    @Test
    void retainsTheObservedEnvelopeAcrossHoverFocusAndLoopRestarts() {
        var view = new GuiModelView();
        var first = view.fit(bounds(-1, -2, -1, 1, 2, 1), 52, 70);
        var hover = view.fit(bounds(-4, -1, -2, 3, 3, 1), 52, 70);
        assertTrue(hover.scale() < first.scale());
        assertEquals(hover, view.fit(bounds(-1, -2, -1, 1, 2, 1), 52, 70));
        assertEquals(hover, view.fit(bounds(-4, -1, -2, 3, 3, 1), 52, 70));
        view.reset();
        assertEquals(first, view.fit(bounds(-1, -2, -1, 1, 2, 1), 52, 70));
    }

    @Test
    void rejectsEmptyAndNonfiniteFramesWithoutPoisoningTheCamera() {
        var view = new GuiModelView();
        var first = view.fit(bounds(0, 0, 0, 1, 2, 1), 52, 70);
        assertNull(view.fit(new GuiModelView.Bounds(), 52, 70));
        assertNull(view.fit(bounds(0, 0, 0, Double.NaN, 1, 1), 52, 70));
        assertNull(view.fit(bounds(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), 52, 70));
        assertNull(view.fit(bounds(0, 0, 0, 1, 1, 1), 0, 70));
        assertEquals(first, view.fit(bounds(0, 0, 0, 1, 2, 1), 52, 70));
    }

    @Test
    void finiteExtremeAndDegenerateBoundsHaveFiniteTransforms() {
        for (var range : new double[] {0, 1.0e-30, 1.0e30, Float.MAX_VALUE}) {
            var camera = new GuiModelView().fit(bounds(-range, -range, -range, range, range, range), 52, 70);
            assertNotNull(camera);
            assertTrue(Double.isFinite(camera.scale()));
            assertTrue(camera.scale() > 0);
            assertTrue(camera.scale() <= 70);
            assertTrue(Math.abs(range * camera.scale()) <= 52 * 0.44 + 1.0e-6);
        }
    }

    private static GuiModelView.Bounds bounds(double x0, double y0, double z0, double x1, double y1, double z1) {
        var result = new GuiModelView.Bounds();
        result.include(x0, y0, z0);
        result.include(x1, y1, z1);
        return result;
    }
}
