package com.elfmcys.ysm.client.gui;

/** Retains the observed pose envelope so looping GUI animations cannot pump the camera. */
public final class GuiModelView {
    private final Bounds envelope = new Bounds();

    public void reset() {
        envelope.clear();
    }

    public Transform fit(Bounds pose, double width, double height) {
        if (!pose.valid() || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            return null;
        envelope.include(pose.minX, pose.minY, pose.minZ);
        envelope.include(pose.maxX, pose.maxY, pose.maxZ);
        var scale = Math.min(width * 0.88 / Math.max(envelope.maxX - envelope.minX, 1.0e-6),
            height * 0.88 / Math.max(envelope.maxY - envelope.minY, 1.0e-6));
        scale = Math.min(scale, 1_600 / Math.max(envelope.maxZ - envelope.minZ, 1.0e-6));
        // Degenerate geometry must not create an unbounded camera magnification.
        scale = Math.min(scale, Math.max(width, height));
        return new Transform(scale, envelope.minX / 2 + envelope.maxX / 2, envelope.minY / 2 + envelope.maxY / 2,
            envelope.minZ / 2 + envelope.maxZ / 2);
    }

    public record Transform(double scale, double centerX, double centerY, double centerZ) {
    }

    public static final class Bounds {
        private double minX, minY, minZ, maxX, maxY, maxZ;
        private boolean finite;

        public Bounds() {
            clear();
        }

        public void clear() {
            minX = minY = minZ = Double.POSITIVE_INFINITY;
            maxX = maxY = maxZ = Double.NEGATIVE_INFINITY;
            finite = true;
        }

        public void include(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                finite = false;
                return;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        public boolean valid() {
            return finite && minX <= maxX && minY <= maxY && minZ <= maxZ;
        }
    }
}
