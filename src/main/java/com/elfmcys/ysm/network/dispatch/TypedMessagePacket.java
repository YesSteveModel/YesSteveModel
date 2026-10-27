package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import us.hebi.quickbuf.ProtoMessage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Dispatch-private transmission for one complete typed outcome without an attachment. */
final class TypedMessagePacket implements CancellablePacket {
    private final int messageId;
    private final ProtoMessage<?> message;
    private final Runnable closeHook;
    private final Consumer<RuntimeException> productionFailure;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    TypedMessagePacket(ProtoMessage<?> message, Runnable closeHook, Consumer<RuntimeException> productionFailure) {
        this.message = Objects.requireNonNull(message, "message");
        var spec = ProtocolMessages.REGISTRY.find(message.getClass())
            .orElseThrow(() -> new IllegalArgumentException("Unregistered resource outcome message"));
        if (spec.attachmentPolicy() != ProtocolMessageSpec.AttachmentPolicy.CONDITIONAL) {
            throw new IllegalArgumentException("Typed outcome must allow an empty attachment");
        }
        messageId = spec.id();
        this.closeHook = Objects.requireNonNull(closeHook, "closeHook");
        this.productionFailure = Objects.requireNonNull(productionFailure, "productionFailure");
    }

    @Override
    public int fragmentCount() {
        return 1;
    }

    @Override
    public long estimateFrameBytes(int index) {
        requireIndex(index);
        return 16L + message.getSerializedSize();
    }

    @Override
    public OutboundFrame buildFragment(int index) {
        requireIndex(index);
        try (var protobuf = ProtocolBuffer.serialize(message); var attachment = ArrayBuffer.allocate(0)) {
            return FrameCodec.encode(messageId, protobuf, attachment);
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
            closeHook.run();
        }
    }

    private static void requireIndex(int index) {
        if (index != 0) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
