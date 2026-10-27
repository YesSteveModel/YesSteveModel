package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.media.SupportedAudioProbe;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class ModelAudioContentPathTest {
    @TempDir
    Path temp;

    @Test
    void rawDirectoryArchiveAndCurrentExportPreserveConsumableAudio() throws Exception {
        var source = copyRawFixture(temp.resolve("raw"));
        var archive = zip(source, temp.resolve("raw.zip"));
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());

        Path directoryContainer;
        try (var captured = importer.capture(source)) {
            directoryContainer =
                importer.convert(captured, Files.createDirectories(temp.resolve("directory-output"))).stagedContainer();
        }
        Path archiveContainer;
        try (var captured = importer.capture(archive)) {
            archiveContainer =
                importer.convert(captured, Files.createDirectories(temp.resolve("archive-output"))).stagedContainer();
        }

        var directory = open(directoryContainer);
        var archived = open(archiveContainer);
        try {
            assertEquals(directory.representation().modelId(), archived.representation().modelId());
            assertAudio(directory);
            assertAudio(archived);
            assertSoundBytesEqual(directory, archived);

            var exportedPath = temp.resolve("exported.ysm");
            ModelExporter.export(directory, TestPreviews.blank(), exportedPath, "audio-u2");
            var exported = open(exportedPath);
            try {
                assertEquals(directory.representation().modelId(), exported.representation().modelId());
                assertNotEquals(directory.representation().containerId(), exported.representation().containerId());
                assertAudio(exported);
                assertSoundBytesEqual(directory, exported);
            } finally {
                exported.representation().close();
            }
        } finally {
            archived.representation().close();
            directory.representation().close();
        }
    }

    @Test
    void normalizationRejectsVorbisCodecCorruptionAfterMetadataAdmission() throws Exception {
        var source = copyRawFixture(temp.resolve("corrupt-raw"));
        var corrupt = Files.readAllBytes(fixtureRoot().resolve("vorbis-under.ogg"));
        int setup = find(corrupt, new byte[] {5, 'v', 'o', 'r', 'b', 'i', 's'});
        assertTrue(setup >= 0);
        corrupt[setup + 20] ^= 0x40;
        rewritePageChecksum(corrupt, pageContaining(corrupt, setup));
        Files.write(source.resolve("sounds/tone_corrupt.ogg"), corrupt);

        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        try (var captured = importer.capture(source)) {
            var output = Files.createDirectories(temp.resolve("corrupt-output"));
            var failure = assertThrows(java.io.UncheckedIOException.class, () -> importer.convert(captured, output));
            assertTrue(failure.getCause().getMessage().contains("SOURCE_INVALID"));
            try (var files = Files.list(output)) {
                assertEquals(0, files.count());
            }
        }
    }

    private static void assertSoundBytesEqual(ManagedContainer expected, ManagedContainer actual) throws Exception {
        for (var name : expected.modelFile().getCommon().sounds().keySet()) {
            assertArrayEquals(readSoundBytes(expected, name), readSoundBytes(actual, name), name);
        }
    }

    private static void assertAudio(ManagedContainer content) throws Exception {
        var sounds = content.modelFile().getCommon().sounds();
        assertEquals(Set.of("tone_opus", "tone_vorbis"), sounds.keySet());
        assertEquals(48_000, sounds.get("tone_opus").sampleRate());
        assertEquals(60_001, sounds.get("tone_opus").frames());
        assertEquals(48_000, sounds.get("tone_vorbis").sampleRate());
        assertEquals(191_999, sounds.get("tone_vorbis").frames());
        content.modelFile().getCommon().validateSoundContent(() -> false, content.chunks());

        var normalized = readSoundBytes(content, "tone_opus");
        var opus = SupportedAudioProbe.inspect(java.nio.ByteBuffer.wrap(normalized));
        assertTrue(opus.playable(), opus.diagnostic());
        assertEquals(SupportedAudioProbe.Encoding.OGG_OPUS, opus.media().encoding());
        var transcoded = readSoundBytes(content, "tone_vorbis");
        assertFalse(Arrays.equals(Files.readAllBytes(fixtureRoot().resolve("vorbis-under.ogg")), transcoded));
        var inspection = SupportedAudioProbe.inspect(java.nio.ByteBuffer.wrap(transcoded));
        assertTrue(inspection.playable());
        assertEquals(SupportedAudioProbe.Encoding.OGG_OPUS, inspection.media().encoding());
    }

    @Test
    void builtinAudioRoundTripsThroughRawArchiveAndCurrentExport() throws Exception {
        var source = copyBuiltinAudioFixture(temp.resolve("builtin-raw"));
        var archive = zip(source, temp.resolve("builtin-raw.zip"));
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());

        Path directoryContainer;
        try (var captured = importer.capture(source)) {
            directoryContainer = importer
                .convert(captured, Files.createDirectories(temp.resolve("builtin-directory-output"))).stagedContainer();
        }
        Path archiveContainer;
        try (var captured = importer.capture(archive)) {
            archiveContainer = importer
                .convert(captured, Files.createDirectories(temp.resolve("builtin-archive-output"))).stagedContainer();
        }

        try (var directory = closeable(open(directoryContainer)); var archived = closeable(open(archiveContainer))) {
            assertBuiltinAudio(directory.content());
            assertBuiltinAudio(archived.content());
            var exportedPath = temp.resolve("builtin-exported.mxc");
            ModelExporter.export(directory.content(), TestPreviews.blank(), exportedPath, "audio-u2-builtin");
            try (var exported = closeable(open(exportedPath))) {
                assertBuiltinAudio(exported.content());
            }
        }
    }

    @Test
    void directDirectoryParseOwnsBorrowedSoundBuffersUntilGeneration() throws Exception {
        var source = copyBuiltinAudioFixture(temp.resolve("borrowed-raw"));
        Path container;
        try (var directory = new Directory(source)) {
            container = ModelParser.parse(directory, Files.createDirectories(temp.resolve("borrowed-output")),
                DefaultAnimationFilter.keepAll());
        }

        try (var content = closeable(open(container))) {
            assertBuiltinAudio(content.content());
        }
    }

    private static byte[] readSoundBytes(ManagedContainer content, String soundName) throws Exception {
        var sound = content.modelFile().getCommon().sounds().get(soundName);
        var output = new java.io.ByteArrayOutputStream();
        var firstPage = sound.firstPage();
        var firstBytes = new byte[firstPage.remaining()];
        firstPage.get(firstBytes);
        output.write(firstBytes);
        for (var chunk : sound.chunkInfos()) {
            assertTrue(chunk.encoding().isEmpty());
            assertEquals(0, chunk.decodeSize());
            assertEquals(0, chunk.flags());
            try (var payload = content.chunks().readPayload(chunk, BufferType.ARRAY);
                var array = payload.acquireArray()) {
                output.write(array.array(), array.arrayOffset(), array.size());
            }
        }
        return output.toByteArray();
    }

    private static void assertBuiltinAudio(ManagedContainer content) throws Exception {
        var sounds = content.modelFile().getCommon().sounds();
        assertEquals(Set.of("tone_opus", "tone_vorbis"), sounds.keySet());
        content.modelFile().getCommon().validateSoundContent(() -> false, content.chunks());
        for (var name : sounds.keySet()) {
            var encoded = readSoundBytes(content, name);
            var inspection = SupportedAudioProbe.inspect(java.nio.ByteBuffer.wrap(encoded));
            assertTrue(inspection.playable(), inspection.diagnostic());
            assertEquals(SupportedAudioProbe.Encoding.OGG_OPUS, inspection.media().encoding());
        }
        assertFalse(Arrays.equals(readSoundBytes(content, "tone_vorbis"),
            Files.readAllBytes(builtinAudio("wine_fox/18_wedding/sounds/motor.ogg"))));
    }

    private static ManagedContainer open(Path file) throws Exception {
        return ManagedContainer.openDirect(file,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("audio-u2")));
    }

    private static Path copyRawFixture(Path destination) throws Exception {
        var manifest = ModelAudioContentPathTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var sounds = Files.createDirectories(destination.resolve("sounds"));
        Files.copy(fixtureRoot().resolve("opus-input-rate.ogg"), sounds.resolve("tone_opus.ogg"));
        Files.copy(fixtureRoot().resolve("vorbis-under.ogg"), sounds.resolve("tone_vorbis.ogg"));
        return destination;
    }

    private static Path copyBuiltinAudioFixture(Path destination) throws Exception {
        var manifest = ModelAudioContentPathTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var sounds = Files.createDirectories(destination.resolve("sounds"));
        Files.copy(builtinAudio("wine_fox/14_momo/sounds/fly_on.ogg"), sounds.resolve("tone_opus.ogg"));
        Files.copy(builtinAudio("wine_fox/18_wedding/sounds/motor.ogg"), sounds.resolve("tone_vorbis.ogg"));
        return destination;
    }

    private static Path builtinAudio(String relative) throws Exception {
        return Path.of(Objects
            .requireNonNull(ModelAudioContentPathTest.class.getResource("/assets/ysm/builtin/" + relative)).toURI());
    }

    private static ManagedCloseable closeable(ManagedContainer content) {
        return new ManagedCloseable(content);
    }

    private record ManagedCloseable(ManagedContainer content) implements AutoCloseable {
        @Override
        public void close() {
            content.representation().close();
        }
    }

    private static Path zip(Path source, Path archive) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(archive)); var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var name = source.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new ZipEntry(name));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        assertFalse(Files.size(archive) == 0);
        return archive;
    }

    private static int find(byte[] bytes, byte[] pattern) {
        outer:
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    private static int pageContaining(byte[] bytes, int target) {
        for (int offset = 0; offset < bytes.length;) {
            int size = pageSize(bytes, offset);
            if (target < offset + size) {
                return offset;
            }
            offset += size;
        }
        throw new IllegalArgumentException("target is outside Ogg pages");
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

    private static Path fixtureRoot() {
        return com.elfmcys.ysm.testutil.AudioFixtures.root();
    }
}
