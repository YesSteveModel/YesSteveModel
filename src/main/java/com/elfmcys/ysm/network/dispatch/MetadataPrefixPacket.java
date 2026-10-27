package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One business-bounded metadata child encoded lazily from already-resident prefixes. */
final class MetadataPrefixPacket implements CancellablePacket {
    private static final int FRAGMENT_BYTES = 24 * 1024;

    private final long transferId;
    private final List<Part> parts;
    private final int fragmentCount;
    private final Runnable closeHook;
    private final Consumer<RuntimeException> productionFailure;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    MetadataPrefixPacket(long transferId, List<Source> sources, Runnable closeHook,
        Consumer<RuntimeException> productionFailure) {
        if (transferId == 0 || sources.isEmpty()) {
            throw new IllegalArgumentException("Metadata transfer must be non-empty");
        }
        this.transferId = transferId;
        this.closeHook = Objects.requireNonNull(closeHook, "closeHook");
        this.productionFailure = Objects.requireNonNull(productionFailure, "productionFailure");
        var acquired = new ArrayList<Part>(sources.size());
        var firstFragment = 0;
        try {
            for (var source : sources) {
                Objects.requireNonNull(source, "source");
                if (source.data.size() <= 0) {
                    throw new IllegalArgumentException("Metadata prefix is empty");
                }
                var count = Math.toIntExact((source.data.size() + (long)FRAGMENT_BYTES - 1) / FRAGMENT_BYTES);
                acquired.add(new Part(source.containerId.clone(), source.data.acquire(), firstFragment, count));
                firstFragment = Math.addExact(firstFragment, count);
            }
            parts = List.copyOf(acquired);
            fragmentCount = firstFragment;
        } catch (Throwable failure) {
            for (var part : acquired) {
                try {
                    part.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public int fragmentCount() {
        return fragmentCount;
    }

    @Override
    public long estimateFrameBytes(int index) {
        return 96L + fragmentSize(part(index), index);
    }

    @Override
    public OutboundFrame buildFragment(int index) {
        var part = part(index);
        var sequence = index - part.firstFragment;
        var offset = Math.multiplyExact(sequence, FRAGMENT_BYTES);
        var length = fragmentSize(part, index);
        final byte[] payload;
        try (var source = part.data.slice(offset, length).acquireArray()) {
            payload = new byte[length];
            System.arraycopy(source.array(), source.arrayOffset(), payload, 0, length);
        }
        var message = MetadataPrefixFragmentProto.newBuilder().setDataTransferId(transferId)
            .setContainerId(ByteBuffer.wrap(part.containerId)).setSequence(sequence)
            .setFinalFragment(sequence + 1 == part.fragmentCount).setPayload(ByteBuffer.wrap(payload)).build();
        try (var protobuf = ProtocolBuffer.serialize(message); var attachment = ArrayBuffer.allocate(0)) {
            return FrameCodec.encode(ProtocolMessages.METADATA_PREFIX_FRAGMENT_ID, protobuf, attachment);
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
        if (closed.compareAndSet(false, true)) {
            cancelled.set(true);
            RuntimeException failure = null;
            for (var part : parts) {
                try {
                    part.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
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
    }

    private Part part(int index) {
        if (index < 0 || index >= fragmentCount) {
            throw new IndexOutOfBoundsException(index);
        }
        var low = 0;
        var high = parts.size() - 1;
        while (low <= high) {
            var middle = low + (high - low) / 2;
            var candidate = parts.get(middle);
            if (index < candidate.firstFragment) {
                high = middle - 1;
            } else if (index >= candidate.firstFragment + candidate.fragmentCount) {
                low = middle + 1;
            } else {
                return candidate;
            }
        }
        throw new AssertionError("Metadata fragment index was not mapped");
    }

    private static int fragmentSize(Part part, int index) {
        var sequence = index - part.firstFragment;
        return Math.min(FRAGMENT_BYTES, part.data.size() - sequence * FRAGMENT_BYTES);
    }

    record Source(byte[] containerId, UniBuffer data) {
        Source {
            containerId = Objects.requireNonNull(containerId, "containerId").clone();
            Objects.requireNonNull(data, "data");
        }

        @Override
        public byte[] containerId() {
            return containerId.clone();
        }
    }

    private record Part(byte[] containerId, UniBuffer data, int firstFragment, int fragmentCount) {
        private void close() {
            data.close();
        }
    }
}
