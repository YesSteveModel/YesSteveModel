package com.elfmcys.ysm.natives;

import org.junit.jupiter.api.Test;

import static com.elfmcys.ysm.natives.ThreadSchedulingResult.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreadSchedulingResultTest {
    @Test
    void decodesEveryStepAndUnsignedWindowsErrorsWithoutRetainingTheArray() {
        var encoded = new long[] {3, 1, 5, -2, 0, 5, 0, 0, 3, 3, 0, 0, 0, 4, 3, 1, 0xffff_ffffL, 5, 3, 1, 5};
        var report = decode(encoded);
        assertEquals(Outcome.PARTIAL, report.outcome());
        assertEquals(Platform.WINDOWS, report.platform());
        assertEquals(-2, report.observations().value(Attribute.CPU_PRIORITY).orElseThrow());
        assertTrue(report.observations().value(Attribute.SCHEDULER_POLICY).isEmpty());
        assertEquals(0xffff_ffffL, report.steps().get(1).nativeError());
        assertEquals(Operation.END_BACKGROUND, report.steps().get(2).operation());
        encoded[3] = 42;
        assertEquals(-2, report.observations().cpuPriority());
        assertThrows(UnsupportedOperationException.class, () -> report.steps().clear());
    }

    @Test
    void missingAndInvalidReportsRemainDistinctFromOsFailures() {
        assertEquals(Outcome.UNAVAILABLE, decode(null).outcome());
        assertEquals(Outcome.INVALID_REPORT, decode(new long[0]).outcome());
        var valid = new long[] {4, 2, 0, 0, 0, 0, 0, 0, 1, 1, 3, 2, 13};
        assertEquals(Outcome.FAILED, decode(valid).outcome());
        for (var mutation : new long[][] {{0, 6}, {1, 0}, {1, 5}, {2, 32}, {2, -1}, {3, 1}, {8, 9}, {8, -1}, {8, 0},
            {9, 0}, {9, 9}, {10, 4}, {11, 4}, {12, -1}}) {
            var invalid = valid.clone();
            invalid[(int)mutation[0]] = mutation[1];
            assertEquals(Outcome.INVALID_REPORT, decode(invalid).outcome(), java.util.Arrays.toString(mutation));
        }
    }

    @Test
    void successfulAndSkippedStepsRejectNativeErrors() {
        for (var result : new long[] {0, 1}) {
            for (var domain : new long[] {1, 2, 3}) {
                var report = new long[] {1, 1, 0, 0, 0, 0, 0, 0, 1, 2, result, domain, 5};
                assertEquals(Outcome.INVALID_REPORT, decode(report).outcome());
            }
        }
    }
}
