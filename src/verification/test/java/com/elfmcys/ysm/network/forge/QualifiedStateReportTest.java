package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy;
import com.elfmcys.ysm.proto.network.SelectionStateSyncProto;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class QualifiedStateReportTest {
    @Test
    void retiredSampleCannotAdvancePlannerOrEncodeAndIdenticalModuleReplacementIsFresh() {
        try (var module = TestExpressionModules.create(Set.of("a")); var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            var state = runtime.roamingState().orElseThrow();
            state.write((short)state.fieldNames().indexOf("a"), 7);
            var original = runtime.snapshot().orElseThrow();
            var planner = planner();
            var sent = new AtomicInteger();
            TestExpressionModules.replace(runtime, module, Map.of());
            LocalPlayerStateReporter.submitOpportunity(planner, 1_000_000_000L, 42, original.payload(),
                original.installation(), report -> sent.incrementAndGet());
            assertEquals(0, sent.get());
            state = runtime.roamingState().orElseThrow();
            state.write((short)state.fieldNames().indexOf("a"), 8);
            var fresh = runtime.snapshot().orElseThrow();
            LocalPlayerStateReporter.submitOpportunity(planner, 1_000_000_000L, 43, fresh.payload(),
                fresh.installation(), report -> {
                    assertEquals(1, report.intentId());
                    try (var frame = FrameCodec.encode(1, report.toByteArray(), null)) {
                        assertTrue(frame.size() > 1);
                        sent.incrementAndGet();
                    }
                });
            assertEquals(1, sent.get());
        }
    }

    @Test
    void mainThreadQualifiedEncodingPrecedesReplacementAndRejectsTheOldSample() throws Exception {
        try (var module = TestExpressionModules.create(Set.of("a")); var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            var state = runtime.roamingState().orElseThrow();
            state.write((short)state.fieldNames().indexOf("a"), 7);
            var snapshot = runtime.snapshot().orElseThrow();
            var sent = new AtomicInteger();
            var encoded = new AtomicReference<byte[]>();
            LocalPlayerStateReporter.submitOpportunity(planner(), 1_000_000_000L, 42, snapshot.payload(),
                snapshot.installation(), report -> {
                    try (var frame = FrameCodec.encode(1, report.toByteArray(), null)) {
                        encoded.set(frame.bytes());
                        sent.incrementAndGet();
                    }
                });
            TestExpressionModules.replace(runtime, module, Map.of());
            assertEquals(1, sent.get());
            var payload = java.util.Arrays.copyOfRange(encoded.get(), 1, encoded.get().length);
            var report = SelectionStateSyncProto.parseFrom(payload);
            assertEquals(42, report.entityId());
            assertEquals(1, report.intentId());
            assertEquals(7F, report.state().roamingUnsafe().values_().getFloat(0));
            assertFalse(snapshot.installation().commit(() -> fail("retired sample reused")));
        }
    }

    @Test
    void failedHostKeepsPlannerBaselineAndDoesNotConsumeProducerDelta() {
        try (var module = TestExpressionModules.create(Set.of("a")); var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            var state = runtime.roamingState().orElseThrow();
            state.write((short)state.fieldNames().indexOf("a"), 7);
            var planner = planner();
            var sample = runtime.snapshot().orElseThrow();
            var values = sample.payload();
            var attempts = new AtomicInteger();
            LocalPlayerStateReporter.submitOpportunity(planner, 1_000_000_000L, 42, values, sample.installation(),
                report -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("host");
                });
            LocalPlayerStateReporter.submitOpportunity(planner, 1_100_000_000L, 42, values, sample.installation(),
                report -> attempts.incrementAndGet());
            assertEquals(1, attempts.get());
            assertTrue(runtime.hasRoamingDelta());
        }
    }

    private static PlayerStateReportPlanner planner() {
        var planner = new PlayerStateReportPlanner();
        planner.beginSession(PlayerStateReportPolicy.gameServer(true));
        planner.selectionCommitted(1, new com.elfmcys.ysm.network.protocol.RoamingDictionary(new String[] {"a"}),
            new dev.semaweave.RoamingPayload(new short[0], new float[0]));
        return planner;
    }
}
