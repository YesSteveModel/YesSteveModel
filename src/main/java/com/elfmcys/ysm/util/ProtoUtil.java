package com.elfmcys.ysm.util;

import com.elfmcys.ysm.buffer.ArrayStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

import java.io.IOException;

public class ProtoUtil {
    public static ProtoSink sink(ArrayBuffer buffer) {
        return ProtoSink.newInstance(buffer.array(), buffer.arrayOffset(), buffer.size());
    }

    /** Borrows input; the caller must keep its owner alive until all reads finish. */
    public static ProtoSource source(UniBuffer buffer) {
        if (buffer instanceof ArrayBuffer array)
            return ProtoSource.newInstance(array.array(), array.arrayOffset(), array.size());
        return ProtoSource.newDirectSource().setInput(buffer.nio());
    }

    public static byte[] serializeToArray(ProtoMessage<?> msg) throws IOException {
        var array = new byte[msg.getSerializedSize()];
        var sink = ProtoSink.newInstance(array);
        msg.writeTo(sink);
        return array;
    }

    public static ArrayStorage serializeToBuffer(ProtoMessage<?> message) throws IOException {
        var result = ArrayBuffer.allocate(message.getSerializedSize());
        try {
            message.writeTo(sink(result));
            return result;
        } catch (IOException | RuntimeException error) {
            result.close();
            throw error;
        }
    }
}
