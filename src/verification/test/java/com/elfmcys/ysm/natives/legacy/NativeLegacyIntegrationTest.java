package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeLegacyIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void invokesEnvelopeRouterRegisteredByTheMainLibrary() throws Exception {
        assertStatus(temp.resolve("missing.ysm"), NativeLegacyStatus.SOURCE_IO);

        assertBytes("empty", new byte[0], NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("other", new byte[] {'O', 'T', 'H', 'E', 'R'}, NativeLegacyStatus.INVALID_CONTENT);
        for (var length = 1; length < 4; length++) {
            assertBytes("raw-prefix-" + length, Arrays.copyOf(rawHeader(1), length),
                NativeLegacyStatus.INVALID_CONTENT);
        }
        for (var length = 4; length < 8; length++) {
            assertBytes("raw-truncated-" + length, Arrays.copyOf(rawHeader(1), length),
                NativeLegacyStatus.INVALID_CONTENT);
        }

        assertBytes("raw-v1", rawHeader(1), NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("raw-v2", rawHeader(2), NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("raw-v3", rawHeader(3), NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("raw-v4", rawHeader(4), NativeLegacyStatus.INVALID_CONTENT);

        for (var length = 4; length < 7; length++) {
            assertBytes("encrypted-prefix-" + length, Arrays.copyOf(encryptedHeader(3), length),
                NativeLegacyStatus.INVALID_CONTENT);
        }
        assertBytes("encrypted-summary-truncated", Arrays.copyOf(encryptedHeader(3), 7),
            NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("encrypted-version-truncated", Arrays.copyOf(encryptedHeader(3), 8),
            NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("encrypted-v3-body-truncated", encryptedHeader(3), NativeLegacyStatus.INVALID_CONTENT);
        assertBytes("encrypted-v4", encryptedHeader(4), NativeLegacyStatus.UNSUPPORTED_VERSION);
    }

    private static byte[] rawHeader(int version) {
        return ByteBuffer.allocate(8).put(new byte[] {'Y', 'S', 'G', 'P'}).putInt(version).array();
    }

    private static byte[] encryptedHeader(int version) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF, 'Y', 'S', 'G', 'P', 0}).putInt(version).array();
    }

    private void assertBytes(String name, byte[] bytes, NativeLegacyStatus expected) throws Exception {
        var source = temp.resolve(name + ".ysm");
        Files.write(source, bytes);
        assertStatus(source, expected);
    }

    private static void assertStatus(Path path, NativeLegacyStatus expected) {
        var response = NativeLegacyImporter.invoke(path);
        var failure = assertInstanceOf(NativeLegacyProtocol.Failure.class, response);
        assertEquals(expected, failure.status());
    }
}
