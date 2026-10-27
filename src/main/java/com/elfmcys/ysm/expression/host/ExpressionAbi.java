package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.compatibility.AbiVersion;

final class ExpressionAbi {
    static final int ANIMATION_NUMBER = descriptorNumber(AbiVersion.CACHE_ANIMATION);

    private ExpressionAbi() {}

    private static int descriptorNumber(String version) {
        var parts = version.split("[-.]", 4);
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        if (major > 2000 || minor > 999 || patch > 999) {
            throw new IllegalArgumentException("ABI numeric component exceeds descriptor range: " + version);
        }
        return major * 1_000_000 + minor * 1_000 + patch;
    }
}
