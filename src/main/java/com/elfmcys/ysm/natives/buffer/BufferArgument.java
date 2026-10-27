package com.elfmcys.ysm.natives.buffer;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Owned;

import java.nio.ByteBuffer;

public class BufferArgument {
    public static Input packInput(UniBuffer buf) {
        if (buf instanceof ArrayBuffer arrayBuf) {
            long flags = ((long)arrayBuf.arrayOffset() << 32) | ((long)arrayBuf.size() & 0xFFFF_FFFFL);
            return new Input(arrayBuf.array(), flags);
        } else if (buf instanceof NativeBuffer nativeBuf) {
            var nio = nativeBuf.nio();
            long flags = Long.MIN_VALUE | ((long)nio.position() << 32) | ((long)nio.remaining() & 0xFFFF_FFFFL);
            return new Input(nio, flags);
        }
        throw new IllegalArgumentException("Unknown UniBuffer type: " + buf.getClass().getName());
    }

    @Owned
    public static UniBuffer unpackOutput(Object buf) {
        if (buf instanceof byte[] array) {
            return ArrayBuffer.move(array);
        } else if (buf instanceof ByteBuffer nio) {
            if (nio.isDirect()) {
                return NativeHeapBuffer.adopt(nio);
            } else {
                return ArrayBuffer.move(nio);
            }
        } else {
            throw new IllegalArgumentException("Unknown data type");
        }
    }

    public record Input(Object obj, long flags) {
    }
}
