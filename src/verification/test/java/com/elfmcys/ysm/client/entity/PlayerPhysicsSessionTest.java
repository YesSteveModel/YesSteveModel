package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.physics.PlayerTeleportState;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.render.RenderTargetKey;
import com.elfmcys.ysm.natives.render.NativeModelState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static com.elfmcys.ysm.client.entity.PlayerPhysicsSession.ANIMATION_ONLY;
import static com.elfmcys.ysm.natives.render.NativeModelState.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsSessionTest {
    private final PlayerPhysicsSession session = new PlayerPhysicsSession("fixture");
    private final Object world = new Object();

    @Test
    void classificationUsesScopeBeforeObserverFlagsAndIgnoresMutability() {
        assertEquals(PhysicsRenderPass.PURE_ANIMATION_ONLY,
            PhysicsRenderPass.classify(new RenderContext(false, false, false, false, false, true, true)));
        assertEquals(PhysicsRenderPass.PURE_ANIMATION_ONLY,
            PhysicsRenderPass.classify(new RenderContext(true, true, true, false, false, false, false)));
        assertEquals(PhysicsRenderPass.PURE_ANIMATION_ONLY,
            PhysicsRenderPass.classify(new RenderContext(true, true, false, true, false, false, true)));
        assertEquals(PhysicsRenderPass.PURE_ANIMATION_ONLY,
            PhysicsRenderPass.classify(new RenderContext(true, false, false, false, true, true, false)));
        assertEquals(PhysicsRenderPass.WORLD_OBSERVER,
            PhysicsRenderPass.classify(new RenderContext(true, true, false, false, false, false, false)));
        assertEquals(PhysicsRenderPass.WORLD_OBSERVER,
            PhysicsRenderPass.classify(new RenderContext(true, false, false, false, false, true, true)));
        assertEquals(PhysicsRenderPass.CANONICAL,
            PhysicsRenderPass.classify(RenderContext.levelImmutable().withImmutable(false)));
    }

    @Test
    void observesUntilCanonicalAndCommitsEvenWhenExtractionFails() {
        assertEquals(ANIMATION_ONLY, mode(PlayerTeleportState.NONE, 10, 0, Vec3.ZERO, 10, false));
        assertEquals(RESET, mode(PlayerTeleportState.NONE, 10, 0, Vec3.ZERO, 10, true));
        accept(RESET, PHYSICS_ACCEPTED, PlayerTeleportState.NONE, 10, 0, Vec3.ZERO, 10);
        long accepted = session.sampleEpoch();
        assertTrue(session.hasSample());
        assertEquals(OBSERVE, mode(PlayerTeleportState.NONE, 10, 0, Vec3.ZERO, 10, true));
        assertEquals(OBSERVE, mode(PlayerTeleportState.NONE, 11, 0.05, Vec3.ZERO, 11, false));
        assertEquals(accepted, session.sampleEpoch());
        assertEquals(0.10, session.secondsSinceSample(0.10), 1e-10);
        assertEquals(STEP, mode(PlayerTeleportState.NONE, 12, 0.10, Vec3.ZERO, 12, true));
        accept(STEP, PHYSICS_ACCEPTED | EXTRACT_OK, PlayerTeleportState.NONE, 12, 0.10, Vec3.ZERO, 12);
        assertNotEquals(accepted, session.sampleEpoch());
        assertEquals(OBSERVE, mode(PlayerTeleportState.NONE, 13, 0.10, Vec3.ZERO, 13, true));
    }

    @Test
    void localTeleportRevokesPriorDrawAtZeroTimeWithoutSelfInitializing() {
        accept(RESET, PHYSICS_ACCEPTED | EXTRACT_OK, PlayerTeleportState.NONE, 10, 0, Vec3.ZERO, 10);
        long beforeTeleport = session.sampleEpoch();
        var teleport = new PlayerTeleportState(1, false, 0);
        session.invalidateIngress(world, teleport.epoch());
        assertFalse(session.hasSample());
        assertNotEquals(beforeTeleport, session.sampleEpoch());
        assertEquals(ANIMATION_ONLY, mode(teleport, 10, 0, Vec3.ZERO, 10.5F, false));
        assertEquals(ANIMATION_ONLY, mode(teleport, 10, 0, Vec3.ZERO, 10.5F, false));
        assertEquals(RESET, mode(teleport, 10, 0, Vec3.ZERO, 10.5F, true));
        accept(RESET, PHYSICS_ACCEPTED | EXTRACT_OK, teleport, 10, 0, Vec3.ZERO, 10.5F);
        session.invalidateIngress(new Object(), teleport.epoch());
        assertFalse(session.hasSample());
    }

    @Test
    void longJumpResetsThroughoutInterpolatedMotionAndOnceAtStableLanding() {
        for (int samplesInside : new int[] {0, 1, 3}) {
            var teleport = new PlayerTeleportState(samplesInside + 1, true, 20 + samplesInside * 20);
            float tick = teleport.receivedTick();
            for (int index = 3 - samplesInside + 1; index <= 3; ++index) {
                var position = new Vec3(index * 8 - 2, 0, 0);
                float frame = tick + index + 0.25F;
                double time = frame / 20.0;
                assertEquals(RESET, mode(teleport, (int)tick + index, time, position, frame, true));
                accept(RESET, PHYSICS_ACCEPTED | EXTRACT_OK, teleport, (int)tick + index, time, position, frame);
                assertEquals(OBSERVE, mode(teleport, (int)tick + index, time, position, frame, false));
                var laterPosition = position.add(4, 0, 0);
                assertEquals(RESET, mode(teleport, (int)tick + index, time + 0.025, laterPosition, frame + 0.5F, true));
                accept(RESET, PHYSICS_ACCEPTED | EXTRACT_OK, teleport, (int)tick + index, time + 0.025, laterPosition,
                    frame + 0.5F);
            }
            var landing = new Vec3(24, 0, 0);
            double time = (tick + 4) / 20.0;
            assertEquals(RESET, mode(teleport, (int)tick + 4, time, landing, tick + 4, true));
            accept(RESET, PHYSICS_ACCEPTED | EXTRACT_OK, teleport, (int)tick + 4, time, landing, tick + 4);
            assertEquals(STEP, mode(teleport, (int)tick + 5, time + 0.05, landing.add(0.4, 0, 0), tick + 5, true));
        }
    }

    @Test
    void resetBoundariesUseDoubleTimeAndWorldPosition() {
        var teleport = PlayerTeleportState.NONE;
        accept(RESET, PHYSICS_ACCEPTED, teleport, 20, 0, Vec3.ZERO, 20);
        assertEquals(STEP, mode(teleport, 24, 0.249, new Vec3(15.99, 0, 0), 24, true));
        assertEquals(RESET, mode(teleport, 25, 0.25, new Vec3(15.99, 0, 0), 25, true));
        accept(RESET, PHYSICS_ACCEPTED, teleport, 25, 0.25, Vec3.ZERO, 25);
        assertEquals(RESET, mode(teleport, 26, 0.30, new Vec3(16, 0, 0), 26, true));
        accept(RESET, PHYSICS_ACCEPTED, teleport, 26, 0.30, Vec3.ZERO, 26);
        assertEquals(RESET, mode(teleport, 27, 0.29, Vec3.ZERO, 27, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, CAPACITY_EXCEEDED, SCRATCH_EXHAUSTED, NON_FINITE_STATE})
    void failuresRevokeOnlyThisSessionAndNeverImplicitlyRetry(int outcome) {
        var teleport = PlayerTeleportState.NONE;
        accept(RESET, PHYSICS_ACCEPTED | EXTRACT_OK, teleport, 10, 0, Vec3.ZERO, 10);
        var another = new PlayerPhysicsSession("other entity");
        another.accept(RESET, PHYSICS_ACCEPTED, 0, world, teleport, 10, Vec3.ZERO, 0, 10);
        long epoch = session.sampleEpoch();
        int flags = outcome == 0 ? 0 : PHYSICS_ACCEPTED | (outcome << NativeModelState.OUTCOME_SHIFT);
        accept(STEP, flags, teleport, 11, 0.05, Vec3.ZERO, 11);
        assertTrue(session.isDegraded());
        assertFalse(session.hasSample());
        assertNotEquals(epoch, session.sampleEpoch());
        assertEquals(ANIMATION_ONLY, mode(teleport, 12, 0.10, Vec3.ZERO, 12, false));
        assertEquals(ANIMATION_ONLY, mode(teleport, 30, 1.0, new Vec3(99, 0, 0), 30, true));
        assertTrue(another.hasSample());
        assertFalse(another.isDegraded());
    }

    @Test
    void allocationFailureSurvivesAdministrativeReinstallationButExplicitRetryAttemptsAgain() {
        var attempts = new AtomicInteger();
        var state = stateIdentity("11");
        var gate = new PlayerPhysicsSession.RecoveryGate(state, world);
        try (var failed = PlayerPhysicsSession.open("first installation", gate, () -> {
            attempts.incrementAndGet();
            return null;
        })) {
            assertEquals(1, attempts.get());
            assertTrue(failed.isDegraded());
            assertEquals(ANIMATION_ONLY, failed.select(0, 10, true));
        }
        assertTrue(gate.blocks(stateIdentity("11"), world));
        assertFalse(gate.blocks(stateIdentity("22"), world));
        assertFalse(gate.blocks(stateIdentity("11"), new Object()));
        try (var reinstalled = PlayerPhysicsSession.open("reinstalled", gate,
            () -> fail("administrative reinstallation retried world creation"))) {
            assertEquals(ANIMATION_ONLY, reinstalled.select(10, 100, true));
            reinstalled.retry(() -> {
                attempts.incrementAndGet();
                return null;
            });
            assertEquals(2, attempts.get());
            assertTrue(reinstalled.isDegraded());
        }
    }

    private static ModelRenderTarget.StateIdentity stateIdentity(String container) {
        var content =
            new ModelFileIdentity(ModelId.parse("hash:" + "12".repeat(32)), Hash256.parse(container.repeat(32)));
        var key = new RenderTargetKey("player", "main", new BakeProfile("test")).stateKey();
        return new ModelRenderTarget.StateIdentity(content, key, new Object());
    }

    private int mode(PlayerTeleportState teleport, int tick, double time, Vec3 position, float frame,
        boolean eligible) {
        session.invalidate(world, teleport, tick, position, time, frame);
        return session.select(time, frame, eligible);
    }

    private void accept(int mode, int flags, PlayerTeleportState teleport, int tick, double time, Vec3 position,
        float frame) {
        session.accept(mode, flags, 0, world, teleport, tick, position, time, frame);
    }
}
