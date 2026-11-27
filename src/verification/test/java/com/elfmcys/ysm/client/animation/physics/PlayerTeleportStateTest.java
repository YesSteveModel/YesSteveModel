package com.elfmcys.ysm.client.animation.physics;

import com.elfmcys.ysm.util.UnsafeUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTeleportStateTest {
    @BeforeAll
    static void initializeMinecraftClasses() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        var gate = Bootstrap.class.getDeclaredField("isBootstrapped");
        gate.setAccessible(true);
        boolean previous = gate.getBoolean(null);
        gate.setBoolean(null, true);
        try {
            BuiltInRegistries.REGISTRY.size();
            var loader = PlayerTeleportStateTest.class.getClassLoader();
            Class.forName(ReplayLevel.class.getName(), true, loader);
            Class.forName(ClientPacketListener.class.getName(), true, loader);
            Class.forName(ReplayRemotePlayer.class.getName(), true, loader);
            Class.forName(ReplayLocalPlayer.class.getName(), true, loader);
        } finally {
            gate.setBoolean(null, previous);
        }
    }

    @Test
    void absolutePacketUsesCurrentRootAndVanillaAdmission() throws Exception {
        var replay = replay();
        var player = replay.player();
        replay.absolute(15.99);
        assertSame(PlayerTeleportState.NONE, PlayerTeleportState.capture(player));

        player.controlled = true;
        replay.absolute(24);
        assertSame(PlayerTeleportState.NONE, PlayerTeleportState.capture(player));

        player.controlled = false;
        replay.absolute(16);
        assertEquals(1, PlayerTeleportState.capture(player).epoch());
        assertEquals(0.0, player.getX());
        assertTrue(PlayerTeleportState.isLongJump(0, 16, 0));
        assertTrue(PlayerTeleportState.isLongJump(12, 0, 12));
    }

    @Test
    void lastInterpolationTickStillMovesBetweenRenderFrames() throws Exception {
        var replay = replay();
        var player = replay.player();
        replay.absolute(24);
        var state = PlayerTeleportState.capture(player);
        assertTrue(state.suppresses(player.tickCount));

        for (int step = 1; step <= 3; ++step) {
            player.interpolateTick();
            assertEquals(step * 8.0, player.getX());
            assertTrue(state.suppresses(player.tickCount));
        }

        assertEquals(18.0, player.getPosition(0.25F).x);
        assertEquals(22.0, player.getPosition(0.75F).x);
        player.interpolateTick();
        assertEquals(24.0, player.getPosition(0.25F).x);
        assertFalse(state.suppresses(player.tickCount));
    }

    @Test
    void ordinaryPacketsKeepTheOriginalDeadlineAndAnotherLongJumpRefreshesIt() throws Exception {
        var replay = replay();
        var player = replay.player();
        replay.absolute(24);
        var original = PlayerTeleportState.capture(player);
        player.interpolateTick();

        new ClientboundMoveEntityPacket.Pos(player.getId(), (short)2048, (short)0, (short)0, false)
            .handle(replay.listener());
        player.interpolateTick();
        assertEquals(13.5, player.getX());
        new ClientboundMoveEntityPacket.Rot(player.getId(), (byte)32, (byte)0, false).handle(replay.listener());
        replay.absolute(14);
        assertSame(original, PlayerTeleportState.capture(player));

        replay.absolute(40);
        var refreshed = PlayerTeleportState.capture(player);
        assertEquals(original.epoch() + 1, refreshed.epoch());
        player.interpolateTick();
        player.interpolateTick();
        assertFalse(original.suppresses(player.tickCount));
        assertTrue(refreshed.suppresses(player.tickCount));
    }

    @Test
    void localTeleportAdvancesEpochEvenAtTheSamePosition() throws Exception {
        var player = allocate(ReplayLocalPlayer.class);
        player.state = PlayerTeleportState.NONE;
        PlayerTeleportState.recordLocal(player);
        var first = PlayerTeleportState.capture(player);
        PlayerTeleportState.recordLocal(player);
        var second = PlayerTeleportState.capture(player);
        assertEquals(first.epoch() + 1, second.epoch());
        assertFalse(second.suppresses(player.tickCount));
    }

    private static Replay replay() throws Exception {
        var minecraft = allocate(Minecraft.class);
        setField(Minecraft.class, minecraft, "gameThread", Thread.currentThread());

        var level = allocate(ReplayLevel.class);
        var player = allocate(ReplayRemotePlayer.class);
        player.state = PlayerTeleportState.NONE;
        player.replayLevel = level;
        level.player = player;
        setField(Entity.class, player, "position", Vec3.ZERO);
        setField(Entity.class, player, "blockPosition", BlockPos.ZERO);
        setField(Entity.class, player, "chunkPosition", new ChunkPos(0, 0));
        setField(Entity.class, player, "dimensions", EntityDimensions.fixed(0.6F, 1.8F));
        setField(Entity.class, player, "packetPositionCodec", new VecDeltaCodec());
        setField(Entity.class, player, "mainSupportingBlockPos", Optional.empty());
        player.setLevelCallback(EntityInLevelCallback.NULL);

        var listener = allocate(ClientPacketListener.class);
        setField(ClientPacketListener.class, listener, "minecraft", minecraft);
        setField(ClientPacketListener.class, listener, "level", level);
        return new Replay(listener, player);
    }

    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(UnsafeUtil.getUnsafe().allocateInstance(type));
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private record Replay(ClientPacketListener listener, ReplayRemotePlayer player) {
        void absolute(double x) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(player.getId());
                buffer.writeDouble(x).writeDouble(0).writeDouble(0);
                buffer.writeByte(0).writeByte(0).writeBoolean(false);
                new ClientboundTeleportEntityPacket(buffer).handle(listener);
            } finally {
                buffer.release();
            }
        }
    }

    private static final class ReplayLevel extends ClientLevel {
        private ReplayRemotePlayer player;

        private ReplayLevel() {
            super(null, null, null, null, 0, 0, null, null, false, 0);
        }

        @Override
        public Entity getEntity(int id) {
            return id == player.getId() ? player : null;
        }

        @Override
        public ProfilerFiller getProfiler() {
            return InactiveProfiler.INSTANCE;
        }
    }

    private static final class ReplayRemotePlayer extends RemotePlayer implements PlayerTeleportState.Access {
        private PlayerTeleportState state;
        private ReplayLevel replayLevel;
        private boolean controlled;

        private ReplayRemotePlayer() {
            super(null, null);
        }

        void interpolateTick() {
            setOldPosAndRot();
            ++tickCount;
            aiStep();
        }

        @Override
        public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
            // Plain JUnit uses the untransformed classes. Record at the same pre-interpolation admission seam.
            if (teleport)
                PlayerTeleportState.recordRemote(this, x, y, z);
            super.lerpTo(x, y, z, yaw, pitch, steps, teleport);
        }

        @Override
        public boolean isControlledByLocalInstance() {
            return controlled;
        }

        @Override
        public ClientLevel level() {
            return replayLevel;
        }

        @Override
        protected void updateSwingTime() {}

        @Override
        protected void pushEntities() {}

        public PlayerTeleportState ysm$teleportState() {
            return state;
        }

        public void ysm$teleportState(PlayerTeleportState next) {
            state = next;
        }
    }

    private static final class ReplayLocalPlayer extends LocalPlayer implements PlayerTeleportState.Access {
        private PlayerTeleportState state;

        private ReplayLocalPlayer() {
            super(null, null, null, null, null, false, false);
        }

        public PlayerTeleportState ysm$teleportState() {
            return state;
        }

        public void ysm$teleportState(PlayerTeleportState next) {
            state = next;
        }
    }
}
