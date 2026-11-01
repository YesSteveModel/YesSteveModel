package com.elfmcys.ysm.mock.classpath;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import us.hebi.quickbuf.ProtoMessage;

import java.util.Arrays;
import java.util.Objects;

/** Test-only bridge that delegates all typed encoding and parsing to the production wire. */
public final class ProductionWire {
    private ProductionWire() {}

    public static int registeredMessageCount() {
        return ProtocolMessages.REGISTRY.messages().size();
    }

    public static Encoded encode(ProtoMessage<?> message, byte[] attachment) {
        Objects.requireNonNull(message, "message");
        var spec = ProtocolMessages.REGISTRY.find(message.getClass()).orElseThrow(
            () -> new IllegalArgumentException("Unregistered production message: " + message.getClass().getName()));
        var rawAttachment = attachment == null ? new byte[0] : attachment;
        spec.attachmentPolicy().validate(rawAttachment.length);
        try (var protobuf = ProtocolBuffer.serialize(message); var attached = ArrayBuffer.move(rawAttachment);
            var frame = FrameCodec.encode(spec.id(), protobuf, attached)) {
            var bytes = frame.bytes();
            return new Encoded(bytes, spec.id(), spec.direction(), message.getClass().getName(), rawAttachment.length);
        }
    }

    public static <T extends ProtoMessage<T>> Decoded<T> decode(byte[] bytes, Class<T> type,
        MessageDirection direction) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(direction, "direction");
        @SuppressWarnings("unchecked")
        var spec = (ProtocolMessageSpec<T>)ProtocolMessages.REGISTRY.find(type)
            .orElseThrow(() -> new IllegalArgumentException("Unregistered production message: " + type.getName()));
        if (spec.direction() != direction) {
            throw new IllegalArgumentException("Message direction mismatch for " + type.getName());
        }
        try (var frame = FrameCodec.decode(bytes, id -> id == spec.id()); var array = frame.attachment().copyArray()) {
            var protobuf = frame.protobuf();
            spec.attachmentPolicy().validate(array.size());
            var payload = ProtocolBuffer.parse(protobuf, spec.parser());
            var copy = Arrays.copyOfRange(array.array(), array.arrayOffset(), array.arrayOffset() + array.size());
            return new Decoded<>(payload, copy, spec.id(), spec.direction(), type.getName());
        }
    }

    public static AnyDecoded decode(byte[] bytes, MessageDirection direction) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(direction, "direction");
        try (var frame = FrameCodec.decode(bytes,
            id -> ProtocolMessages.REGISTRY.find(id).filter(spec -> spec.direction() == direction).isPresent())) {
            var spec = ProtocolMessages.REGISTRY.find(frame.messageId()).orElseThrow();
            try (var array = frame.attachment().copyArray()) {
                var protobuf = frame.protobuf();
                spec.attachmentPolicy().validate(array.size());
                var payload = ProtocolBuffer.parse(protobuf, spec.parser());
                var copy = Arrays.copyOfRange(array.array(), array.arrayOffset(), array.arrayOffset() + array.size());
                return new AnyDecoded(payload, copy, spec.id(), spec.direction(), spec.messageType().getName());
            }
        }
    }

    public record Encoded(byte[] bytes, int messageId, MessageDirection direction, String messageType,
        int attachmentBytes) {
        public Encoded {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }
    }

    public record Decoded<T>(T message, byte[] attachment, int messageId, MessageDirection direction,
        String messageType) {
        public Decoded {
            attachment = Arrays.copyOf(attachment, attachment.length);
        }
    }

    public record AnyDecoded(ProtoMessage<?> message, byte[] attachment, int messageId, MessageDirection direction,
        String messageType) {
        public AnyDecoded {
            attachment = Arrays.copyOf(attachment, attachment.length);
        }
    }
}
