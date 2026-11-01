package com.elfmcys.ysm.network;

import com.elfmcys.ysm.buffer.ArrayStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.IOException;
import java.lang.ref.Reference;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;

public final class ProtocolBuffer {
    private ProtocolBuffer() {}

    public static ArrayStorage serialize(ProtoMessage<?> message) {
        var result = ArrayBuffer.allocate(message.getSerializedSize());
        try {
            message.writeTo(ProtoSink.newInstance(result.array(), result.arrayOffset(), result.size()));
            return result;
        } catch (IOException | RuntimeException error) {
            result.close();
            throw new IllegalArgumentException("Failed to encode protobuf message", error);
        }
    }

    public static <T extends ProtoMessage<T>> T parse(UniBuffer bytes, ProtocolMessageSpec.Parser<T> parser) {
        try {
            var source = ProtoUtil.source(bytes);
            var message = parser.parse(source);
            if (!source.isAtEnd()) {
                throw new IllegalArgumentException("Protobuf parser left trailing bytes");
            }
            return message;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid protobuf payload", error);
        } finally {
            Reference.reachabilityFence(bytes);
        }
    }
}
