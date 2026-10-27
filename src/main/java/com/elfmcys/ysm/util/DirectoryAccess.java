package com.elfmcys.ysm.util;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Checked path operations. The caller keeps directory structure stable during each operation. */
public final class DirectoryAccess implements AutoCloseable {
    private final Path path;
    private final List<Entry> entries;
    private boolean closed;

    private DirectoryAccess(Path path, List<Entry> entries) {
        this.path = path;
        this.entries = entries;
    }

    public static DirectoryAccess open(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        var identity = readEntry(absolute, true);
        requireRealPath(absolute);
        return new DirectoryAccess(absolute, List.of(new Entry(absolute, identity)));
    }

    public Path label() { return path; }

    public Object identity() throws IOException {
        verify();
        return entries.get(entries.size() - 1).observation;
    }

    public void verify() throws IOException {
        requireOpen();
        for (var entry : entries) {
            if (!entry.observation.equals(readEntry(entry.path, true)))
                throw new IOException("Directory changed: " + entry.path);
        }
        // Resolving the deepest path covers linked ancestors without resolving every prefix again.
        requireRealPath(path);
    }

    public Snapshot snapshot() throws IOException {
        requireOpen();
        return new Snapshot(path, entries);
    }

    /** Retains observations, not a freshness guarantee or an open filesystem handle. */
    public static final class Snapshot {
        private final Path path;
        private final List<Entry> entries;

        private Snapshot(Path path, List<Entry> entries) {
            this.path = path;
            this.entries = entries;
        }

        public DirectoryAccess open() throws IOException {
            var directory = new DirectoryAccess(path, entries);
            directory.verify();
            return directory;
        }
    }

    public DirectoryAccess directory(String name, boolean create) throws IOException {
        requireSegment(name);
        return descend(List.of(name), create, false);
    }

    public DirectoryAccess directory(Path relative, boolean create) throws IOException {
        if (relative.isAbsolute()) throw new IOException("Expected a relative directory path");
        var names = new ArrayList<String>();
        for (var part : relative) {
            var name = part.toString();
            if (name.isEmpty()) continue;
            requireSegment(name);
            names.add(name);
        }
        return descend(names, create, false);
    }

    public DirectoryAccess createDirectory(String name) throws IOException {
        requireSegment(name);
        return descend(List.of(name), true, true);
    }

    private DirectoryAccess descend(List<String> names, boolean create, boolean exclusive) throws IOException {
        verify();
        var current = path;
        var observed = new ArrayList<>(entries);
        for (var name : names) {
            var child = current.resolve(name);
            Object identity;
            try {
                if (exclusive) Files.createDirectory(child);
                identity = readEntry(child, true);
            } catch (NoSuchFileException absent) {
                if (!create) throw absent;
                // An existing prefix can contain a junction; reject it before creating its missing tail.
                requireRealPath(current);
                try { Files.createDirectory(child); }
                catch (FileAlreadyExistsException raced) { if (exclusive) throw raced; }
                identity = readEntry(child, true);
            }
            observed.add(new Entry(child, identity));
            current = child;
        }
        requireRealPath(current);
        return new DirectoryAccess(current, List.copyOf(observed));
    }

    public FileChannel file(String name, boolean write, boolean create, boolean exclusive) throws IOException {
        var child = child(name);
        try {
            checkedEntry(child, false);
        } catch (NoSuchFileException absent) {
            if (!create) throw absent;
        }
        var options = new HashSet<OpenOption>();
        options.add(StandardOpenOption.READ);
        if (write) options.add(StandardOpenOption.WRITE);
        if (create) options.add(exclusive ? StandardOpenOption.CREATE_NEW : StandardOpenOption.CREATE);
        options.add(LinkOption.NOFOLLOW_LINKS);
        return FileChannel.open(child, options);
    }

    public Object fileIdentity(String name) throws IOException {
        var child = child(name);
        return checkedEntry(child, false);
    }

    public void move(String source, Object expected, DirectoryAccess target, String name) throws IOException {
        var from = child(source);
        if (!checkedEntry(from, false).equals(expected))
            throw new IOException("Staged filesystem entry changed: " + from);
        var to = target.child(name);
        try { checkedEntry(to, false); } catch (NoSuchFileException absent) {}
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(String name, Object expected, boolean directory) throws IOException {
        var child = child(name);
        if (!checkedEntry(child, directory).equals(expected))
            throw new IOException("Filesystem entry changed: " + child);
        Files.delete(child);
    }

    public List<String> names() throws IOException {
        verify();
        try (var children = Files.list(path)) {
            return children.map(p -> p.getFileName().toString()).toList();
        }
    }

    private Path child(String name) throws IOException {
        requireSegment(name);
        verify();
        return path.resolve(name);
    }

    private static void requireSegment(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
            || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Invalid filesystem segment");
    }

    private static Object readEntry(Path path, boolean directory) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!(directory ? attrs.isDirectory() : attrs.isRegularFile()) || attrs.isOther() || attrs.isSymbolicLink())
            throw new IOException("Unexpected filesystem entry type: " + path);
        return FileIdentity.observe(attrs);
    }

    private static Object checkedEntry(Path path, boolean directory) throws IOException {
        var identity = readEntry(path, directory);
        requireRealPath(path);
        return identity;
    }

    private static void requireRealPath(Path path) throws IOException {
        if (!path.equals(path.toRealPath())) throw new IOException("Linked filesystem path: " + path);
    }

    private void requireOpen() throws ClosedChannelException {
        if (closed) throw new ClosedChannelException();
    }

    @Override public void close() throws IOException { closed = true; }

    private record Entry(Path path, Object observation) {}

    public static Path ensureDirectory(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        try {
            Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            var parent = absolute.getParent();
            if (parent == null) throw absent;
            ensureDirectory(parent);
            try {
                Files.createDirectory(absolute);
            } catch (FileAlreadyExistsException raced) {
                // Another process may create the same tail; validate it before use.
            }
        }
        var real = absolute.toRealPath();
        if (!Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
            throw new IOException("Not a directory: " + absolute);
        }
        return real;
    }

    /** Resolves an existing prefix without treating dangling links as missing tails. */
    public static Path prospectiveRealPath(Path path) throws IOException {
        var current = path.toAbsolutePath().normalize();
        var tail = new ArrayDeque<Path>();
        while (true) {
            try {
                Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                var real = current.toRealPath();
                if (!Files.isDirectory(real)) throw new IOException("Not a directory: " + current);
                for (var part : tail) real = real.resolve(part);
                return real.normalize();
            } catch (NoSuchFileException missing) {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) throw missing;
                if (current.getParent() == null) throw missing;
                tail.addFirst(current.getFileName());
                current = current.getParent();
            }
        }
    }
}
