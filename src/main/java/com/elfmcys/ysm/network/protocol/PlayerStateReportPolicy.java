package com.elfmcys.ysm.network.protocol;

import java.util.EnumSet;
import java.util.Set;

public record PlayerStateReportPolicy(Set<PlayerStateSection> requestedSections, int minDeltaIntervalMillis) {
    public PlayerStateReportPolicy {
        requestedSections = Set.copyOf(requestedSections);
        if (minDeltaIntervalMillis < 50 || minDeltaIntervalMillis > 1000)
            throw new IllegalArgumentException("Delta interval must be between 50 and 1000 ms");
    }

    public static PlayerStateReportPolicy gameServer(boolean syncRoaming) {
        var sections = EnumSet.of(PlayerStateSection.ANIMATION);
        if (syncRoaming)
            sections.add(PlayerStateSection.ROAMING);
        return new PlayerStateReportPolicy(sections, 50);
    }
}
