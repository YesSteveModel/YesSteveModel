package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import us.hebi.quickbuf.ProtoMessage;

/**
 * Produces bounded fragments from one owned byte, lease or file-range capability.
 *
 * <p><b>Ownership:</b> Successful construction owns its source until idempotent close. The caller owns
 * the packet until dispatch admission succeeds. Cancel seals production eligibility but does not close
 * a source still borrowed by dispatch. Close also invokes the request's release hook, even if source close fails.
 *
 * <p><b>Failure:</b> Source admission does not guarantee later range I/O. Production failure belongs
 * to the accepted request and must not become legal absence or a successful send.
 *
 * <p><b>Seam:</b> [range production failure](docs/architecture/network/overview.md#失败归属).
 */
final class RangePacket implements CancellablePacket {
    static final int FRAGMENT_BYTES = 24 * 1024;

    private final Source source;
    private final int size;
    private final int fragmentCount;
    private final FragmentFactory fragments;
    private final Runnable closeHook;
    private final Consumer<RuntimeException> productionFailure;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    static RangePacket bytes(byte[] bytes, FragmentFactory fragments, Runnable closeHook,
        Consumer<RuntimeException> productionFailure) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Byte range packet is empty");
        }
        return new RangePacket(new BytesSource(bytes.clone()), fragments, closeHook, productionFailure);
    }

    static RangePacket lease(ServerResourceRuntime.BytesLease lease, FragmentFactory fragments, Runnable closeHook,
        Consumer<RuntimeException> productionFailure) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(fragments, "fragments");
        Objects.requireNonNull(closeHook, "closeHook");
        Objects.requireNonNull(productionFailure, "productionFailure");
        var size = lease.size();
        if (size <= 0) {
            throw new IllegalArgumentException("Leased byte range packet is empty");
        }
        return new RangePacket(new LeaseSource(lease, size), fragments, closeHook, productionFailure);
    }

    static RangePacket file(Object sourceLease, Path path, long fileOffset, int size, FragmentFactory fragments,
        Runnable closeHook, Consumer<RuntimeException> productionFailure) {
        if (fileOffset < 0 || size <= 0) {
            throw new IllegalArgumentException("Invalid file-range packet bounds");
        }
        Math.addExact(fileOffset, size);
        return new RangePacket(new FileSource(sourceLease, path, fileOffset, size), fragments, closeHook,
            productionFailure);
    }

    private RangePacket(Source source, FragmentFactory fragments, Runnable closeHook,
        Consumer<RuntimeException> productionFailure) {
        this.source = Objects.requireNonNull(source, "source");
        size = source.size();
        this.fragments = Objects.requireNonNull(fragments, "fragments");
        this.closeHook = Objects.requireNonNull(closeHook, "closeHook");
        this.productionFailure = Objects.requireNonNull(productionFailure, "productionFailure");
        fragmentCount = Math.toIntExact((size + (long)FRAGMENT_BYTES - 1) / FRAGMENT_BYTES);
    }

    @Override
    public int fragmentCount() {
        return fragmentCount;
    }

    @Override
    public long estimateFrameBytes(int index) {
        var relativeOffset = Math.multiplyExact(index, FRAGMENT_BYTES);
        var length = fragmentSize(index);
        var message = fragments.message(relativeOffset, relativeOffset + length == size);
        return 32L + message.getSerializedSize() + length;
    }

    @Override
    public OutboundFrame buildFragment(int index) {
        var relativeOffset = Math.multiplyExact(index, FRAGMENT_BYTES);
        var length = fragmentSize(index);
        var message = fragments.message(relativeOffset, relativeOffset + length == size);
        var attachment = source.read(relativeOffset, length);
        try (var protobuf = ProtocolBuffer.serialize(message)) {
            var spec = ProtocolMessages.REGISTRY.find(message.getClass())
                .orElseThrow(() -> new IllegalArgumentException("Unregistered resource fragment message"));
            return FrameCodec.encode(spec.id(), protobuf, attachment);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void productionFailed(RuntimeException failure) {
        productionFailure.accept(Objects.requireNonNull(failure, "failure"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelled.set(true);
        RuntimeException failure = null;
        try {
            source.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            closeHook.run();
        } catch (RuntimeException callbackFailure) {
            if (failure == null) {
                failure = callbackFailure;
            } else {
                failure.addSuppressed(callbackFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private int fragmentSize(int index) {
        if (index < 0 || index >= fragmentCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return Math.min(FRAGMENT_BYTES, size - index * FRAGMENT_BYTES);
    }

    @FunctionalInterface
    interface FragmentFactory {
        ProtoMessage<?> message(long offset, boolean finalFragment);
    }

    private interface Source extends AutoCloseable {
        int size();

        SharedArrayBuffer read(int offset, int length);

        @Override
        void close();
    }

    private static final class BytesSource implements Source {
        private SharedArrayBuffer bytes;

        private BytesSource(byte[] bytes) {
            this.bytes = SharedArrayBuffer.adopt(ArrayBuffer.move(bytes));
        }

        @Override
        public int size() {
            return requireOpen().size();
        }

        @Override
        public SharedArrayBuffer read(int offset, int length) {
            return requireOpen().ownedSlice(offset, length);
        }

        @Override
        public void close() {
            bytes = null;
        }

        private SharedArrayBuffer requireOpen() {
            if (bytes == null) {
                throw new IllegalStateException("Byte range source is closed");
            }
            return bytes;
        }
    }

    private static final class LeaseSource implements Source {
        private ServerResourceRuntime.BytesLease lease;
        private final int size;

        private LeaseSource(ServerResourceRuntime.BytesLease lease, int size) {
            this.lease = lease;
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public SharedArrayBuffer read(int offset, int length) {
            return requireOpen().share(offset, length);
        }

        @Override
        public void close() {
            var current = lease;
            lease = null;
            if (current != null) {
                current.close();
            }
        }

        private ServerResourceRuntime.BytesLease requireOpen() {
            if (lease == null) {
                throw new IllegalStateException("Leased byte range source is closed");
            }
            return lease;
        }
    }

    private static final class FileSource implements Source {
        private Object sourceLease;
        private final Path path;
        private final long fileOffset;
        private final int size;

        private FileSource(Object sourceLease, Path path, long fileOffset, int size) {
            this.sourceLease = Objects.requireNonNull(sourceLease, "sourceLease");
            this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            this.fileOffset = fileOffset;
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public SharedArrayBuffer read(int offset, int length) {
            var keepAlive = requireOpen();
            try (var scope = ArrayBuffer.allocateWithScope(length)) {
                try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                    var target = scope.get().nio();
                    var position = Math.addExact(fileOffset, offset);
                    channel.position(position);
                    while (target.hasRemaining()) {
                        var read = channel.read(target);
                        if (read < 0) {
                            throw new EOFException("Accepted asset file was truncated: " + path);
                        }
                    }
                }
                return SharedArrayBuffer.adopt(scope.release());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            } finally {
                Reference.reachabilityFence(keepAlive);
            }
        }

        @Override
        public void close() {
            var keepAlive = sourceLease;
            sourceLease = null;
            Reference.reachabilityFence(keepAlive);
        }

        private Object requireOpen() {
            if (sourceLease == null) {
                throw new IllegalStateException("File range source is closed");
            }
            return sourceLease;
        }
    }
}
