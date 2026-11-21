package com.elfmcys.ysm.network.protocol;

public final class ProtocolLimits {
    public static final int MAX_PLAYER_STATE_BYTES = 128 * 1024;
    public static final int MAX_ENTITY_ACTION_BYTES = 8 * 1024;
    public static final int MAX_SWING_HAND_BYTES = 256;
    public static final int MAX_MINECRAFT_STATE_BYTES = 32 * 1024;
    public static final int MAX_ROAMING_VARIABLES = 64;
    public static final int MAX_ROAMING_VARIABLE_NAME_BYTES = 32;

    private ProtocolLimits() {}
}
