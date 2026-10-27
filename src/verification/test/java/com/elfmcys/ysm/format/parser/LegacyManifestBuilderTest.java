package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyManifestBuilderTest {
    @Test
    void separatesArrowTextureFromPlayerTextures() throws Exception {
        var manifest = LegacyManifestBuilder.build(
            source("main.json", "arm.json", "main.png", "arrow.json", "arrow.png", "arrow.animation.json"), new Gson());

        assertEquals(List.of("main.png"), manifest.files.player.texture.stream().map(texture -> texture.uv).toList());
        assertEquals("arrow.json", manifest.files.arrow.model);
        assertEquals("arrow.png", manifest.files.arrow.texture.uv);
        assertEquals("arrow.animation.json", manifest.files.arrow.animation);
        assertEquals(List.of("arrow"), manifest.files.arrow.match);
    }

    @Test
    void rejectsArrowModelWithoutTexture() {
        var error = assertThrows(FileNotFoundException.class,
            () -> LegacyManifestBuilder.build(source("main.json", "arm.json", "main.png", "arrow.json"), new Gson()));
        assertEquals("Arrow texture not found", error.getMessage());
    }

    @Test
    void rejectsArrowTextureWithoutModel() {
        var error = assertThrows(UncheckedIOException.class,
            () -> LegacyManifestBuilder.build(source("main.json", "arm.json", "main.png", "arrow.png"), new Gson()));
        assertEquals("Arrow model not found", error.getCause().getMessage());
    }

    private static RawModelSource source(String... names) {
        var files = Set.of(names);
        return new RawModelSource(new VirtualFileSystem() {
            @Override
            public String[] listFiles(String path) {
                return files.toArray(String[]::new);
            }

            @Override
            public String[] listDirectories(String path) {
                return new String[0];
            }

            @Override
            public boolean hasFile(String name) {
                return files.contains(name);
            }

            @Override
            public NativeBuffer getFile(String name) {
                return null;
            }
        });
    }
}
