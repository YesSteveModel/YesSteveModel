package com.elfmcys.ysm.format.media;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedAudioProbeTest {
    @Test
    void inspectsEveryContractFixtureWithExactMetadata() throws Exception {
        var root = fixtureRoot();
        var manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        for (var element : manifest.getAsJsonArray("cases")) {
            var fixture = element.getAsJsonObject();
            var encoded = ByteBuffer.wrap(Files.readAllBytes(root.resolve(fixture.get("encoded").getAsString())));

            assertEquals(fixture.get("encoded_sha256").getAsString(),
                java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded.array())),
                fixture.get("id").getAsString());
            var inspection = SupportedAudioProbe.inspect(encoded);
            assertTrue(inspection.playable(), fixture.get("id").getAsString() + ": " + inspection.diagnostic());
            var media = inspection.media();
            assertEquals("opus".equals(fixture.get("codec").getAsString()) ? SupportedAudioProbe.Encoding.OGG_OPUS
                : SupportedAudioProbe.Encoding.OGG_VORBIS, media.encoding());
            assertEquals(fixture.get("source_channels").getAsInt(), media.channels());
            assertEquals(fixture.get("sample_rate").getAsLong(), media.sampleRate());
            assertEquals(fixture.get("frames").getAsLong(), media.frames());
            assertEquals(fixture.get("pre_skip").getAsInt(), media.preSkip());
            assertEquals(fixture.get("short_eligible").getAsBoolean(), media.shortEligible());
        }
    }

    @Test
    void rejectsIntegrityTimelineAndDescriptorConflicts() throws IOException {
        var root = fixtureRoot();
        byte[] valid = Files.readAllBytes(root.resolve("opus-origin.ogg"));
        var accepted = SupportedAudioProbe.inspect(ByteBuffer.wrap(valid));
        assertTrue(accepted.playable(), accepted.diagnostic());
        var media = accepted.media();
        assertEquals(60_001, media.frames());

        byte[] badCrc = valid.clone();
        badCrc[badCrc.length - 1] ^= 1;
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED,
            SupportedAudioProbe.inspect(ByteBuffer.wrap(badCrc)).disposition());

        byte[] badIdentificationPageCrc = valid.clone();
        badIdentificationPageCrc[22] ^= 1;
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED,
            SupportedAudioProbe.inspect(ByteBuffer.wrap(badIdentificationPageCrc)).disposition());

        byte[] trailing = new byte[valid.length + 1];
        System.arraycopy(valid, 0, trailing, 0, valid.length);
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED,
            SupportedAudioProbe.inspect(ByteBuffer.wrap(trailing)).disposition());

        byte[] truncated = new byte[valid.length - 1];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED,
            SupportedAudioProbe.inspect(ByteBuffer.wrap(truncated)).disposition());

        byte[] badMapping = valid.clone();
        int firstPage = 0;
        int firstBody = firstPage + 27 + Byte.toUnsignedInt(badMapping[firstPage + 26]);
        badMapping[firstBody + 18] = 1;
        rewritePageChecksum(badMapping, firstPage);
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED,
            SupportedAudioProbe.inspect(ByteBuffer.wrap(badMapping)).disposition());

        byte[] badSerial = valid.clone();
        int secondPage = pageSize(badSerial, 0);
        badSerial[secondPage + 14] ^= 1;
        rewritePageChecksum(badSerial, secondPage);
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED,
            SupportedAudioProbe.inspect(ByteBuffer.wrap(badSerial)).disposition());

        var mismatched = SupportedAudioProbe.admit(ByteBuffer.wrap(valid), media.encoding(), media.channels(),
            media.sampleRate(), media.frames() + 1);
        assertFalse(mismatched.playable());
        assertEquals(SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED, mismatched.disposition());
    }

    private static Path fixtureRoot() {
        return com.elfmcys.ysm.testutil.AudioFixtures.root();
    }

    private static int pageSize(byte[] bytes, int offset) {
        int segments = Byte.toUnsignedInt(bytes[offset + 26]);
        int size = 27 + segments;
        for (int index = 0; index < segments; index++) {
            size += Byte.toUnsignedInt(bytes[offset + 27 + index]);
        }
        return size;
    }

    private static void rewritePageChecksum(byte[] bytes, int offset) {
        for (int index = 22; index < 26; index++) {
            bytes[offset + index] = 0;
        }
        int crc = 0;
        int size = pageSize(bytes, offset);
        for (int index = 0; index < size; index++) {
            crc ^= Byte.toUnsignedInt(bytes[offset + index]) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = crc << 1 ^ ((crc & 0x8000_0000) != 0 ? 0x04c1_1db7 : 0);
            }
        }
        for (int index = 0; index < 4; index++) {
            bytes[offset + 22 + index] = (byte)(crc >>> (index * 8));
        }
    }
}
