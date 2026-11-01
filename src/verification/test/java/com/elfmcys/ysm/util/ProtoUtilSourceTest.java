package com.elfmcys.ysm.util;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.proto.mixel.manifest.info.PropertiesProto;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoUtilSourceTest {
    @ParameterizedTest
    @EnumSource(BufferType.class)
    void borrowedSourceReadsTheCurrentBytesInItsExactRange(BufferType type) throws Exception {
        var backing = allocate(type, 6);
        backing.put(new byte[] {99, 99, 8, 1, 99, 99}).position(2).limit(4);
        var input = UniBuffer.borrow(backing);
        try {
            var source = ProtoUtil.source(input);
            backing.put(3, (byte)7);

            assertEquals(8, source.readTag());
            assertEquals(7, source.readInt32());
            assertTrue(source.isAtEnd());
            assertEquals(2, backing.position());
            assertEquals(4, backing.limit());
        } finally {
            Reference.reachabilityFence(input);
        }
    }

    @ParameterizedTest
    @EnumSource(BufferType.class)
    void parsedFieldsOutliveTheModifiedAndClosedInputOwner(BufferType type) throws Exception {
        var expected = PropertiesProto.newBuilder().setModelId(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})).setFree(true)
            .setOriginVer("2.0").build();
        var wire = ProtoUtil.serializeToArray(expected);
        var backing = allocate(type, wire.length + 6);
        backing.position(3).put(wire).position(3).limit(3 + wire.length);
        final PropertiesProto parsed;
        try (var owner = UniBuffer.move(backing)) {
            var source = ProtoUtil.source(owner);
            try {
                parsed = PropertiesProto.parseFrom(source);
                assertTrue(source.isAtEnd());
            } finally {
                Reference.reachabilityFence(owner);
            }
            var data = owner.nio();
            while (data.hasRemaining())
                data.put((byte)0);
        }

        assertEquals(expected, parsed);
    }

    private static ByteBuffer allocate(BufferType type, int size) {
        return type == BufferType.NATIVE ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    }
}
