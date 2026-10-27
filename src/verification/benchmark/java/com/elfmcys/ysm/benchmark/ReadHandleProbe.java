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

    public static void initialize() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var output = System.getProperty("ysm.readHandles.output");
            if (output != null) {
                try {
                    Files.writeString(Path.of(output),
                        new GsonBuilder().setPrettyPrinting().create().toJson(snapshot()));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        }, "read-handle-probe"));
    }

    public static List<Map<String, Object>> snapshot() {
        return HANDLES.stream().map(handle -> Map.<String, Object>of("path", handle.path, "stack", handle.stack,
            "closed", handle.closed, "bytes", handle.bytes, "closeCalls", handle.closeCalls)).toList();
    }

    public static void clear() {
        if (HANDLES.stream().anyMatch(handle -> !handle.closed))
            throw new IllegalStateException("A previous read handle is still open");
        HANDLES.clear();
    }

    public static FileChannel open(Path path, OpenOption... options) throws IOException {
        return track(path, Arrays.asList(options), FileChannel.open(path, options));
    }

    public static FileChannel open(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes)
        throws IOException {
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
        var channel = Files.newByteChannel(path, options);
        return trackByteChannel(path, Arrays.asList(options), channel);
    }

    public static SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
        FileAttribute<?>... attributes) throws IOException {
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
                        var value = method.invoke(physical, arguments);
                        if (method.getName().equals("close")) {
                            handle.closed = !physical.isOpen();
                            handle.closeCalls++;
                        }
                        if (method.getName().equals("read") && value instanceof Integer count && count > 0)
                            handle.bytes += count;
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
        var input = Files.newInputStream(path, options);
        try {
            var handle = new Handle(path);
            return new FilterInputStream(input) {
                @Override
                public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0)
                        handle.bytes++;
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    if (count > 0)
                        handle.bytes += count;
                    return count;
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

        private Handle(Path path) {
            this.path = path.toAbsolutePath().normalize().toString();
            stack = StackWalker.getInstance()
                .walk(frames -> frames
                    .filter(frame -> frame.getClassName().startsWith("com.elfmcys.ysm.")
                        && !frame.getClassName().contains("ReadHandle"))
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName()).limit(32).toList());
            HANDLES.add(this);
        }
    }

    private static final class TrackedChannel extends FileChannel {
        private final FileChannel physical;
        private final Handle handle;

        private TrackedChannel(FileChannel physical, Handle handle) {
            this.physical = physical;
            this.handle = handle;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            int count = physical.read(target);
            if (count > 0)
                handle.bytes += count;
            return count;
        }

        @Override
        public int read(ByteBuffer target, long position) throws IOException {
            int count = physical.read(target, position);
            if (count > 0)
                handle.bytes += count;
            return count;
        }

        @Override
        public long read(ByteBuffer[] target, int offset, int length) throws IOException {
            long count = physical.read(target, offset, length);
            if (count > 0)
                handle.bytes += count;
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
            } finally {
                handle.closed = !physical.isOpen();
                handle.closeCalls++;
            }
        }
    }
}
