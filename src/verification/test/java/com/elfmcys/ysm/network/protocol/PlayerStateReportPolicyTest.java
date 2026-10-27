package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.proto.network.AnimationStateProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateReportPolicyTest {
    private final PlayerStateReportPolicy policy =
        new PlayerStateReportPolicy(Set.of(PlayerStateSection.ANIMATION), 50, 30_000);

    @Test
    void acceptsOnlyNegotiatedSections() {
        var animation = report(StateWriteMode.DELTA)
            .setAnimation(AnimationStateProto.newBuilder().setStopped(true).build()).build();
        var roaming =
            report(StateWriteMode.DELTA).setRoaming(RoamingStateProto.newBuilder().setModelKey(1).build()).build();

        assertTrue(policy.acceptsProjection(animation));
        assertFalse(policy.acceptsProjection(roaming));
    }

    @Test
    void fullMayOmitARequestedSectionButDeltaMayNotBeEmpty() {
        assertTrue(policy.acceptsProjection(report(StateWriteMode.FULL).build()));
        assertFalse(policy.acceptsProjection(report(StateWriteMode.DELTA).build()));
    }

    private static PlayerStateReportProto.Builder report(StateWriteMode mode) {
        return PlayerStateReportProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(1).build())
            .setMode(mode);
    }
}
