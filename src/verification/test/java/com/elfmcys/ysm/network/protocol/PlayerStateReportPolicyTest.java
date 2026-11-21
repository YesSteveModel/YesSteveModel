package com.elfmcys.ysm.network.protocol;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStateReportPolicyTest {
    @Test
    void serverRequestsOnlyProducerOwnedSections() {
        assertEquals(Set.of(PlayerStateSection.ANIMATION),
            PlayerStateReportPolicy.gameServer(false).requestedSections());
        assertEquals(Set.of(PlayerStateSection.ANIMATION, PlayerStateSection.ROAMING),
            PlayerStateReportPolicy.gameServer(true).requestedSections());
    }

    @Test
    void boundedOpportunityInterval() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerStateReportPolicy(Set.of(), 49));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStateReportPolicy(Set.of(), 1001));
    }
}
