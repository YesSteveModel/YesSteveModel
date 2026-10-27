package com.elfmcys.ysm.model.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelHashingTest {
    @TempDir
    Path temp;

    @Test
    void hashesFileFromJarFileSystem() throws Exception {
        var content = new byte[] {1, 2, 3, 4};
        var archive = temp.resolve("models.jar");

        try (var fileSystem =
            FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            var file = fileSystem.getPath("/assets/mod/builtin/ysm-pack.png");
            Files.createDirectories(file.getParent());
            Files.write(file, content);

            assertEquals(ModelHashing.blake3(content), ModelHashing.blake3(file));
        }
    }
}
