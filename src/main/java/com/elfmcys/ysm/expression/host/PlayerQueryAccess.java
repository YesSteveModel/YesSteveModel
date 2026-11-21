package com.elfmcys.ysm.expression.host;

/** Per-player immutable network facts, independent of body installation and sampling. */
public interface PlayerQueryAccess {
    PlayerQueryProjection ysm$playerQueryProjection();

    void ysm$playerQueryProjection(PlayerQueryProjection projection);
}
