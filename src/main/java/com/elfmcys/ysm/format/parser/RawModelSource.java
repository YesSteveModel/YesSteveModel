package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import org.apache.commons.lang3.StringUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Owns raw-model VFS reads, path normalization and canonical source hashing. */
final class RawModelSource {
    private final VirtualFileSystem vfs;
    private final ModelHashCanonicalizer canonicalizer = new ModelHashCanonicalizer();

    RawModelSource(VirtualFileSystem vfs) {
        this.vfs = Objects.requireNonNull(vfs, "vfs");
    }

    boolean hasFile(String path) {
        return vfs.hasFile(path);
    }

    byte[] aggregateHash() {
        return canonicalizer.aggregate();
    }

    Optional<NativeBuffer> readFile(String path, String type, boolean required) throws FileNotFoundException {
        if (!StringUtils.isBlank(path)) {
            var borrowed = vfs.getFile(path);
            if (borrowed != null) {
                canonicalizer.add(type, path, borrowed);
                return Optional.of(borrowed);
            }
        }
        if (required) {
            throw new FileNotFoundException("File not found: " + path);
        }
        return Optional.empty();
    }

    Optional<String> readText(String path, String type, boolean required) throws IOException {
        return readFile(path, type, required).map(RawModelSource::readUtf8);
    }

    <T> Optional<T> readJson(String path, String type, Gson gson, Class<T> typeClass, boolean required)
        throws IOException {
        return readFile(path, type, required).map(file -> {
            try (var reader =
                new JsonReader(new InputStreamReader(new ByteBufferInputStream(file.nio()), StandardCharsets.UTF_8))) {
                return gson.fromJson(reader, typeClass);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
    }

    void collectFiles(String directory, String extension, String type, boolean recursive,
        BiConsumer<String, NativeBuffer> consumer) {
        collectFileNames(directory, extension, recursive, (name, path) -> {
            try {
                consumer.accept(name, readFile(path, type, true).orElseThrow());
            } catch (FileNotFoundException error) {
                throw new UncheckedIOException(error);
            }
        });
    }

    void collectFileNames(String directory, String extension, boolean recursive, BiConsumer<String, String> consumer) {
        var root = normalizePath(directory);
        collectFilesImpl(root, root, extension.toLowerCase(Locale.ROOT), recursive, consumer);
    }

    static String readUtf8(NativeBuffer buffer) {
        return StandardCharsets.UTF_8.decode(buffer.nio()).toString();
    }

    private void collectFilesImpl(String root, String directory, String extension, boolean recursive,
        BiConsumer<String, String> consumer) {
        var files = new java.util.ArrayList<>(
            java.util.Arrays.asList(vfs.listFiles(StringUtils.isBlank(directory) ? null : directory)));
        files.sort(String::compareTo);
        for (var file : files) {
            if (!file.toLowerCase(Locale.ROOT).endsWith(extension)) {
                continue;
            }
            var fullPath = joinPath(directory, file);
            var relativePath = StringUtils.isBlank(root) ? fullPath : fullPath.substring(root.length() + 1);
            consumer.accept(removeExtension(relativePath), fullPath);
        }
        if (!recursive) {
            return;
        }
        var children = new java.util.ArrayList<>(
            java.util.Arrays.asList(vfs.listDirectories(StringUtils.isBlank(directory) ? null : directory)));
        children.sort(String::compareTo);
        for (var child : children) {
            collectFilesImpl(root, joinPath(directory, child), extension, true, consumer);
        }
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        var normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String joinPath(String directory, String name) {
        return StringUtils.isBlank(directory) ? name : directory + "/" + name;
    }

    private static String removeExtension(String path) {
        var index = path.lastIndexOf('.');
        return index >= 0 ? path.substring(0, index) : path;
    }

    private static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buffer;

        private ByteBufferInputStream(ByteBuffer buffer) {
            this.buffer = buffer.duplicate();
        }

        @Override
        public int read() {
            return buffer.hasRemaining() ? Byte.toUnsignedInt(buffer.get()) : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            var count = Math.min(length, buffer.remaining());
            buffer.get(target, offset, count);
            return count;
        }
    }
}
