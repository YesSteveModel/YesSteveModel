package com.elfmcys.ysm.network.protocol;

import org.junit.jupiter.api.Test;
import dev.semaweave.RoamingPayload;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStateReportPlannerTest {
    private static RoamingPayload payload(float value) {
        return new RoamingPayload(new short[] {0}, new float[] {value});
    }

    private static PlayerStateReportPlanner planner() {
        var p = new PlayerStateReportPlanner();
        p.beginSession(PlayerStateReportPolicy.gameServer(true));
        p.selectionCommitted(1, new RoamingDictionary(new String[] {"a", "missing"}), payload(1));
        return p;
    }

    @Test
    void noInitialOrPeriodicFullAndOnlyChangedValuesAreSent() {
        var p = planner();
        assertTrue(p.observe(1, 42, payload(1)).isEmpty());
        var delta = p.observe(2, 42, payload(2)).orElseThrow();
        assertEquals(1, delta.intentId());
        assertArrayEquals(new int[] {0}, delta.state().roamingUnsafe().ids().toIntArray());
        assertEquals(2, delta.state().roamingUnsafe().values_().getFloat(0));
        assertTrue(p.observe(600_000_000_000L, 42, payload(2)).isEmpty());
    }

    @Test
    void opportunitiesAreConsumedBeforeFailedSendAndRateLimited() {
        var p = planner();
        assertTrue(p.observe(1, 42, payload(2)).isPresent());
        assertTrue(p.observe(2, 42, payload(3)).isEmpty());
        assertTrue(p.observe(50_000_002, 42, payload(2)).isEmpty());
        assertTrue(p.observe(50_000_003, 42, payload(3)).isPresent());
    }

    @Test
    void zeroBaselineSignedZeroAndNewSelectionRemainDistinct() {
        var p = planner();
        p.selectionCommitted(2, new RoamingDictionary(new String[] {"a"}),
            new RoamingPayload(new short[0], new float[0]));
        assertTrue(p.observe(1, 42, payload(0F)).isEmpty());
        var d = p.observe(50_000_002, 42, payload(-0F)).orElseThrow();
        assertEquals(Float.floatToRawIntBits(-0F),
            Float.floatToRawIntBits(d.state().roamingUnsafe().values_().getFloat(0)));
        p.selectionCommitted(3, new RoamingDictionary(new String[] {"a"}), payload(9));
        assertTrue(p.observe(50_000_003, 42, payload(9)).isEmpty());
    }

    @Test
    void noSampleDoesNotFabricateRoamingAndAnimationIsIndependent() {
        var p = planner();
        p.setAnimation("missing-local-animation");
        var state = p.observe(1, 42, null).orElseThrow().state();
        assertTrue(state.hasAnimation());
        assertFalse(state.hasRoaming());
        p.endSession();
        assertTrue(p.observe(2, 42, payload(1)).isEmpty());
    }
}
