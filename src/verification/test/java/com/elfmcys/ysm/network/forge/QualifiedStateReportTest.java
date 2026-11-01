package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.expression.aot.TestExpressionModules;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
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
            runtime.roamingState().orElseThrow().write("a", 7);
            var original = runtime.snapshot().orElseThrow();
            var planner = planner();
            var sent = new AtomicInteger();
            TestExpressionModules.replace(runtime, module, Map.of());
            LocalPlayerStateReporter.submitOpportunity(planner, 1_000_000_000L, 42,
                new PlayerStateReportPlanner.RoamingSample(11, original.payload().values()), original.installation(),
                report -> sent.incrementAndGet());
            assertEquals(0, sent.get());
            var fresh = runtime.snapshot().orElseThrow();
            LocalPlayerStateReporter.submitOpportunity(planner, 1_000_000_000L, 43,
                new PlayerStateReportPlanner.RoamingSample(12, fresh.payload().values()), fresh.installation(),
                report -> {
                    assertEquals(com.elfmcys.ysm.proto.network.StateWriteMode.FULL, report.mode());
                    assertEquals(12, report.roamingUnsafe().modelKey());
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
            runtime.roamingState().orElseThrow().write("a", 7);
            var snapshot = runtime.snapshot().orElseThrow();
            var sent = new AtomicInteger();
            var encoded = new AtomicReference<byte[]>();
            LocalPlayerStateReporter.submitOpportunity(planner(),
                1_000_000_000L, 42, new PlayerStateReportPlanner.RoamingSample(11, snapshot.payload().values()),
                snapshot.installation(), report -> {
                    try (var frame = FrameCodec.encode(1, report.toByteArray(), null)) {
                        encoded.set(frame.bytes());
                        sent.incrementAndGet();
                    }
                });
            TestExpressionModules.replace(runtime, module, Map.of());
            assertEquals(1, sent.get());
            var payload = java.util.Arrays.copyOfRange(encoded.get(), 1, encoded.get().length);
            var report = PlayerStateReportProto.parseFrom(payload);
            assertEquals(42, report.subject().entityId());
            assertEquals(11, report.roamingUnsafe().modelKey());
            assertEquals(7F, report.roamingUnsafe().variables().get(0).value_());
            assertFalse(snapshot.installation().commit(() -> fail("retired sample reused")));
        }
    }

    @Test
    void failedHostKeepsPlannerBaselineAndDoesNotConsumeProducerDelta() {
        try (var module = TestExpressionModules.create(Set.of("a")); var runtime = new EntityExpressionRuntime()) {
            TestExpressionModules.replace(runtime, module, Map.of());
            runtime.roamingState().orElseThrow().write("a", 7);
            var planner = planner();
            var sample = runtime.snapshot().orElseThrow();
            var values = new PlayerStateReportPlanner.RoamingSample(11, sample.payload().values());
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
        planner.acceptAuthority(TestExpressionModules.consumerId(), null);
        return planner;
    }

}
