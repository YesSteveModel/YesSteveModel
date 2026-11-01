package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.ArrayStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import us.hebi.quickbuf.ProtoMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-owned sequence of complete typed model-session publication messages. */
public final class SessionPublicationPacket implements LogicalPacket {
    private final List<Part> parts;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public SessionPublicationPacket(List<? extends ProtoMessage<?>> messages) {
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Session publication is empty");
        }
        var acquired = new ArrayList<Part>(messages.size());
        try {
            for (var message : messages) {
                var value = Objects.requireNonNull(message, "message");
                var spec = ProtocolMessages.REGISTRY.find(value.getClass())
                    .orElseThrow(() -> new IllegalArgumentException("Unregistered publication message"));
                if (spec.attachmentPolicy() != ProtocolMessageSpec.AttachmentPolicy.FORBIDDEN) {
                    throw new IllegalArgumentException("Session publication message must forbid attachments");
                }
                var protobuf = ProtocolBuffer.serialize(value);
                if (protobuf.size() >= FrameCodec.MAX_BODY_BYTES) {
                    protobuf.close();
                    throw new IllegalArgumentException("ITEM_TOO_LARGE");
                }
                acquired.add(new Part(spec.id(), protobuf));
            }
            parts = List.copyOf(acquired);
        } catch (Throwable error) {
            acquired.forEach(Part::close);
            throw error;
        }
    }

    @Override
    public int fragmentCount() {
        return parts.size();
    }

    @Override
    public long estimateFrameBytes(int index) {
        return part(index).protobuf.size() + 1L;
    }

    @Override
    public OutboundFrame buildFragment(int index) {
        var part = part(index);
        try (var attachment = ArrayBuffer.allocate(0)) {
            return FrameCodec.encode(part.messageId, part.protobuf, attachment);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void close() {
        parts.forEach(Part::close);
    }

    private Part part(int index) {
        if (index < 0 || index >= parts.size()) {
            throw new IndexOutOfBoundsException(index);
        }
        return parts.get(index);
    }

    private record Part(int messageId, ArrayStorage protobuf) {
        private void close() {
            protobuf.close();
        }
    }
}
