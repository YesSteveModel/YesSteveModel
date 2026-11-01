package com.elfmcys.ysm.network;

import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import net.minecraftforge.network.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;

import java.util.function.Supplier;

/**
 * Admits a complete frame as a fixed typed message and transfers its payload to the registered handler.
 *
 * <p><b>Ensures:</b> Handler entry follows message-ID matching, encoded-size and attachment-policy
 * checks, and complete protobuf parsing. The handler can trust those structural guarantees; business
 * validation, exact connection identity and protected effects remain its separate responsibilities.
 *
 * <p><b>Execution:</b> Dispatch invokes the handler on the caller's networking entry and does not grant
 * main-thread domain access. A handler that defers work must retain the required backing and capture
 * its source connection. A thrown handler failure closes the payload; normal return transfers closure duty.
 *
 * <p><b>Seam:</b> [frame admission versus domain authority](docs/architecture/network/overview.md#接收准入与执行权限).
 */
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
