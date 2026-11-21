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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeArchiveIntegrationTest {
    @Test
    void memberTablePreservesOriginalNamesSizesAndUnsignedCrc(@TempDir Path temporary) throws Exception {
        var file = temporary.resolve("folded.zip");
        var payload = new byte[] {1, 2, 3, 4};
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("root/deep/a"));
            zip.write(payload);
            zip.closeEntry();
        }
        try (var archive = NativeArchive.open(file.toString())) {
            var member = archive.members().get(0);
            var crc = new java.util.zip.CRC32();
            crc.update(payload);
            org.junit.jupiter.api.Assertions.assertEquals("root/deep/a", member.name());
            org.junit.jupiter.api.Assertions.assertEquals(payload.length, member.size());
            org.junit.jupiter.api.Assertions.assertEquals(crc.getValue(), member.crc().orElseThrow());
            assertArrayEquals(new String[] {"a"}, archive.listFiles());
        }
    }

    @Test
    void memberTableKeepsDistinctNamesWithSupplementaryCharacters(@TempDir Path temporary) throws Exception {
        var file = temporary.resolve("supplementary.zip");
        var names = List.of("root/\uD83D\uDE00.jpg", "root/\uD83D\uDE00.png");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(1);
                zip.closeEntry();
            }
        }
        try (var archive = NativeArchive.open(file.toString())) {
            assertEquals(names, archive.members().stream().map(NativeArchive.Member::name).toList());
        }
    }

    @Test
    void ambiguousMembersAreRejectedBeforeEitherObservationOrParse(@TempDir Path temporary) throws Exception {
        var file = temporary.resolve("ambiguous.zip");
        for (var names : List.of(List.of("a", "/a"), List.of("a", "a/b"), List.of("a/b", "a"))) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
                for (var name : names) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(1);
                    zip.closeEntry();
                }
            }
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> NativeArchive.open(file.toString()));
        }
    }

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
