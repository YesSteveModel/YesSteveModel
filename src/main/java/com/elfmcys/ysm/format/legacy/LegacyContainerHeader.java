package com.elfmcys.ysm.format.legacy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Provides only the routing hint that Java can prove without owning legacy parsing. */
public final class LegacyContainerHeader {
    private static final byte[] RAW_MAGIC = {'Y', 'S', 'G', 'P'};
    private static final byte[] V3_MAGIC = {(byte)0xEF, (byte)0xBB, (byte)0xBF, 'Y', 'S', 'G', 'P'};

    private LegacyContainerHeader() {}

    public static Version probe(Path source) throws IOException {
        try (var input = new BufferedInputStream(Files.newInputStream(source))) {
            var prefix = input.readNBytes(8);
            if (prefix.length == 8 && startsWith(prefix, RAW_MAGIC)) {
                var version = ByteBuffer.wrap(prefix, RAW_MAGIC.length, Integer.BYTES).getInt();
                return switch (version) {
                    case 1 -> Version.V1_RAW;
                    case 2 -> Version.V2_RAW;
                    default -> Version.UNSUPPORTED;
                };
            }
            return startsWith(prefix, V3_MAGIC) ? Version.V3_ENCRYPTED : Version.UNSUPPORTED;
        }
    }

    private static boolean startsWith(byte[] input, byte[] expected) {
        if (input.length < expected.length) {
            return false;
        }
        for (var i = 0; i < expected.length; i++) {
            if (input[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    public enum Version {
        V1_RAW, V2_RAW, V3_ENCRYPTED, UNSUPPORTED
    }
}
