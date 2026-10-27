package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.MissingPreviewEntryProto;
import com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MissingPreviewPacketTest {
    @Test
    void compressibleCompleteQueryStillUsesOneFrame() {
        var entries = new java.util.ArrayList<MissingPreviewEntryProto>();
        for (var index = 0; index < 12; index++) {
            entries.add(
                entry(index + 1).toBuilder().setHierarchyPath("local:custom/fixture/" + "a".repeat(4_000)).build());
        }
        var combined = MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setSequence(0)
            .setFinalFragment(true).addAllEntries(entries).build();
        assertTrue(combined.getSerializedSize() >= FrameCodec.MAX_BODY_BYTES);
        try (var packet = new MissingPreviewPacket(1, entries, () -> {
        }, failure -> fail(failure))) {
            assertEquals(1, packet.fragmentCount());
            try (var frame = packet.buildFragment(0); var decoded = FrameCodec.decode(frame.bytes(), ignored -> true)) {
                var result = ProtocolBuffer.parse(decoded.protobuf(), MissingPreviewFragmentProto::parseFrom);
                assertEquals(frame.size(), packet.estimateFrameBytes(0));
                assertEquals(entries, result.entries());
                assertTrue(result.finalFragment());
            }
        }
    }

    @Test
    void largeQueryHasCompleteOrderedCoverageAndOneFinal() {
        var random = new java.util.Random(81);
        var entries = new java.util.ArrayList<MissingPreviewEntryProto>();
        for (var index = 0; index < 500; index++) {
            var bytes = new byte[320];
            random.nextBytes(bytes);
            entries.add(entry(index).toBuilder()
                .setHierarchyPath("local:custom/fixture/" + java.util.HexFormat.of().formatHex(bytes)).build());
        }
        try (var packet = new MissingPreviewPacket(1, entries, () -> {
        }, failure -> fail(failure))) {
            assertTrue(packet.fragmentCount() > 1);
            var actual = new java.util.ArrayList<MissingPreviewEntryProto>();
            for (var index = 0; index < packet.fragmentCount(); index++) {
                try (var frame = packet.buildFragment(index);
                    var decoded = FrameCodec.decode(frame.bytes(), ignored -> true)) {
                    var result = ProtocolBuffer.parse(decoded.protobuf(), MissingPreviewFragmentProto::parseFrom);
                    assertEquals(index, result.sequence());
                    assertEquals(index + 1 == packet.fragmentCount(), result.finalFragment());
                    actual.addAll(result.entries());
                }
            }
            assertEquals(entries, actual);
        }
    }

    @Test
    void emptyQueryHasAnExplicitFinal() {
        try (var packet = new MissingPreviewPacket(1, List.of(), () -> {
        }, failure -> fail(failure)); var frame = packet.buildFragment(0);
            var decoded = FrameCodec.decode(frame.bytes(), ignored -> true)) {
            var result = ProtocolBuffer.parse(decoded.protobuf(), MissingPreviewFragmentProto::parseFrom);
            assertEquals(1, packet.fragmentCount());
            assertTrue(result.entries().isEmpty());
            assertTrue(result.finalFragment());
        }
    }

    @Test
    void smallQueryEntriesThatFitOneFrameAreNotSplit() {
        var entries = List.of(entry(1), entry(2));
        var combined = MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setSequence(0)
            .setFinalFragment(true).addAllEntries(entries).build();
        try (var protobuf = ProtocolBuffer.serialize(combined); var empty = ArrayBuffer.allocate(0);
            var frame = FrameCodec.encode(ProtocolMessages.MISSING_PREVIEW_FRAGMENT_ID, protobuf, empty)) {
            assertNotNull(frame);
        }
        try (var packet = new MissingPreviewPacket(1, entries, () -> {
        }, failure -> fail(failure))) {
            assertEquals(1, packet.fragmentCount(), "W02 requires no needless split when the query fits one frame");
            try (var frame = packet.buildFragment(0); var decoded = FrameCodec.decode(frame.bytes(), ignored -> true)) {
                var result = ProtocolBuffer.parse(decoded.protobuf(), MissingPreviewFragmentProto::parseFrom);
                assertEquals(entries, result.entries());
                assertTrue(result.finalFragment());
            }
        }
    }

    private static MissingPreviewEntryProto entry(int marker) {
        var model = new byte[32];
        var container = new byte[32];
        model[0] = (byte)marker;
        container[0] = (byte)(marker + 2);
        return MissingPreviewEntryProto.newBuilder().setModelId(ByteBuffer.wrap(model))
            .setContainerId(ByteBuffer.wrap(container)).setHierarchyPath("local:custom/fixture/" + marker).build();
    }
}
