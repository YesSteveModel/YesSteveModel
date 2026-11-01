package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeStorage;
import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.vfs.VirtualFileSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the frozen raw parse closure used by later compilation.
 *
 * <p>Ownership: the VFS and its buffers are borrowed until this capture closes. Closing the VFS view
 * is not a separate ownership transfer. The caller excludes close while a compilation or borrow is active.
 *
 * <p>Seam: supports [frozen input reuse](docs/architecture/asset-pipeline/overview.md#冻结输入与单向转换); an uncaptured file is not fetched from the original source after freezing.
 */
public interface CaptureData extends AutoCloseable {
    /** Returns the borrowed frozen view; no source I/O or new ownership is implied. */
    VirtualFileSystem asVirtualFileSystem();

    /** Ends the capture and invalidates all its borrowed views and buffers. */
    @Override
    void close();
}

final class CapturedSourceData implements CaptureData, VirtualFileSystem {
    private final Map<String, DirectoryEntries> directories = new HashMap<>();
    private final Set<String> sourceFiles = new HashSet<>();
    private final Map<String, NativeStorage> capturedFiles = new HashMap<>();
    private VirtualFileSystem source;
    private State state = State.CAPTURING;

    CapturedSourceData(VirtualFileSystem source) {
        this.source = Objects.requireNonNull(source, "source");
        snapshotDirectory("");
    }

    void freeze() {
        if (state != State.CAPTURING) {
            throw new IllegalStateException("Capture is not active");
        }
        source = null;
        state = State.FROZEN;
    }

    @Override
    public VirtualFileSystem asVirtualFileSystem() {
        checkOpen();
        return this;
    }

    @Override
    public @NotNull String[] listFiles(@Nullable String path) {
        checkOpen();
        var entries = directories.get(directoryPath(path));
        return entries == null ? new String[0] : entries.files.clone();
    }

    @Override
    public @NotNull String[] listDirectories(@Nullable String path) {
        checkOpen();
        var entries = directories.get(directoryPath(path));
        return entries == null ? new String[0] : entries.directories.clone();
    }

    @Override
    public boolean hasFile(String fileName) {
        checkOpen();
        return sourceFiles.contains(portablePath(fileName));
    }

    @Override
    public @Nullable NativeBuffer getFile(String fileName) {
        checkOpen();
        var path = portablePath(fileName);
        if (!sourceFiles.contains(path)) {
            return null;
        }

        var captured = capturedFiles.get(path);
        if (captured == null) {
            if (state != State.CAPTURING) {
                throw new IllegalStateException("File was not read during capture: " + path);
            }
            var borrowed = Objects.requireNonNull(source, "source").getFile(path);
            if (borrowed == null) {
                throw new IllegalStateException("Listed file disappeared during capture: " + path);
            }
            captured = borrowed.copy();
            capturedFiles.put(path, captured);
        }
        return BorrowedNativeBuffer.of(this, captured.nio().asReadOnlyBuffer(), 0, captured.size());
    }

    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        source = null;

        Throwable failure = null;
        for (var captured : capturedFiles.values()) {
            try {
                captured.close();
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        capturedFiles.clear();
        if (failure != null) {
            rethrow(failure);
        }
    }

    private void snapshotDirectory(String path) {
        var pending = new ArrayDeque<String>();
        pending.push(path);
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Raw source capture cancelled");
            }
            path = pending.pop();
            var sourcePath = path.isEmpty() ? null : path;
            var files = snapshotEntries(source.listFiles(sourcePath), "file", path);
            var childDirectories = snapshotEntries(source.listDirectories(sourcePath), "directory", path);
            var fileNames = new HashSet<>(Arrays.asList(files));
            for (var child : childDirectories) {
                if (fileNames.contains(child)) {
                    throw new IllegalArgumentException("File/directory collision: " + join(path, child));
                }
            }
            if (directories.put(path, new DirectoryEntries(files, childDirectories)) != null) {
                throw new IllegalArgumentException("Duplicate directory: " + path);
            }
            for (var file : files) {
                var fullPath = join(path, file);
                if (!sourceFiles.add(fullPath)) {
                    throw new IllegalArgumentException("Duplicate file: " + fullPath);
                }
            }
            for (var child : childDirectories) {
                pending.push(join(path, child));
            }
        }
    }

    private static String[] snapshotEntries(String[] entries, String kind, String parent) {
        var result = Objects.requireNonNull(entries, kind + " entries").clone();
        var unique = new HashSet<String>();
        for (var entry : result) {
            validateSegment(entry);
            if (!unique.add(entry)) {
                throw new IllegalArgumentException("Duplicate " + kind + ": " + join(parent, entry));
            }
        }
        Arrays.sort(result);
        return result;
    }

    private static String directoryPath(@Nullable String path) {
        return path == null || path.isEmpty() ? "" : portablePath(path);
    }

    private static String portablePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
            || isWindowsAbsolute(path)) {
            throw new IllegalArgumentException("Invalid portable path: " + path);
        }
        for (var segment : path.split("/", -1)) {
            validateSegment(segment);
        }
        return path;
    }

    private static void validateSegment(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")
            || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid portable path segment: " + segment);
        }
        validateSurrogates(segment);
    }

    private static void validateSurrogates(String value) {
        for (var index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("Portable path contains invalid UTF-16");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Portable path contains invalid UTF-16");
            }
        }
    }

    private static boolean isWindowsAbsolute(String path) {
        return path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
            && path.charAt(2) == '/';
    }

    private static String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private void checkOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Capture data has been closed");
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Failed to close capture data", failure);
    }

    private record DirectoryEntries(String[] files, String[] directories) {
    }

    private enum State {
        CAPTURING, FROZEN, CLOSED
    }
}
