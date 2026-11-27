package com.elfmcys.ysm.client.animation.physics;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;

/** A client player's latest accepted teleport, published by the client thread for animation sampling. */
public record PlayerTeleportState(long epoch, boolean remote, int receivedTick) {

    public static final PlayerTeleportState NONE = new PlayerTeleportState(0, false, 0);
    private static final int INTERPOLATION_TICKS = 3;
    private static final double LONG_JUMP_DISTANCE_SQUARED = 16.0 * 16.0;

    public static PlayerTeleportState capture(Entity entity) {
        return entity instanceof Access access ? access.ysm$teleportState() : NONE;
    }

    public static boolean isLongJump(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz >= LONG_JUMP_DISTANCE_SQUARED;
    }

    public boolean suppresses(int entityTickCount) {
        // The third entity tick reaches the target, but its render frames still interpolate from the previous position.
        return remote && Integer.compareUnsigned(entityTickCount - receivedTick, INTERPOLATION_TICKS) <= 0;
    }

    public static void recordLocal(LocalPlayer player) {
        record((Access)player, false, 0);
    }

    public static void recordRemote(RemotePlayer player, double x, double y, double z) {
        if (isLongJump(x - player.getX(), y - player.getY(), z - player.getZ()))
            record((Access)player, true, player.tickCount);
    }

    private static void record(Access access, boolean remote, int tick) {
        access.ysm$teleportState(new PlayerTeleportState(access.ysm$teleportState().epoch() + 1, remote, tick));
    }

    public interface Access {
        PlayerTeleportState ysm$teleportState();

        void ysm$teleportState(PlayerTeleportState state);
    }
}
