package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.natives.Blake3;
import com.elfmcys.ysm.network.protocol.PlayerId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

final class PlayerIdDerivation {
    private static final byte[] MASK = "ysm.player.id.v0".getBytes(StandardCharsets.US_ASCII);

    private PlayerIdDerivation() {}

    static PlayerId derive(UUID uuid) {
        try (var input = ArrayBuffer.move(input(uuid))) {
            return new PlayerId(Arrays.copyOf(Blake3.computeHash(input), PlayerId.SIZE));
        }
    }

    static byte[] input(UUID uuid) {
        var bytes = ByteBuffer.allocate(PlayerId.SIZE).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        for (var index = 0; index < bytes.length; index++) {
            bytes[index] ^= MASK[index];
        }
        return bytes;
    }
}
