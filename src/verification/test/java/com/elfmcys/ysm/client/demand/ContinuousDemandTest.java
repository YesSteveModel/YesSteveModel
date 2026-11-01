package com.elfmcys.ysm.client.demand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuousDemandTest {
    static Stream<Arguments> pageAdmissionCases() {
        return Stream.of(-1L, 0L, 300L, 301L, 699L, 700L, 701L).flatMap(previous ->
            Stream.of(0L, 299L, 300L, 699L, 700L).map(current -> Arguments.of(previous, current)));
    }

    @ParameterizedTest
    @MethodSource("pageAdmissionCases")
    void pageAdmissionUsesOnePreviousPageThresholdAndInclusiveCurrentThresholds(long previous, long current) {
        var demand = new ContinuousDemand<String>();
        if (previous >= 0)
            demand.observe("previous", 0);
        var start = Math.max(0, previous);
        demand.observe("current", start);
        var immediate = previous < 0 || previous > 700;
        assertEquals(immediate || current >= 300, demand.pageEffectEligible(true, start + current, 300));
        assertEquals(immediate || current >= 700, demand.pageEffectEligible(true, start + current, 700));
        assertTrue(demand.pageEffectEligible(false, start + current, 700));
    }

    @Test
    void hoverIncludesThreeHundredMilliseconds() {
        var demand = new ContinuousDemand<String>();
        demand.observe("model", 1_000);

        assertFalse(demand.elapsedAtLeast(1_299, ContinuousDemand.HOVER_DWELL_MILLIS));
        assertTrue(demand.elapsedAtLeast(1_300, ContinuousDemand.HOVER_DWELL_MILLIS));
        assertTrue(demand.elapsedAtLeast(1_301, ContinuousDemand.HOVER_DWELL_MILLIS));
    }

    @ParameterizedTest
    @ValueSource(longs = {ContinuousDemand.LOCAL_SWITCH_DWELL_MILLIS, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS})
    void firstAndCompleteCurrentBypassSwitchDwell(long dwell) {
        var first = new ContinuousDemand<String>();
        first.observe("first", 0);
        assertTrue(first.effectEligible(true, 0, dwell));

        var complete = new ContinuousDemand<String>();
        complete.observe("A", 0);
        complete.observe("B", 100);
        assertTrue(complete.effectEligible(false, 100, dwell));
    }

    @ParameterizedTest
    @CsvSource({"300,false,false", "301,true,false", "700,true,false", "701,true,true"})
    void localAndNetworkEligibilityUseTheSameStartAndIndependentPreviousDurations(long previous,
        boolean localImmediate, boolean networkImmediate) {
        var demand = new ContinuousDemand<String>();
        demand.observe("A", 0);
        demand.observe("B", previous);

        assertEquals(localImmediate,
            demand.effectEligible(true, previous, ContinuousDemand.LOCAL_SWITCH_DWELL_MILLIS));
        assertEquals(localImmediate,
            demand.effectEligible(true, previous + 300, ContinuousDemand.LOCAL_SWITCH_DWELL_MILLIS));
        assertTrue(demand.effectEligible(true, previous + 301, ContinuousDemand.LOCAL_SWITCH_DWELL_MILLIS));
        assertEquals(networkImmediate,
            demand.effectEligible(true, previous, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS));
        assertEquals(networkImmediate,
            demand.effectEligible(true, previous + 700, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS));
        assertTrue(demand.effectEligible(true, previous + 701, ContinuousDemand.NETWORK_SWITCH_DWELL_MILLIS));
    }

    @Test
    void repeatedObservationDoesNotResetTheRealStart() {
        var demand = new ContinuousDemand<String>();
        demand.observe("model", 0);
        assertFalse(demand.observe("model", 250));
        assertTrue(demand.elapsedStrictlyExceeds(301, ContinuousDemand.HOVER_DWELL_MILLIS));
    }
}
