package net.minecraft.world.entity;

/** Minimal isolated-smoke subject; the production Minecraft class is supplied by Forge. */
public class Entity {
    public net.minecraft.world.phys.Vec3 velocity = net.minecraft.world.phys.Vec3.f_82478_;
    public final net.minecraft.world.level.Level world = new net.minecraft.world.level.Level();
    public long reads;
    public long positionReads;
    public net.minecraft.world.phys.Vec3 position = net.minecraft.world.phys.Vec3.f_82478_;

    public net.minecraft.world.phys.Vec3 m_20184_() {
        reads++;
        return velocity;
    }

    public net.minecraft.world.level.Level m_9236_() {
        return world;
    }

    public void tick() {}

    public net.minecraft.world.phys.Vec3 m_20182_() {
        positionReads++;
        return position;
    }
}
