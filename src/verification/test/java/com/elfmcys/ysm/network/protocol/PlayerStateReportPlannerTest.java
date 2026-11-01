package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateReportPlannerTest {
    private static final long DELTA_INTERVAL_NANOS = 50_000_000L;
    private static final long FULL_INTERVAL_NANOS = 30_000_000_000L;

    @Test
    void failedHostAttemptDoesNotRetainOrReplayTheObservedDifference() {
        var planner = planner(false);
        var attempts = new AtomicInteger();
        var first = planner.observe(1, 7, absent()).orElseThrow();
        Consumer<PlayerStateReportProto> failingHost = ignored -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("host send failed");
        };

        assertThrows(IllegalStateException.class, () -> failingHost.accept(first));
        assertEquals(StateWriteMode.FULL, first.mode());
        assertFalse(first.hasGameplay());
        assertFalse(first.hasEffects());
        assertFalse(first.subject().hasPlayerId());

        assertTrue(planner.observe(1 + DELTA_INTERVAL_NANOS, 7, absent()).isEmpty());
        planner.setAnimation("wave");
        var independent = planner.observe(1 + 2 * DELTA_INTERVAL_NANOS, 7, absent()).orElseThrow();
        assertEquals(StateWriteMode.DELTA, independent.mode());
        assertEquals("wave", independent.animationUnsafe().animationId());
        assertEquals(1, attempts.get());
    }

    @Test
    void lostInitialFullIsNotRetriedUntilTheIndependentPeriodicFull() {
        var planner = planner(false);
        assertEquals(StateWriteMode.FULL, planner.observe(1, 7, absent()).orElseThrow().mode());

        planner.setAnimation("wave");
        assertEquals(StateWriteMode.DELTA, planner.observe(1 + DELTA_INTERVAL_NANOS, 7, absent()).orElseThrow().mode());
        assertTrue(planner.observe(1 + 2 * DELTA_INTERVAL_NANOS, 7, absent()).isEmpty());

        var periodic = planner.observe(1 + FULL_INTERVAL_NANOS, 7, absent()).orElseThrow();
        assertEquals(StateWriteMode.FULL, periodic.mode());
        assertEquals("wave", periodic.animationUnsafe().animationId());
    }

    @Test
    void fullWithoutARoamingSectionStillBindsTheAuthorityAndFormsTheFirstFull() {
        var planner = new PlayerStateReportPlanner();
        planner.beginSession(PlayerStateReportPolicy.gameServer(true));
        // A self FULL that omitted roaming resets that scope to empty instead of denying the
        // model authority, so the first opportunity must form and animation must keep flowing.
        assertTrue(planner.acceptAuthority(hash(1), null));

        var full = planner.observe(1, 3, absent()).orElseThrow();
        assertEquals(StateWriteMode.FULL, full.mode());
        assertFalse(full.hasRoaming());

        planner.setAnimation("wave");
        var animation = planner.observe(1 + DELTA_INTERVAL_NANOS, 3, absent()).orElseThrow();
        assertEquals(StateWriteMode.DELTA, animation.mode());
        assertEquals("wave", animation.animationUnsafe().animationId());
    }

    @Test
    void theRoamingScopeIsAdoptedOnceTheAppliedModelNamesItsNamespace() {
        var planner = new PlayerStateReportPlanner();
        planner.beginSession(PlayerStateReportPolicy.gameServer(true));
        assertTrue(planner.acceptAuthority(hash(1), null));
        assertFalse(planner.observe(1, 3, absent()).orElseThrow().hasRoaming());

        var adopted = planner.observe(1 + DELTA_INTERVAL_NANOS, 3, sample(11, Map.of("speed", 1F))).orElseThrow();
        assertEquals(StateWriteMode.DELTA, adopted.mode());
        assertEquals(11, adopted.roamingUnsafe().modelKey());
        assertEquals(1F, adopted.roamingUnsafe().variables().get(0).value_());

        assertTrue(planner.observe(1 + 2 * DELTA_INTERVAL_NANOS, 3, sample(11, Map.of("speed", 1F))).isEmpty());
        var changed = planner.observe(1 + 3 * DELTA_INTERVAL_NANOS, 3, sample(11, Map.of("speed", 2F))).orElseThrow();
        assertEquals(2F, changed.roamingUnsafe().variables().get(0).value_());
    }

    @Test
    void roamingRequiresAuthorityAndUsesContentDrivenDelta() {
        var planner = new PlayerStateReportPlanner();
        planner.beginSession(PlayerStateReportPolicy.gameServer(true));
        assertTrue(planner.observe(1, 3, sample(11, Map.of("speed", 1F))).isEmpty());
        assertTrue(planner.acceptAuthority(hash(1), 11));

        var full = planner.observe(1, 3, sample(11, Map.of("speed", 1F))).orElseThrow();
        assertTrue(full.hasRoaming());
        assertEquals(11, full.roamingUnsafe().modelKey());
        assertTrue(planner.observe(1 + DELTA_INTERVAL_NANOS, 3, sample(11, Map.of("speed", 1F))).isEmpty());

        var delta = planner.observe(1 + 2 * DELTA_INTERVAL_NANOS, 3, sample(11, Map.of("speed", 2F))).orElseThrow();
        assertEquals(StateWriteMode.DELTA, delta.mode());
        assertEquals(2F, delta.roamingUnsafe().variables().get(0).value_());
    }

    @Test
    void unavailableRoamingPreservesTheFirstFullAndItsAnimationObservation() {
        var planner = planner(true);
        planner.setAnimation("wave");

        assertTrue(planner.observe(1, 7, PlayerStateReportPlanner.RoamingSample.unavailable(1)).isEmpty());

        var restored = planner.observe(2, 7, sample(1, Map.of("a", 1F))).orElseThrow();
        assertEquals(StateWriteMode.FULL, restored.mode());
        assertEquals("wave", restored.animationUnsafe().animationId());
        assertEquals(1F, restored.roamingUnsafe().variables().get(0).value_());
    }

    @Test
    void unavailableRoamingDoesNotAdvanceDeltaObservationsOrRateLimit() {
        var planner = planner(true);
        planner.observe(1, 7, sample(1, Map.of("a", 1F))).orElseThrow();
        planner.setAnimation("wave");

        assertTrue(planner.observe(1 + DELTA_INTERVAL_NANOS, 7,
            PlayerStateReportPlanner.RoamingSample.unavailable(1)).isEmpty());

        var changed = planner.observe(2 + DELTA_INTERVAL_NANOS, 7, sample(1, Map.of("a", 2F))).orElseThrow();
        assertEquals(StateWriteMode.DELTA, changed.mode());
        assertEquals("wave", changed.animationUnsafe().animationId());
        assertEquals(2F, changed.roamingUnsafe().variables().get(0).value_());
    }

    @Test
    void knownEmptyRoamingCanFormTheFirstFull() {
        var full = planner(true).observe(1, 7, sample(1, Map.of())).orElseThrow();

        assertEquals(StateWriteMode.FULL, full.mode());
        assertTrue(full.hasRoaming());
        assertEquals(1, full.roamingUnsafe().modelKey());
        assertTrue(full.roamingUnsafe().variables().isEmpty());
    }

    @Test
    void unavailableRoamingDoesNotBlockAnAnimationOnlyPolicy() {
        var planner = planner(false);
        planner.setAnimation("wave");

        var full = planner.observe(1, 7, PlayerStateReportPlanner.RoamingSample.unavailable(1)).orElseThrow();

        assertEquals(StateWriteMode.FULL, full.mode());
        assertEquals("wave", full.animationUnsafe().animationId());
        assertFalse(full.hasRoaming());
    }

    private static PlayerStateReportPlanner planner(boolean syncRoaming) {
        var planner = new PlayerStateReportPlanner();
        planner.beginSession(PlayerStateReportPolicy.gameServer(syncRoaming));
        assertTrue(planner.acceptAuthority(hash(1), syncRoaming ? 1 : null));
        return planner;
    }

    private static PlayerStateReportPlanner.RoamingSample absent() {
        return PlayerStateReportPlanner.RoamingSample.absent();
    }

    private static PlayerStateReportPlanner.RoamingSample sample(int modelKey, Map<String, Float> values) {
        return new PlayerStateReportPlanner.RoamingSample(modelKey, values);
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)seed;
        return new Hash256(bytes);
    }
}
