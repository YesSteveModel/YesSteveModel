package com.elfmcys.ysm.format.vfs;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.util.Closeable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.Channels;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Directory implements VirtualFileSystem, Closeable {
    private final Path dir;
    private final Path root;
    private final Map<Path, DirectoryIdentity> directories;
    private NativeBuffer buffer;
    private boolean closed;

    public Directory(Path dir) {
        try {
            this.dir = dir.toRealPath();
            root = this.dir;
            directories = new HashMap<>();
            checkDirectory(root);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Directory(Path dir, Path root, Map<Path, DirectoryIdentity> directories) {
        this.dir = dir;
        this.root = root;
        this.directories = directories;
    }

    @Override
    public Directory directoryView(String path) {
        var subDir = prependPath(path);
        if (subDir == null) {
            throw new IllegalArgumentException("Illegal sub path: " + path);
        }
        try {
            checkDirectory(subDir);
            return new Directory(subDir, root, directories);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Nullable
    private Path prependPath(@Nullable String path) {
        checkOpen();
        if (path == null) {
            return this.dir;
        }
        var newPath = this.dir.resolve(path).normalize();
        if (!newPath.startsWith(this.dir)) {
            return null;
        }
        return newPath;
    }

    @Override
    public @NotNull String[] listFiles(@Nullable String path) {
        return list(path, false);
    }

    @Override
    public @NotNull String[] listDirectories(@Nullable String path) {
        return list(path, true);
    }

    @Override
    public boolean hasFile(String fileName) {
        var file = prependPath(fileName);
        if (file == null) {
            return false;
        }
        try {
            checkDirectory(file.getParent());
            return fileAttributes(file) != null;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private NativeBuffer getBufferView(int size) {
        if (buffer != null) {
            if (buffer.size() < size) {
                var newSize = Math.max(size, buffer.size() * 2);
                buffer.close();
                buffer = NativeBuffer.allocate(newSize);
            }
        } else {
            buffer = NativeBuffer.allocate(Math.max(32 * 1024, size));
        }
        return buffer.slice(0, size).borrow();
    }

    @Override
    public @Nullable NativeBuffer getFile(String fileName) {
        var file = prependPath(fileName);
        if (file == null) {
            return null;
        }
        try {
            checkDirectory(file.getParent());
            var before = fileAttributes(file);
            if (before == null) {
                return null;
            }
            try (var channel = openChannel(file)) {
                var size = before.size();
                if (size > NativeBuffer.MAX_SIZE) {
                    throw new IllegalArgumentException("File is too large: " + file);
                }
                var buffer = getBufferView((int)size);
                readFully(channel, buffer.nio(), file);
                checkDirectory(file.getParent());
                var after = fileAttributes(file);
                if (after == null || !sameFile(before, after)
                    || channel instanceof SeekableByteChannel seekable && seekable.size() != size) {
                    throw new IOException("File changed while reading: " + file);
                }
                return buffer;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ReadableByteChannel openChannel(Path file) throws IOException {
        if (file.getFileSystem().equals(FileSystems.getDefault())) {
            return Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        }
        // Forge's read-only resource provider exposes streams but does not implement byte channels.
        return Channels.newChannel(Files.newInputStream(file));
    }

    @Override
    public void close() {
        if (!closed) {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            closed = true;
        }
    }

    private String[] list(@Nullable String path, boolean directories) {
        var target = prependPath(path);
        if (target == null) {
            throw new IllegalArgumentException("Illegal sub path: " + path);
        }
        try {
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                checkDirectory(target.getParent());
                return new String[0];
            }
            checkDirectory(target);
            var result = new ArrayList<String>();
            try (var stream = Files.newDirectoryStream(target)) {
                for (var child : stream) {
                    var attributes = attributes(child);
                    if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                        throw new IOException("Unsupported raw source entry: " + child);
                    }
                    if (attributes.isDirectory() == directories) {
                        result.add(child.getFileName().toString());
                    }
                }
            }
            checkDirectory(target);
            result.sort(Comparator.naturalOrder());
            return result.toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Directory has been closed");
        }
    }

    private static void readFully(ReadableByteChannel channel, ByteBuffer dst, Path file) throws IOException {
        while (dst.hasRemaining()) {
            checkInterrupted();
            if (channel.read(dst) < 0) {
                throw new EOFException("File changed while reading: " + file);
            }
        }
    }

    private void checkDirectory(Path directory) throws IOException {
        var current = root;
        checkDirectoryIdentity(current);
        for (var segment : root.relativize(directory)) {
            if (!segment.toString().isEmpty()) {
                current = current.resolve(segment);
                checkDirectoryIdentity(current);
            }
        }
    }

    private void checkDirectoryIdentity(Path directory) throws IOException {
        var attributes = attributes(directory);
        if (!attributes.isDirectory()) {
            throw new IOException("Raw source directory was replaced: " + directory);
        }
        var identity = new DirectoryIdentity(attributes.fileKey(), attributes.creationTime());
        var previous = directories.putIfAbsent(directory, identity);
        if (previous != null && !previous.equals(identity)) {
            throw new IOException("Raw source directory changed while reading: " + directory);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        checkInterrupted();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
            || path.getFileSystem().equals(FileSystems.getDefault()) && !path.toRealPath().equals(path)) {
            throw new IOException("Links and special entries are forbidden inside raw sources: " + path);
        }
        return attributes;
    }

    private static BasicFileAttributes fileAttributes(Path path) throws IOException {
        try {
            var attributes = attributes(path);
            return attributes.isRegularFile() ? attributes : null;
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
        return Objects.equals(before.fileKey(), after.fileKey()) && before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime())
            && before.creationTime().equals(after.creationTime());
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Raw source capture cancelled");
        }
    }

    private record DirectoryIdentity(Object fileKey, FileTime creationTime) {}
}
