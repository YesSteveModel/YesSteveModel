package com.elfmcys.ysm.network.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerIdTest {
    @Test
    void currentEntityRefsNeverExposePlayerId() {
        assertFalse(EntityRefEncoder.encode(12).hasPlayerId());
        assertFalse(EntityRefEncoder.encode(13).hasPlayerId());
    }
}
