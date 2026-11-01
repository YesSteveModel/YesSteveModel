package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelOriginVersion;
import com.google.gson.JsonParser;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.io.UncheckedIOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawModelImporterTest {
    @Test
    void cancelledRawReadAndClosedOwnerDoNotRetainTheStreamOrRevokeAnOwnedCopy(@TempDir Path temporary)
        throws Exception {
        var root = Files.createDirectory(temporary.resolve("raw"));
        var file = Files.write(root.resolve("input"), new byte[] {1, 2, 3});
        var directory = new Directory(root);
        try (var verified = directory.getFile("input").copy()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(UncheckedIOException.class, () -> directory.getFile("input"));
            } finally {
                Thread.interrupted();
            }
            try (var next = directory.getFile("input").copy()) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {1, 2, 3},
                    new byte[] {next.nio().get(0), next.nio().get(1), next.nio().get(2)});
            }
            directory.close();
            assertThrows(IllegalStateException.class, () -> directory.getFile("input"));
            Files.move(file, root.resolve("replacement"));
            assertEquals(1, verified.nio().get(0));
        } finally {
            directory.close();
        }
    }

    @Test
    void nonDefaultResourceProviderReadsTheSameManifestBytes(@TempDir Path temporary) throws Exception {
        var source = copyFixture(temporary.resolve("source"));
        var archive = temporary.resolve("resources.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var files = Files.walk(source)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        try (var resources = java.nio.file.FileSystems.newFileSystem(archive);
            var directory = new Directory(resources.getPath("/"))) {
            var actual = directory.getFile("ysm.json");
            var bytes = new byte[actual.size()];
            actual.nio().get(bytes);
            org.junit.jupiter.api.Assertions.assertArrayEquals(Files.readAllBytes(source.resolve("ysm.json")), bytes);
            try (var captured = ModelParser.capture(directory)) {
                org.junit.jupiter.api.Assertions.assertNotNull(captured);
            }
        }
    }

    @Test
    void rawEntryLinksAreAllowedButInternalFilesDirectoriesAndManifestLinksAreRejected(@TempDir Path temporary)
        throws Exception {
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        var source = copyFixture(temporary.resolve("physical"));
        var link = Files.createSymbolicLink(temporary.resolve("entry"), source);
        try (var expected = importer.capture(source); var actual = importer.capture(link)) {
            assertEquals(expected.modelId(), actual.modelId());
        }
        var external = Files.writeString(temporary.resolve("external.txt"), "external");
        var internal = Files.createSymbolicLink(source.resolve("unused.txt"), external);
        assertThrows(UncheckedIOException.class, () -> importer.capture(link));
        Files.delete(internal);
        var directory = Files.createSymbolicLink(source.resolve("unused-directory"), temporary);
        assertThrows(UncheckedIOException.class, () -> importer.capture(link));
        Files.delete(directory);
        var manifest = Files.move(source.resolve("ysm.json"), temporary.resolve("manifest.json"));
        Files.createSymbolicLink(source.resolve("ysm.json"), manifest);
        assertThrows(UncheckedIOException.class, () -> importer.capture(link));
    }

    @Test
    void linksInsertedBetweenListingAndReadingFailCapture(@TempDir Path temporary) throws Exception {
        var source = copyFixture(temporary.resolve("listing"));
        var external = Files.writeString(temporary.resolve("external.txt"), "external");
        try (var vfs = new Directory(source) {
            @Override
            public String[] listFiles(String path) {
                var files = super.listFiles(path);
                if (path == null) {
                    try {
                        Files.createSymbolicLink(source.resolve("late.txt"), external);
                    } catch (java.io.IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
                return files;
            }
        }) {
            assertThrows(UncheckedIOException.class, () -> ModelParser.capture(vfs));
        }
        var reading = copyFixture(temporary.resolve("reading"));
        var manifest = temporary.resolve("manifest.json");
        try (var vfs = new Directory(reading) {
            @Override
            public NativeBuffer getFile(String path) {
                if (path.equals("ysm.json")) {
                    try {
                        Files.move(reading.resolve(path), manifest);
                        Files.createSymbolicLink(reading.resolve(path), manifest);
                    } catch (java.io.IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
                return super.getFile(path);
            }
        }) {
            assertThrows(UncheckedIOException.class, () -> ModelParser.capture(vfs));
        }
    }

    @Test
    void directoryViewsKeepTheOriginalRawBoundary(@TempDir Path temporary) throws Exception {
        var source = Files.createDirectory(temporary.resolve("source"));
        var child = Files.createDirectory(source.resolve("child"));
        try (var vfs = new Directory(source); var view = vfs.directoryView("child")) {
            Files.move(child, temporary.resolve("old-child"));
            Files.createSymbolicLink(child, temporary.resolve("old-child"));
            assertThrows(UncheckedIOException.class, () -> view.listFiles(null));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rawInternalJunctionIsRejectedDuringStampAndCapture(@TempDir Path temporary) throws Exception {
        var source = copyFixture(temporary.resolve("raw"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        var junction = source.resolve("unused-junction");
        var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
            .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        try {
            assertThrows(java.io.IOException.class,
                () -> com.elfmcys.ysm.model.catalog.source.SourceStamp.captureDirectory(source));
            assertThrows(UncheckedIOException.class,
                () -> new RawModelImporter(DefaultAnimationFilter.keepAll()).capture(source));
        } finally {
            Files.delete(junction);
        }
    }

    private static Path copyFixture(Path target) throws Exception {
        var resource =
            Objects.requireNonNull(RawModelImporterTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json"));
        var source = Path.of(resource.toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }

    @Test
    void directoryAndArchiveUseTheExplicitOriginOrDefaultToTheCurrentRelease(@TempDir Path temporary) throws Exception {
        var resource =
            Objects.requireNonNull(RawModelImporterTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json"));
        var fixture = Path.of(resource.toURI()).getParent();
        var source = Files.createDirectory(temporary.resolve("source"));
        try (var paths = Files.walk(fixture)) {
            for (var path : paths.toList()) {
                var target = source.resolve(fixture.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
        var original = Files.readString(source.resolve("ysm.json"));
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        for (var origin : new String[] {null, "", "2.4.2-snapshot-32"}) {
            var manifest = JsonParser.parseString(original).getAsJsonObject();
            manifest.getAsJsonObject("properties").addProperty("originVer", origin);
            Files.writeString(source.resolve("ysm.json"), manifest.toString());
            var archive = temporary.resolve("source.zip");
            try (var output = new ZipOutputStream(Files.newOutputStream(archive)); var paths = Files.walk(source)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new ZipEntry(source.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, output);
                    output.closeEntry();
                }
            }
            var expected = origin == null || origin.isBlank() ? ModelOriginVersion.CURRENT_RELEASE : origin;
            for (var input : new Path[] {source, archive}) {
                try (var captured = importer.capture(input)) {
                    assertEquals(expected, captured.originVer());
                    var result = importer.convert(captured, temporary.resolve("converted"));
                    try (var channel = FileChannel.open(result.stagedContainer(), StandardOpenOption.READ)) {
                        var model = new ModelFileView(channel);
                        assertEquals(expected, model.getManifest().info().properties().originVer());
                        assertEquals(ModelOriginVersion.fromModVersion(expected), model.getMetadata().originVer());
                    }
                }
            }
        }
    }
}
