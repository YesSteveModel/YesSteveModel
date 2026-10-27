package com.elfmcys.ysm.client.gui.overlay;

import com.elfmcys.ysm.expression.inventory.ModelProgramInventory;
import com.elfmcys.ysm.expression.inventory.ProgramRole;
import dev.semaweave.ProgramId;
import dev.semaweave.ProgramKey;
import dev.semaweave.compilation.ProgramReplacement;
import dev.semaweave.compilation.ReplacementResult;
import dev.semaweave.compilation.SemanticFailureReason;
import dev.semaweave.program.ReadOnlyBytes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugAnimationScreenTest {
    private static final ProgramKey KEY = new ProgramKey(ReadOnlyBytes.copyOf(new byte[] {1}));
    private static final ProgramReplacement REPLACEMENT = new ProgramReplacement(new ProgramId(0), KEY,
        SemanticFailureReason.TYPE_MISMATCH, "root/1", "incompatible assignment", ReplacementResult.F32_ZERO);

    @Test
    void currentScopeShowsEachReplacementOnceAndRetriesFailedDelivery() {
        var tracker = new DebugAnimationScreen.ReplacementNoticeTracker();
        var resource = new Object();
        var attempts = new AtomicInteger();
        var delivered = new ArrayList<DebugAnimationScreen.ReplacementNotice>();
        var location = location("animation/main/idle/blend-weight");

        tracker.observe(resource, List.of(REPLACEMENT), Map.of(KEY, location), notice -> {
            if (attempts.getAndIncrement() == 0) {
                return false;
            }
            delivered.add(notice);
            return true;
        });
        assertEquals(0, delivered.size());

        tracker.observe(resource, List.of(REPLACEMENT), Map.of(KEY, location), notice -> {
            delivered.add(notice);
            return true;
        });
        tracker.observe(resource, List.of(REPLACEMENT), Map.of(KEY, location), notice -> {
            delivered.add(notice);
            return true;
        });

        assertEquals(List.of(new DebugAnimationScreen.ReplacementNotice(location, REPLACEMENT)), delivered);
    }

    @Test
    void targetAndResourceChangesUseOnlyTheCurrentLocationMap() {
        var tracker = new DebugAnimationScreen.ReplacementNoticeTracker();
        var first = location("animation/main/idle/blend-weight");
        var reused = location("animation/alternate/idle/blend-weight");
        var delivered = new ArrayList<DebugAnimationScreen.ReplacementNotice>();

        tracker.observe(new Object(), List.of(REPLACEMENT), Map.of(KEY, first), notice -> {
            delivered.add(notice);
            return true;
        });
        tracker.observe(new Object(), List.of(), Map.of(), notice -> {
            delivered.add(notice);
            return true;
        });
        tracker.observe(new Object(), List.of(REPLACEMENT), Map.of(KEY, reused), notice -> {
            delivered.add(notice);
            return true;
        });
        tracker.reset();
        tracker.observe(new Object(), List.of(REPLACEMENT), Map.of(KEY, first), notice -> {
            delivered.add(notice);
            return true;
        });

        assertEquals(List.of(first, reused, first),
            delivered.stream().map(DebugAnimationScreen.ReplacementNotice::location).toList());
    }

    private static ModelProgramInventory.SemanticLocation location(String value) {
        return new ModelProgramInventory.SemanticLocation("player", value, ProgramRole.ANIMATION_SAMPLE, "");
    }
}
