package com.elfmcys.ysm.network;

import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.PlayerStateValidator;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.AnimationStateProto;
import com.elfmcys.ysm.proto.network.CatalogCollectionOperationProto;
import com.elfmcys.ysm.proto.network.CatalogPublicationProto;
import com.elfmcys.ysm.proto.network.EffectStateProto;
import com.elfmcys.ysm.proto.network.EffectStateSetProto;
import com.elfmcys.ysm.proto.network.EntityRefProto;
import com.elfmcys.ysm.proto.network.GameplayStateProto;
import com.elfmcys.ysm.proto.network.ModelReferenceProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoBytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInteroperabilityTest {
    private static final String V1_PROTO_HEX =
        "08888e98a8c0e08081011003180122780801127420010a45686173683a303130323033303430353036303730383039306130"
            + "6230633064306530663130313131323133313431353136313731383139316131623163316431653166323012202122232425"
            + "262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f401a0764656661756c74";
    private static final String V1_FRAME_HEX = "04" + V1_PROTO_HEX;

    @Test
    void frozenModelSessionPacketMatchesCurrentCodecAndIndependentOracle() throws Exception {
        var expectedProto = hex(V1_PROTO_HEX);
        var expectedFrame = hex(V1_FRAME_HEX);
        var independent = ProtobufJavaOracle.parse("ysm.network.SessionFullFragment", expectedProto);
        var currentProto = serialize(modelSessionFull());
        assertEquals(independent, ProtobufJavaOracle.parse("ysm.network.SessionFullFragment", currentProto));
        try (var encoded = FrameCodec.encode(ProtocolMessages.SESSION_FULL_FRAGMENT_ID, currentProto, null);
            var decoded = FrameCodec.decode(encoded.bytes(),
                id -> ProtocolInbound.accepts(id, MessageDirection.SERVER_TO_CLIENT))) {
            assertEquals(ProtocolMessages.SESSION_FULL_FRAGMENT_ID, decoded.messageId());
            assertEquals(independent,
                ProtobufJavaOracle.parse("ysm.network.SessionFullFragment", bytes(decoded.protobuf())));
        }

        var first = dispatch(expectedFrame, ProtocolMessages.SESSION_FULL_FRAGMENT_ID,
            MessageDirection.SERVER_TO_CLIENT, SessionFullFragmentProto.class);
        var second = dispatch(expectedFrame, ProtocolMessages.SESSION_FULL_FRAGMENT_ID,
            MessageDirection.SERVER_TO_CLIENT, SessionFullFragmentProto.class);
        assertNotSame(first, second);
        assertEquals(0x0102030405060708L, first.transferId());
        assertEquals(3, first.sequence());
        assertTrue(first.finalFragment());
        assertEquals(com.elfmcys.ysm.proto.network.CollectionOperationType.FULL, first.catalog().opType());
        assertEquals("hash:" + HexFormat.of().formatHex(sequence(1, 32)), first.catalog().entries().get(0).modelId());

        assertEquals(0x0102030405060708L, field(independent, "transfer_id"));
        assertEquals("catalog",
            independent.getOneofFieldDescriptor(independent.getDescriptorForType().getOneofs().get(0)).getName());
        var catalog = messageField(independent, "catalog");
        assertEquals(1, ((Descriptors.EnumValueDescriptor)field(catalog, "op_type")).getNumber());
        var entry = repeatedMessage(catalog, "entries", 0);
        assertEquals(ByteString.copyFrom(sequence(33, 32)), field(entry, "container_id"));
        assertEquals("default", field(entry, "hierarchy_path"));

        var changed = independent.toBuilder()
            .setField(ProtobufJavaOracle.field(independent.getDescriptorForType(), "sequence"), 4).build();
        var quickbuf = parse(changed.toByteArray(), SessionFullFragmentProto::parseFrom);
        assertEquals(4, quickbuf.sequence());
        assertEquals("hash:" + HexFormat.of().formatHex(sequence(1, 32)),
            quickbuf.catalog().entries().get(0).modelId());
    }

    @Test
    void selectionWireUsesIndependentNumericFieldTableAndSignedZero() throws Exception {
        assertFieldNumbers("ysm.network.SelectionChange", "selection", 1);
        assertFieldNumbers("ysm.network.Selection", "entity_id", 1, "model", 2, "intent_id", 3, "roaming_names", 4,
            "state", 5);
        assertFieldNumbers("ysm.network.RoamingState", "ids", 1, "values", 2);
        assertFieldNumbers("ysm.network.SelectionStateSync", "entity_id", 1, "intent_id", 2, "state", 3, "sync", 4);
        var numeric =
            com.elfmcys.ysm.proto.network.RoamingStateProto.newBuilder().addIds(0, 63).addValues(0F, -0F).build();
        var bytes = serialize(numeric);
        var oracle = ProtobufJavaOracle.parse("ysm.network.RoamingState", bytes);
        assertEquals(java.util.List.of(0, 63), field(oracle, "ids"));
        var parsed = com.elfmcys.ysm.proto.network.RoamingStateProto.parseFrom(oracle.toByteArray());
        assertEquals(Float.floatToRawIntBits(-0F), Float.floatToRawIntBits(parsed.values_().getFloat(1)));
        var event = com.elfmcys.ysm.proto.network.SelectionStateSyncProto.newBuilder().setEntityId(7).setIntentId(1)
            .setSync(com.elfmcys.ysm.proto.network.SyncArgumentsProto.newBuilder().build()).build();
        assertTrue(com.elfmcys.ysm.proto.network.SelectionStateSyncProto.parseFrom(serialize(event)).hasSync());
        assertTrue(
            ProtocolInbound.accepts(ProtocolMessages.SELECTION_STATE_SYNC_ID, MessageDirection.CLIENT_TO_SERVER));
        assertTrue(
            ProtocolInbound.accepts(ProtocolMessages.SELECTION_STATE_SYNC_ID, MessageDirection.SERVER_TO_CLIENT));
        assertFalse(ProtocolInbound.accepts(ProtocolMessages.ENTITY_TRACK_ID, MessageDirection.CLIENT_TO_SERVER));
        for (int id : new int[] {21, 23, 24})
            assertFalse(ProtocolInbound.accepts(id, MessageDirection.CLIENT_TO_SERVER));
    }

    @Test
    void malformedStateFramesNeverEnterTheTypedHandler() {
        var actual =
            spec(ProtocolMessages.SELECTION_CHANGE_ID, com.elfmcys.ysm.proto.network.SelectionChangeProto.class);
        var calls = new AtomicInteger();
        var probe = new ProtocolMessageSpec<>(actual.id(), actual.direction(), actual.messageType(),
            actual.maxEncodedBytes(), actual.attachmentPolicy(), actual.parser(), (payload, context) -> {
                try (payload) {
                    calls.incrementAndGet();
                }
            });
        try (var encoded = FrameCodec.encode(actual.id(), new byte[] {10, (byte)128}, null); var decoded =
            FrameCodec.decode(encoded.bytes(), id -> ProtocolInbound.accepts(id, MessageDirection.CLIENT_TO_SERVER))) {
            assertThrows(IllegalArgumentException.class, () -> ProtocolInbound.dispatch(decoded, probe, () -> null));
        }
        try (var encoded = FrameCodec.encode(actual.id(), new byte[0], new byte[] {1}); var decoded =
            FrameCodec.decode(encoded.bytes(), id -> ProtocolInbound.accepts(id, MessageDirection.CLIENT_TO_SERVER))) {
            assertThrows(IllegalArgumentException.class, () -> ProtocolInbound.dispatch(decoded, probe, () -> null));
        }
        try (var encoded = FrameCodec.encode(ProtocolMessages.SELECTION_STATE_SYNC_ID, new byte[0], null); var decoded =
            FrameCodec.decode(encoded.bytes(), id -> ProtocolInbound.accepts(id, MessageDirection.CLIENT_TO_SERVER))) {
            assertThrows(IllegalArgumentException.class, () -> ProtocolInbound.dispatch(decoded, probe, () -> null));
        }
        assertEquals(0, calls.get());
    }

    private static <T extends ProtoMessage<T>> T dispatch(byte[] frameBytes, int messageId, MessageDirection direction,
        Class<T> type) {
        var captured = new AtomicReference<T>();
        var actual = spec(messageId, type);
        var probe = new ProtocolMessageSpec<>(actual.id(), actual.direction(), actual.messageType(),
            actual.maxEncodedBytes(), actual.attachmentPolicy(), actual.parser(), (payload, context) -> {
                try (payload) {
                    captured.set(payload.protobuf());
                }
            });
        try (var decoded = FrameCodec.decode(frameBytes.clone(), id -> ProtocolInbound.accepts(id, direction))) {
            ProtocolInbound.dispatch(decoded, probe, () -> null);
        }
        return captured.get();
    }

    @SuppressWarnings("unchecked")
    private static <T extends ProtoMessage<T>> ProtocolMessageSpec<T> spec(int messageId, Class<T> type) {
        var spec = ProtocolMessages.REGISTRY.find(messageId).orElseThrow();
        assertEquals(type, spec.messageType());
        return (ProtocolMessageSpec<T>)spec;
    }

    private static SessionFullFragmentProto modelSessionFull() {
        var entry = CatalogPublicationProto.newBuilder()
            .setModelId("hash:" + java.util.HexFormat.of().formatHex(sequence(1, 32)))
            .setContainerId(ByteBuffer.wrap(sequence(33, 32))).setHierarchyPath("default")
            .setAccess(com.elfmcys.ysm.proto.network.CatalogAccess.PUBLIC).build();
        var catalog = CatalogCollectionOperationProto.newBuilder()
            .setOpType(com.elfmcys.ysm.proto.network.CollectionOperationType.FULL).addEntries(entry).build();
        return SessionFullFragmentProto.newBuilder().setTransferId(0x0102030405060708L).setSequence(3)
            .setFinalFragment(true).setCatalog(catalog).build();
    }

    private static <T extends ProtoMessage<T>> T parse(byte[] bytes, ProtocolMessageSpec.Parser<T> parser) {
        return ProtocolBuffer.parse(ArrayBuffer.borrow(bytes), parser);
    }

    private static byte[] serialize(ProtoMessage<?> message) {
        try (var output = ProtocolBuffer.serialize(message)) {
            return Arrays.copyOfRange(output.array(), output.arrayOffset(), output.arrayOffset() + output.size());
        }
    }

    private static byte[] bytes(UniBuffer buffer) {
        try (var array = buffer.copyArray()) {
            return Arrays.copyOfRange(array.array(), array.arrayOffset(), array.arrayOffset() + array.size());
        }
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static byte[] sequence(int first, int size) {
        var bytes = new byte[size];
        for (var index = 0; index < size; index++) {
            bytes[index] = (byte)(first + index);
        }
        return bytes;
    }

    private static Object field(DynamicMessage message, String name) {
        return message.getField(ProtobufJavaOracle.field(message.getDescriptorForType(), name));
    }

    private static DynamicMessage messageField(DynamicMessage message, String name) {
        return (DynamicMessage)field(message, name);
    }

    private static DynamicMessage repeatedMessage(DynamicMessage message, String name, int index) {
        var descriptor = ProtobufJavaOracle.field(message.getDescriptorForType(), name);
        return (DynamicMessage)message.getRepeatedField(descriptor, index);
    }

    private static void assertFieldNumbers(String messageName, Object... expected) throws Exception {
        var descriptor = ProtobufJavaOracle.parse(messageName, new byte[0]).getDescriptorForType();
        assertEquals(0, expected.length % 2);
        assertEquals(expected.length / 2, descriptor.getFields().size());
        for (var index = 0; index < expected.length; index += 2) {
            assertEquals(expected[index + 1],
                ProtobufJavaOracle.field(descriptor, (String)expected[index]).getNumber());
        }
    }
}
