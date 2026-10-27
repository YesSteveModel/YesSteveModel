package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RawModelSourceTest {
    @Test
    void hashesOriginalImageBytesBeforeDownstreamProcessing() throws IOException {
        try (var file = NativeBuffer.allocate(4)) {
            file.nio().put(new byte[] {1, 2, 3, 4});
            var expected = new ModelHashCanonicalizer();
            expected.add("texture", "textures/main.png", new byte[] {1, 2, 3, 4});

            var source = new RawModelSource(new SingleFileVfs("textures/main.png", file));
            var borrowed = source.readFile("textures/main.png", "texture", true).orElseThrow();
            borrowed.nio().put(0, (byte)99);

            assertArrayEquals(expected.aggregate(), source.aggregateHash());
        }
    }

    @Test
    void visitsFilesAndDirectoriesInCanonicalOrder() {
        var source = new RawModelSource(new ShuffledTreeVfs());
        var names = new ArrayList<String>();

        source.collectFileNames("functions", ".molang", true, (name, path) -> names.add(name));

        assertEquals(List.of("a", "z", "a/first", "z/last"), names);
    }

    private record SingleFileVfs(String path, NativeBuffer file) implements VirtualFileSystem {
        @Override
        public String[] listFiles(String path) {
            return new String[0];
        }

        @Override
        public String[] listDirectories(String path) {
            return new String[0];
        }

        @Override
        public boolean hasFile(String fileName) {
            return path.equals(fileName);
        }

        @Override
        public NativeBuffer getFile(String fileName) {
            return hasFile(fileName) ? file : null;
        }
    }

    private static final class ShuffledTreeVfs implements VirtualFileSystem {
        @Override
        public String[] listFiles(String path) {
            return switch (path) {
                case "functions" -> new String[] {"z.molang", "ignored.txt", "a.molang"};
                case "functions/a" -> new String[] {"first.molang"};
                case "functions/z" -> new String[] {"last.molang"};
                default -> new String[0];
            };
        }

        @Override
        public String[] listDirectories(String path) {
            return path.equals("functions") ? new String[] {"z", "a"} : new String[0];
        }

        @Override
        public boolean hasFile(String fileName) {
            return false;
        }

        @Override
        public NativeBuffer getFile(String fileName) {
            return null;
        }
    }
}
