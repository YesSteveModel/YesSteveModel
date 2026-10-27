package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.util.EnumSet;
import java.util.Set;

public record PlayerStateReportPolicy(Set<PlayerStateSection> requestedSections, int minDeltaIntervalMillis,
    int fullSnapshotIntervalMillis) {
    public PlayerStateReportPolicy {
        requestedSections = Set.copyOf(requestedSections);
        if (minDeltaIntervalMillis < 50 || minDeltaIntervalMillis > 1000) {
            throw new IllegalArgumentException("Delta interval must be between 50 and 1000 ms");
        }
        if (fullSnapshotIntervalMillis != 0
            && (fullSnapshotIntervalMillis < 5000 || fullSnapshotIntervalMillis > 300_000)) {
            throw new IllegalArgumentException(
                "Full snapshot interval must be zero or between 5 seconds and 5 minutes");
        }
    }

    public static PlayerStateReportPolicy gameServer(boolean syncRoaming) {
        var sections = EnumSet.of(PlayerStateSection.ANIMATION);
        if (syncRoaming) {
            sections.add(PlayerStateSection.ROAMING);
        }
        return new PlayerStateReportPolicy(sections, 50, 30_000);
    }

    public boolean acceptsProjection(PlayerStateReportProto report) {
        if (report.hasGameplay() && !requestedSections.contains(PlayerStateSection.GAMEPLAY)
            || report.hasEffects() && !requestedSections.contains(PlayerStateSection.EFFECTS)
            || report.hasAnimation() && !requestedSections.contains(PlayerStateSection.ANIMATION)
            || report.hasRoaming() && !requestedSections.contains(PlayerStateSection.ROAMING)) {
            return false;
        }
        return report.mode() == StateWriteMode.FULL || report.hasGameplay() || report.hasEffects()
            || report.hasAnimation() || report.hasRoaming();
    }
}
