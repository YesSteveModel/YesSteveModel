package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.network.protocol.NegotiatedSessionPolicy;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPolicy;

final class PlayerStateSessionPolicy {
    private PlayerStateSessionPolicy() {}

    static NegotiatedSessionPolicy create(boolean syncRoaming) {
        return new NegotiatedSessionPolicy(PlayerStateReportPolicy.gameServer(syncRoaming));
    }
}
