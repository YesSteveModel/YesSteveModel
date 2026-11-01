package com.elfmcys.ysm.natives.buffer;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Owned;

import java.nio.ByteBuffer;

/**
 * Encodes the bounded input and owning output carriers used by synchronous JNI calls.
 *
 * <p>Seam: implements the carrier protocol in the
 * [buffer handoff](docs/architecture/native-runtime/implementation/jni-and-memory.md#buffer-传递).
 * Domain callers use typed native wrappers instead of treating these carriers as persistent handles.
 */
public class BufferArgument {
    /**
     * <p>Requires: the caller retains the original buffer owner until JNI returns and excludes
     * release, reallocation, and conflicting writes for that call.
     *
     * <p>Ownership: captures the array offset/size or direct NIO position/remaining, without copying bytes.
     * The returned carrier does not retain the original owner or freeze its contents.
     *
     * @return one-call carrier; packing leaves the source cursor unchanged
     * @throws IllegalArgumentException if buf has no supported array or native representation
     */
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

    /**
     * <p>Requires: buf is an owning result of the native output protocol.
     * A direct buffer must be the allocator's base view accepted by NativeHeapBuffer.adopt().
     *
     * <p>Ownership: transfers the result into Storage. The receiver closes Storage or transfers
     * that entire Storage to a GC owner. Direct-buffer adoption failure releases its allocation.
     *
     * @return owning Storage
     * @throws IllegalArgumentException if the result carrier has no supported representation
     */
    @Owned
    public static BufferStorage unpackOutput(Object buf) {
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

    /** Per-call representation and range; this record is not a backing owner. */
    public record Input(Object obj, long flags) {
    }
}
