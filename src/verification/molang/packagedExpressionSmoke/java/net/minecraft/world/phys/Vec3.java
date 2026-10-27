package net.minecraft.world.phys;

/** Minimal isolated-smoke signature type; Forge supplies the production class. */
public final class Vec3 {
    // The smoke executes the reobfuscated bundle, where Vec3.ZERO uses its runtime name.
    public static final Vec3 f_82478_ = new Vec3();
    public final double f_82479_;
    public final double f_82480_;
    public final double f_82481_;

    public Vec3() {
        this(0, 0, 0);
    }

    public Vec3(double x, double y, double z) {
        f_82479_ = x;
        f_82480_ = y;
        f_82481_ = z;
    }
}
