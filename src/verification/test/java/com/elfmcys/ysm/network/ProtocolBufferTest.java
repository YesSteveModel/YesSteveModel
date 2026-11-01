package com.elfmcys.ysm.network;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolBufferTest {
    @ParameterizedTest
    @EnumSource(BufferType.class)
    void parserBorrowsTheSuppliedInputForBothStorageTypes(BufferType type) {
        var backing = allocate(type, 2);
        backing.put(new byte[] {8, 1}).clear();
        try (var input = UniBuffer.move(backing)) {
            var parsed = ProtocolBuffer.parse(input, source -> {
                assertEquals(8, source.readTag());
                input.nio().put(1, (byte)7);
                return MetadataPrefixRequestProto.newBuilder().setDataTransferId(source.readInt32()).build();
            });

            assertEquals(7, parsed.dataTransferId());
        }
    }

    @ParameterizedTest
    @EnumSource(BufferType.class)
    void parsesAnExactRangeIntoAnIndependentMessage(BufferType type) {
        var expected = MetadataPrefixRequestProto.newBuilder().setDataTransferId(42)
            .addContainerIds(ByteBuffer.wrap(new byte[] {1, 2, 3})).build();
        final MetadataPrefixRequestProto parsed;
        try (var wire = ProtocolBuffer.serialize(expected)) {
            var backing = allocate(type, wire.size() + 4);
            backing.position(2).put(wire.nio()).position(2).limit(2 + wire.size());
            try (var input = UniBuffer.move(backing)) {
                parsed = ProtocolBuffer.parse(input, MetadataPrefixRequestProto::parseFrom);
                var data = input.nio();
                while (data.hasRemaining())
                    data.put((byte)0);
            }
        }

        assertEquals(expected, parsed);
    }

    @ParameterizedTest
    @EnumSource(BufferType.class)
    void rejectsTruncatedFieldsEvenWhenTheAllocationContainsTheirMissingBytes(BufferType type) {
        var backing = allocate(type, 7);
        backing.put(new byte[] {99, 18, 3, 1, 2, 3, 99}).position(1).limit(5);
        try (var input = UniBuffer.move(backing)) {
            assertThrows(IllegalArgumentException.class,
                () -> ProtocolBuffer.parse(input, MetadataPrefixRequestProto::parseFrom));
        }
    }

    @ParameterizedTest
    @EnumSource(BufferType.class)
    void rejectsParsersThatLeaveTrailingBytes(BufferType type) {
        var backing = allocate(type, 4);
        backing.put(new byte[] {8, 1, 8, 2}).clear();
        try (var input = UniBuffer.move(backing)) {
            assertThrows(IllegalArgumentException.class, () -> ProtocolBuffer.parse(input, source -> {
                source.readTag();
                source.readInt32();
                return MetadataPrefixRequestProto.newBuilder().setDataTransferId(1).build();
            }));
        }
    }

    private static ByteBuffer allocate(BufferType type, int size) {
        return type == BufferType.NATIVE ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    }
}
