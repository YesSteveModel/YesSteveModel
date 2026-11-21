package com.elfmcys.ysm.benchmark;

import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ReadHandleProbe {
    private static final List<Handle> HANDLES = new CopyOnWriteArrayList<>();
    private static final long[] SOURCE_CALLS = new long[5];
    private static long capturedBytes;
    private static long archiveMemberBytes;

    public static void captured(Object capture) {
        try {
            var field = capture.getClass().getDeclaredField("capturedFiles");
            field.setAccessible(true);
            for (var buffer : ((Map<?, ?>)field.get(capture)).values())
                capturedBytes += ((com.elfmcys.ysm.buffer.NativeBuffer)buffer).size();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static void archiveMember(Object buffer) {
        if (buffer != null)
            archiveMemberBytes += ((com.elfmcys.ysm.buffer.NativeBuffer)buffer).size();
    }

    public static long capturedBytes() {
        return capturedBytes;
    }

    public static long archiveMemberBytes() {
        return archiveMemberBytes;
    }

    public static void sourceCall(int kind) {
        SOURCE_CALLS[kind]++;
    }

    public static long sourceCalls(int kind) {
        return SOURCE_CALLS[kind];
    }

    public static void resetSources() {
        java.util.Arrays.fill(SOURCE_CALLS, 0);
        capturedBytes = 0;
        archiveMemberBytes = 0;
    }

    private static final List<Handle> ALL_HANDLES = new CopyOnWriteArrayList<>();
    private static final List<Map<String, Object>> QUERIES = new CopyOnWriteArrayList<>();
    private static final Map<Path, String> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

    public static void fail(Path path, String failure) {
        if (failure == null)
            FAILURES.remove(path.toAbsolutePath().normalize());
        else
            FAILURES.put(path.toAbsolutePath().normalize(), failure);
    }

    public static List<Map<String, Object>> queries() {
        return List.copyOf(QUERIES);
    }

    private static void query(String operation, Path path) {
        QUERIES.add(Map.of("operation", operation, "path", path.toAbsolutePath().normalize().toString(),
            "injectedFailure", FAILURES.getOrDefault(path.toAbsolutePath().normalize(), ""), "stack",
            StackWalker.getInstance()
                .walk(frames -> frames
                    .filter(frame -> frame.getClassName().startsWith("com.elfmcys.ysm.")
                        && !frame.getClassName().contains("ReadHandle"))
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName()).limit(32).toList())));
    }

    private static void opening(Path path) throws IOException {
        query("open", path);
        var failure = FAILURES.getOrDefault(path.toAbsolutePath().normalize(), "");
        switch (failure) {
            case "missing" -> throw new NoSuchFileException(path.toString());
            case "access" -> throw new AccessDeniedException(path.toString());
            case "io" -> throw new IOException("Injected cache I/O failure");
            case "interrupted" -> throw new InterruptedIOException("Injected cache interruption");
            case "closed" -> throw new ClosedChannelException();
            case "cancelled" -> throw new java.util.concurrent.CancellationException("Injected cache cancellation");
            case "oom" -> throw new OutOfMemoryError("Injected cache allocation failure");
            case "defect" -> throw new IllegalStateException("Injected cache program defect");
        }
    }

    public static <A extends java.nio.file.attribute.BasicFileAttributes> A readAttributes(Path path, Class<A> type,
        LinkOption... options) throws IOException {
        query("readAttributes", path);
        return Files.readAttributes(path, type, options);
    }

    public static Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException {
        query("readAttributes", path);
        return Files.readAttributes(path, attributes, options);
    }

    public static Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
        query("getAttribute", path);
        return Files.getAttribute(path, attribute, options);
    }

    public static boolean exists(Path path, LinkOption... options) {
        query("exists", path);
        return Files.exists(path, options);
    }

    public static boolean isRegularFile(Path path, LinkOption... options) {
        query("isRegularFile", path);
        return Files.isRegularFile(path, options);
    }

    public static boolean isDirectory(Path path, LinkOption... options) {
        query("isDirectory", path);
        return Files.isDirectory(path, options);
    }

    public static boolean isReadable(Path path) {
        query("isReadable", path);
        return Files.isReadable(path);
    }

    public static boolean isWritable(Path path) {
        query("isWritable", path);
        return Files.isWritable(path);
    }

    public static long size(Path path) throws IOException {
        query("size", path);
        return Files.size(path);
    }

    public static java.nio.file.attribute.FileTime getLastModifiedTime(Path path, LinkOption... options)
        throws IOException {
        query("getLastModifiedTime", path);
        return Files.getLastModifiedTime(path, options);
    }

    public static Set<java.nio.file.attribute.PosixFilePermission> getPosixFilePermissions(Path path,
        LinkOption... options) throws IOException {
        query("getPosixFilePermissions", path);
        return Files.getPosixFilePermissions(path, options);
    }

    public static java.util.stream.Stream<Path> list(Path path) throws IOException {
        query("list", path);
        return Files.list(path);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path path) throws IOException {
        query("newDirectoryStream", path);
        return Files.newDirectoryStream(path);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path path, String glob) throws IOException {
        query("newDirectoryStream", path);
        return Files.newDirectoryStream(path, glob);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path path, DirectoryStream.Filter<? super Path> filter)
        throws IOException {
        query("newDirectoryStream", path);
        return Files.newDirectoryStream(path, filter);
    }

    public static Path toRealPath(Path path, LinkOption... options) throws IOException {
        query("toRealPath", path);
        return path.toRealPath(options);
    }

    public static void initialize() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var output = System.getProperty("ysm.readHandles.output");
            if (output != null) {
                try {
                    Files.writeString(Path.of(output),
                        new GsonBuilder().setPrettyPrinting().create().toJson(snapshot(ALL_HANDLES)));
                    Files.writeString(Path.of(output + ".queries.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(queries()));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        }, "read-handle-probe"));
    }

    public static List<Map<String, Object>> snapshot() {
        return snapshot(HANDLES);
    }

    private static List<Map<String, Object>> snapshot(List<Handle> handles) {
        return handles.stream()
            .map(handle -> Map.<String, Object>of("path", handle.path, "stack", handle.stack, "closed", handle.closed,
                "bytes", handle.bytes, "closeCalls", handle.closeCalls, "ranges", List.copyOf(handle.ranges)))
            .toList();
    }

    public static void clear() {
        if (HANDLES.stream().anyMatch(handle -> !handle.closed))
            throw new IllegalStateException("A previous read handle is still open");
        HANDLES.clear();
    }

    public static FileChannel open(Path path, OpenOption... options) throws IOException {
        opening(path);
        return track(path, Arrays.asList(options), FileChannel.open(path, options));
    }

    public static FileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes)
        throws IOException {
        opening(path);
        return track(path, options, FileChannel.open(path, options, attributes));
    }

    private static FileChannel track(Path path, Collection<? extends OpenOption> options, FileChannel physical) {
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND))
            return physical;
        try {
            return new TrackedChannel(physical, new Handle(path));
        } catch (RuntimeException | Error failure) {
            try {
                physical.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static SeekableByteChannel newByteChannel(Path path, OpenOption... options) throws IOException {
        opening(path);
        var channel = Files.newByteChannel(path, options);
        return trackByteChannel(path, Arrays.asList(options), channel);
    }

    public static SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
        FileAttribute<?>... attributes) throws IOException {
        opening(path);
        var channel = Files.newByteChannel(path, options, attributes);
        return trackByteChannel(path, options, channel);
    }

    private static SeekableByteChannel trackByteChannel(Path path, Collection<? extends OpenOption> options,
        SeekableByteChannel physical) {
        if (physical instanceof FileChannel file)
            return track(path, options, file);
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND))
            return physical;
        try {
            var handle = new Handle(path);
            return (SeekableByteChannel)java.lang.reflect.Proxy.newProxyInstance(ReadHandleProbe.class.getClassLoader(),
                new Class<?>[] {SeekableByteChannel.class}, (proxy, method, arguments) -> {
                    try {
                        long offset = method.getName().equals("read") ? physical.position() : -1;
                        var value = method.invoke(physical, arguments);
                        if (method.getName().equals("close")) {
                            handle.closed = !physical.isOpen();
                            handle.closeCalls++;
                        }
                        if (method.getName().equals("read") && value instanceof Integer count && count > 0)
                            handle.read(offset, count);
                        return value == physical ? proxy : value;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        } catch (RuntimeException | Error failure) {
            try {
                physical.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        opening(path);
        var input = Files.newInputStream(path, options);
        try {
            var handle = new Handle(path);
            return new FilterInputStream(input) {
                private long position;

                @Override
                public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0)
                        handle.read(position++, 1);
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    if (count > 0) {
                        handle.read(position, count);
                        position += count;
                    }
                    return count;
                }

                @Override
                public long skip(long count) throws IOException {
                    long skipped = in.skip(count);
                    position += skipped;
                    return skipped;
                }

                @Override
                public void close() throws IOException {
                    in.close();
                    handle.closed = true;
                    handle.closeCalls++;
                }
            };
        } catch (RuntimeException | Error failure) {
            try {
                input.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static final class Handle {
        private final String path;
        private final List<String> stack;
        private volatile boolean closed;
        private volatile long bytes;
        private volatile int closeCalls;
        private final List<Range> ranges = new CopyOnWriteArrayList<>();

        private void read(long offset, long count) {
            bytes += count;
            ranges.add(new Range(offset, count));
        }

        private Handle(Path path) {
            this.path = path.toAbsolutePath().normalize().toString();
            stack = StackWalker.getInstance()
                .walk(frames -> frames
                    .filter(frame -> frame.getClassName().startsWith("com.elfmcys.ysm.")
                        && !frame.getClassName().contains("ReadHandle"))
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName()).limit(32).toList());
            HANDLES.add(this);
            ALL_HANDLES.add(this);
        }
    }

    private record Range(long offset, long bytes) {
    }

    private static final class TrackedChannel extends FileChannel {
        private final FileChannel physical;
        private final Handle handle;

        private TrackedChannel(FileChannel physical, Handle handle) {
            this.physical = physical;
            this.handle = handle;
        }

        private void beforeRead() throws IOException {
            if ("read-close".equals(FAILURES.get(Path.of(handle.path))))
                throw new IOException("Injected cache read failure with close failure");
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            beforeRead();
            long position = physical.position();
            int count = physical.read(target);
            if (count > 0)
                handle.read(position, count);
            return count;
        }

        @Override
        public int read(ByteBuffer target, long position) throws IOException {
            beforeRead();
            int count = physical.read(target, position);
            if (count > 0)
                handle.read(position, count);
            return count;
        }

        @Override
        public long read(ByteBuffer[] target, int offset, int length) throws IOException {
            beforeRead();
            long position = physical.position();
            long count = physical.read(target, offset, length);
            if (count > 0)
                handle.read(position, count);
            return count;
        }

        @Override
        public long position() throws IOException {
            return physical.position();
        }

        @Override
        public FileChannel position(long value) throws IOException {
            physical.position(value);
            return this;
        }

        @Override
        public long size() throws IOException {
            return physical.size();
        }

        @Override
        public FileChannel truncate(long value) throws IOException {
            physical.truncate(value);
            return this;
        }

        @Override
        public int write(ByteBuffer value) throws IOException {
            return physical.write(value);
        }

        @Override
        public int write(ByteBuffer value, long position) throws IOException {
            return physical.write(value, position);
        }

        @Override
        public long write(ByteBuffer[] value, int offset, int length) throws IOException {
            return physical.write(value, offset, length);
        }

        @Override
        public void force(boolean metadata) throws IOException {
            physical.force(metadata);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return physical.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
            return physical.transferFrom(source, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return physical.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return physical.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return physical.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            try {
                physical.close();
                if (java.util.Set.of("close", "read-close").contains(FAILURES.getOrDefault(Path.of(handle.path), "")))
                    throw new IOException("Injected cache close failure");
            } finally {
                handle.closed = !physical.isOpen();
                handle.closeCalls++;
            }
        }
    }
}
