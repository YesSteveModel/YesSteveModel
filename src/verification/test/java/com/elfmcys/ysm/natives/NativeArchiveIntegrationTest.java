package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeArchiveIntegrationTest {
    @Test
    void readsNonAsciiEntryPaths(@TempDir Path temporary) throws Exception {
        var names = List.of("ascii.txt", "\u00e9.txt", "\u4e2d.txt", "ex\u52d5\u4f5c/mixed-\u00e9-\u4e2d.txt");
        var payload = new byte[] {1, 2, 3, 4};
        var file = temporary.resolve("entries.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(payload);
                zip.closeEntry();
            }
        }

        try (var archive = NativeArchive.open(file.toString())) {
            assertTrue(List.of(archive.listDirectories()).contains("ex\u52d5\u4f5c"));
            assertArrayEquals(new String[] {"mixed-\u00e9-\u4e2d.txt"}, archive.listFiles("ex\u52d5\u4f5c"));
            for (var name : names) {
                assertTrue(archive.hasFile(name), name);
                var buffer = archive.getFile(name);
                assertNotNull(buffer, name);
                var actual = new byte[buffer.nio().remaining()];
                buffer.nio().get(actual);
                assertArrayEquals(payload, actual, name);
            }
            assertFalse(archive.hasFile("missing-\u4e2d.txt"));
        }
    }
}
