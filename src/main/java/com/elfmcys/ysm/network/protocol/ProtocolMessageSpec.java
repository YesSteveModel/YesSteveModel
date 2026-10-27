package com.elfmcys.ysm.network.protocol;

import com.elfmcys.ysm.network.NetworkPayload;
import net.minecraftforge.network.NetworkEvent;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/** The single parse, direction, size, attachment and handler binding for one wire message. */
public record ProtocolMessageSpec<T extends ProtoMessage<T>>(int id, MessageDirection direction, Class<T> messageType,
    int maxEncodedBytes, AttachmentPolicy attachmentPolicy, Parser<T> parser, Handler<T> handler) {
    public ProtocolMessageSpec {
        if (id < 0 || id > 127) {
            throw new IllegalArgumentException("Protocol message id must be in [0, 127]");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(messageType, "messageType");
        if (maxEncodedBytes <= 0) {
            throw new IllegalArgumentException("Maximum encoded size must be positive");
        }
        Objects.requireNonNull(attachmentPolicy, "attachmentPolicy");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(handler, "handler");
    }

    public enum AttachmentPolicy {
        FORBIDDEN, REQUIRED, CONDITIONAL;

        public void validate(int size) {
            if (size < 0) {
                throw new IllegalArgumentException("Attachment size must not be negative");
            }
            if (this == FORBIDDEN && size != 0) {
                throw new IllegalArgumentException("Message forbids an attachment");
            }
            if (this == REQUIRED && size == 0) {
                throw new IllegalArgumentException("Message requires an attachment");
            }
        }
    }

    @FunctionalInterface
    public interface Parser<T extends ProtoMessage<T>> {
        T parse(ProtoSource source) throws IOException;
    }

    @FunctionalInterface
    public interface Handler<T extends ProtoMessage<T>> {
        void handle(NetworkPayload<T> payload, Supplier<NetworkEvent.Context> context);
    }
}
