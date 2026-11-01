package com.elfmcys.ysm.network;

import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import net.minecraftforge.network.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;

import java.util.function.Supplier;

/** Validates and publishes one complete inbound message before invoking its handler. */
final class ProtocolInbound {
    private ProtocolInbound() {}

    static boolean accepts(int messageId, MessageDirection direction) {
        return ProtocolMessages.REGISTRY.find(messageId).filter(spec -> spec.direction() == direction).isPresent();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void dispatch(FrameCodec.DecodedFrame frame, ProtocolMessageSpec<?> spec,
        Supplier<NetworkEvent.Context> context) {
        if (frame.messageId() != spec.id()) {
            throw new IllegalArgumentException("Frame and protocol spec ids do not match");
        }
        dispatchTyped(frame, (ProtocolMessageSpec)spec, context);
    }

    private static <T extends ProtoMessage<T>> void dispatchTyped(FrameCodec.DecodedFrame frame,
        ProtocolMessageSpec<T> spec, Supplier<NetworkEvent.Context> context) {
        var protobuf = frame.protobuf();
        var attachment = frame.attachment();
        if (protobuf.size() > spec.maxEncodedBytes()) {
            throw new IllegalArgumentException("Protobuf exceeds its message limit");
        }
        spec.attachmentPolicy().validate(attachment.size());
        var message = ProtocolBuffer.parse(protobuf, spec.parser());
        NetworkPayload<T> payload = attachment.size() == 0 ? NetworkPayload.protobuf(message)
            : NetworkPayload.withRaw(message, attachment.share());
        try {
            spec.handler().handle(payload, context);
        } catch (Throwable error) {
            payload.close();
            throw error;
        }
    }
}
