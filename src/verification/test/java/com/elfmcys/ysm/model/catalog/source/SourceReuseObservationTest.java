package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.natives.NativeArchive;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class SourceReuseObservationTest {
    @TempDir
    Path temp;

    @Test
    void rawObservationIncludesManifestBytesAndCurrentMemberSet() throws Exception {
        var raw = Files.createDirectories(temp.resolve("raw"));
        var manifest = Files.writeString(raw.resolve("ysm.json"), "{\"a\":1}");
        Files.writeString(raw.resolve("model.json"), "payload");
        var first = observed(raw, ModelSourceKind.CURRENT_RAW_DIRECTORY);
        var stamp = Files.getLastModifiedTime(manifest);
        Files.writeString(manifest, "{\"a\":2}");
        Files.setLastModifiedTime(manifest, stamp);
        var changed = observed(raw, ModelSourceKind.CURRENT_RAW_DIRECTORY);
        assertNotEquals(first.nio(), changed.nio());
        Files.writeString(raw.resolve("new-member"), "");
        var added = observed(raw, ModelSourceKind.CURRENT_RAW_DIRECTORY);
        assertNotEquals(changed.nio(), added.nio());
        Files.delete(raw.resolve("model.json"));
        assertNotEquals(added.nio(), observed(raw, ModelSourceKind.CURRENT_RAW_DIRECTORY).nio());
    }

    @Test
    void legacyZipNameDisablesReuseAndKeepsOtherEntriesReadable() throws Exception {
        var path = temp.resolve("legacy-encoding.zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(path), Charset.forName("IBM437"))) {
            for (var name : java.util.List.of("plain.txt", "\u00e9.txt")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(1);
                zip.closeEntry();
            }
        }
        var source = sourceObservation(path, ModelSourceKind.CURRENT_RAW_ARCHIVE);
        try (var archive = NativeArchive.open(path.toString())) {
            assertTrue(SourceReuseObservation.capture(source, archive).isEmpty());
            assertNotNull(archive.getFile("plain.txt"));
        }
    }

    @Test
    void legacyObservationReadsOnlyFooterHint() throws Exception {
        var file = Files.write(temp.resolve("legacy.ysm"), new byte[64]);
        var first = observed(file, ModelSourceKind.LEGACY_ARCHIVE);
        var bytes = Files.readAllBytes(file);
        bytes[20] = 1;
        Files.write(file, bytes);
        assertEquals(first.nio(), observed(file, ModelSourceKind.LEGACY_ARCHIVE).nio());
        bytes[63] = 1;
        Files.write(file, bytes);
        assertNotEquals(first.nio(), observed(file, ModelSourceKind.LEGACY_ARCHIVE).nio());
    }

    @Test
    void archiveHintsUseTheWholeOriginalMemberSetAndIgnoreDirectoryOrder() throws Exception {
        var path = temp.resolve("members.zip");
        zip(path, java.util.List.of("root/a", "root/b"), "value");
        var original = observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE);
        zip(path, java.util.List.of("root/b", "root/a"), "value");
        assertEquals(original.nio(), observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE).nio());
        for (var names : java.util.List.of(java.util.List.of("root/a", "root/c"), java.util.List.of("root/a"),
            java.util.List.of("root/a", "root/b", "root/c"))) {
            zip(path, names, "value");
            assertNotEquals(original.nio(), observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE).nio());
        }
        zip(path, java.util.List.of("root/a", "root/b"), "other");
        assertNotEquals(original.nio(), observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE).nio(),
            "CRC changes at equal size");
        zip(path, java.util.List.of("root/a", "root/b"), "longer");
        assertNotEquals(original.nio(), observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE).nio(), "member size");
        zip(path, java.util.List.of("root/a", "root/b", "other/unread"), "value");
        assertNotEquals(original.nio(), observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE).nio(),
            "members outside the folded model root");
    }

    @Test
    void archiveHintSupportsLongNamesWithSupplementaryCharacters() throws Exception {
        var path = temp.resolve("long-name.zip");
        var name = "\uD83D\uDE00".repeat(11_000);
        zip(path, java.util.List.of(name + ".jpg"), "value");
        var first = observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE);
        zip(path, java.util.List.of(name + ".png"), "value");
        assertNotEquals(first.nio(), observed(path, ModelSourceKind.CURRENT_RAW_ARCHIVE).nio());
    }

    private static void zip(Path path, java.util.List<String> names, String data) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            for (var name : names) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private com.elfmcys.ysm.buffer.SharedArrayBuffer observed(Path path, ModelSourceKind kind) throws Exception {
        return SourceReuseObservation.capture(sourceObservation(path, kind)).orElseThrow();
    }

    private SourceObservation sourceObservation(Path path, ModelSourceKind kind) throws Exception {
        var key = new ModelSourceKey(new CatalogRootIdentity(CatalogRootKind.MODELS, temp),
            new ModelPath(path.getFileName().toString()), kind);
        SourceStamp stamp =
            Files.isDirectory(path) ? SourceStamp.captureDirectory(path) : SourceStamp.captureFile(path);
        return new SourceObservation(key, path, path, stamp, 0);
    }
}
