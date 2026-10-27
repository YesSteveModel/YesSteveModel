package com.elfmcys.ysm.natives.legacy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacySoundProjectionTest {
    @Test
    void requiresProjectedSourceFactsToMatchThePreservedStream() throws Exception {
        var encoded = ByteBuffer.wrap(Files.readAllBytes(builtinOpus()));
        var valid = record(NativeLegacyProtocol.PayloadEncoding.OGG_OPUS, 1, 48_000, 32_127, encoded.remaining());

        assertDoesNotThrow(() -> LegacyModelImporter.validateSound(valid, encoded));
        assertThrows(NativeLegacyProtocol.ProtocolException.class,
            () -> LegacyModelImporter.validateSound(
                record(NativeLegacyProtocol.PayloadEncoding.OGG_OPUS, 1, 48_000, 32_128, encoded.remaining()),
                encoded));
        assertThrows(NativeLegacyProtocol.ProtocolException.class,
            () -> LegacyModelImporter.validateSound(
                record(NativeLegacyProtocol.PayloadEncoding.OGG_OPUS, 2, 48_000, 32_127, encoded.remaining()),
                encoded));
        assertThrows(NativeLegacyProtocol.ProtocolException.class,
            () -> LegacyModelImporter.validateSound(
                record(NativeLegacyProtocol.PayloadEncoding.OGG_VORBIS, 1, 48_000, 32_127, encoded.remaining()),
                encoded));
    }

    private static NativeLegacyProtocol.PayloadRecord record(NativeLegacyProtocol.PayloadEncoding encoding,
        int channels, int sampleRate, long frames, int payloadSize) {
        return new NativeLegacyProtocol.PayloadRecord(NativeLegacyProtocol.PayloadKind.SOUND_STREAM, encoding, 1,
            "tone", channels, sampleRate, frames, payloadSize);
    }

    private static Path builtinOpus() {
        return Path.of("src/main/resources/assets/ysm/builtin/wine_fox/14_momo/" + "sounds/fly_on.ogg").toAbsolutePath()
            .normalize();
    }
}
