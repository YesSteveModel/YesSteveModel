package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

public sealed interface SourceStamp permits SourceStamp.File, SourceStamp.RawDirectory {
    static File captureFile(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Model source is not a regular non-link file: " + path);
        }
        return new File(attributes.size(), attributes.lastModifiedTime().toMillis(),
            Objects.toString(attributes.fileKey(), ""));
    }

    static RawDirectory captureDirectory(Path root) throws IOException {
        var entries = new ArrayList<DirectoryEntry>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
                rejectLink(directory, attributes);
                entries.add(DirectoryEntry.directory(relative(root, directory), attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                rejectLink(file, attributes);
                if (!attributes.isRegularFile()) {
                    throw new IOException("Unsupported entry in raw model source: " + file);
                }
                entries.add(DirectoryEntry.file(relative(root, file), attributes));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                throw new IOException("Failed to observe raw model source entry: " + file, error);
            }
        });
        entries.sort(Comparator.comparing(DirectoryEntry::path));
        var bytes = new ByteArrayOutputStream();
        long totalBytes = 0;
        var fileCount = 0;
        try (var output = new DataOutputStream(bytes)) {
            for (var entry : entries) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Raw source observation cancelled");
                }
                output.writeUTF(entry.path);
                output.writeByte(entry.type);
                output.writeLong(entry.size);
                output.writeLong(entry.lastModifiedMillis);
                output.writeUTF(entry.fileKey);
                if (entry.type == 1) {
                    totalBytes = Math.addExact(totalBytes, entry.size);
                    fileCount++;
                }
            }
        }
        return new RawDirectory(ModelHashing.blake3(bytes.toByteArray()), fileCount, totalBytes);
    }

    private static void rejectLink(Path path, BasicFileAttributes attributes) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Raw source observation cancelled");
        }
        if (attributes.isSymbolicLink() || attributes.isOther()
            || !path.toRealPath().equals(path.toAbsolutePath().normalize())) {
            throw new IOException("Links and special entries are not allowed in model sources: " + path);
        }
    }

    private static String relative(Path root, Path path) {
        var names = new java.util.StringJoiner("/");
        for (var segment : root.relativize(path))
            names.add(segment.toString());
        return names.toString();
    }

    record File(long size, long lastModifiedMillis, String fileKey) implements SourceStamp {
        public File {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            fileKey = Objects.requireNonNullElse(fileKey, "");
        }
    }

    record RawDirectory(Hash256 metadataDigest, int fileCount, long totalBytes) implements SourceStamp {
        public RawDirectory {
            Objects.requireNonNull(metadataDigest, "metadataDigest");
            if (fileCount < 0 || totalBytes < 0) {
                throw new IllegalArgumentException("directory counts must not be negative");
            }
        }
    }

    record DirectoryEntry(String path, int type, long size, long lastModifiedMillis, String fileKey) {
        static DirectoryEntry file(String path, BasicFileAttributes attributes) {
            return new DirectoryEntry(path, 1, attributes.size(), attributes.lastModifiedTime().toMillis(),
                Objects.toString(attributes.fileKey(), ""));
        }

        static DirectoryEntry directory(String path, BasicFileAttributes attributes) {
            // Windows can settle directory mtimes after child creation has completed.
            return new DirectoryEntry(path, 0, 0, 0, Objects.toString(attributes.fileKey(), ""));
        }
    }
}
