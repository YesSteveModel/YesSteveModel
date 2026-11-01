package com.elfmcys.ysm.network;

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
import com.elfmcys.ysm.proto.network.ModelSelectionStateProto;
import com.elfmcys.ysm.proto.network.MolangVariableProto;
import com.elfmcys.ysm.proto.network.PlayerStateReportProto;
import com.elfmcys.ysm.proto.network.PlayerStateUpdateProto;
import com.elfmcys.ysm.proto.network.RoamingStateProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshotProto;
import com.elfmcys.ysm.proto.network.StateWriteMode;
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
        "08888e98a8c0e08081011003180122530801124f20010a200102030405060708090a0b0c0d0e"
            + "0f101112131415161718191a1b1c1d1e1f2012202122232425262728292a2b2c2d2e2f303132"
            + "333435363738393a3b3c3d3e3f401a0764656661756c74";
    private static final String V1_FRAME_HEX = "04" + V1_PROTO_HEX;
    private static final String PLAYER_STATE_FULL_PROTO_HEX =
        "0a14080712104142434445464748494a4b4c4d4e4f5010011a121005181420122814301738004012"
            + "0800480022150a1310020a0f6d696e6563726166743a73706565642a06120469646c653219"
            + "0d785634121212150000c03f0a0b71756572792e7370656564";
    private static final String PLAYER_STATE_DELTA_PROTO_HEX =
        "0a14080712104142434445464748494a4b4c4d4e4f5010021a020800";
    private static final String PLAYER_STATE_UPDATE_PROTO_HEX = "0a02080710011a040a021001";
    private static final String STARRED_MODELS_PROTO_HEX =
        "0a200102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

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
        assertArrayEquals(sequence(1, 32), ProtoBytes.copy(first.catalog().entries().get(0).modelId()));

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
        assertArrayEquals(sequence(1, 32), ProtoBytes.copy(quickbuf.catalog().entries().get(0).modelId()));
    }

    @Test
    void frozenPlayerStateFullAndDeltaKeepLogicalValuesAcrossCodecs() throws Exception {
        var fullBytes = hex(PLAYER_STATE_FULL_PROTO_HEX);
        var deltaBytes = hex(PLAYER_STATE_DELTA_PROTO_HEX);

        var full = ProtobufJavaOracle.parse("ysm.network.PlayerStateReport", fullBytes);
        assertEquals(full, ProtobufJavaOracle.parse("ysm.network.PlayerStateReport", serialize(playerStateFull())));
        assertEquals(1, ((Descriptors.EnumValueDescriptor)field(full, "mode")).getNumber());
        var subject = messageField(full, "subject");
        assertEquals(7, field(subject, "entity_id"));
        assertEquals(ByteString.copyFrom(sequence(65, 16)), field(subject, "player_id"));
        var gameplay = messageField(full, "gameplay");
        var flying = ProtobufJavaOracle.field(gameplay.getDescriptorForType(), "flying");
        assertTrue(gameplay.hasField(flying));
        assertFalse((boolean)gameplay.getField(flying));
        var roaming = messageField(full, "roaming");
        assertEquals(0x12345678, field(roaming, "model_key"));
        assertEquals(1.5F, field(repeatedMessage(roaming, "variables", 0), "value"));

        var delta = ProtobufJavaOracle.parse("ysm.network.PlayerStateReport", deltaBytes);
        assertEquals(delta, ProtobufJavaOracle.parse("ysm.network.PlayerStateReport", serialize(playerStateDelta())));
        assertEquals(2, ((Descriptors.EnumValueDescriptor)field(delta, "mode")).getNumber());
        var deltaGameplay = messageField(delta, "gameplay");
        assertTrue(deltaGameplay.hasField(ProtobufJavaOracle.field(deltaGameplay.getDescriptorForType(), "flying")));

        var quickbuf = parse(full.toByteArray(), PlayerStateReportProto::parseFrom);
        assertTrue(quickbuf.gameplayUnsafe().hasFlying());
        assertFalse(quickbuf.gameplayUnsafe().flyingUnsafe());
        assertEquals("idle", quickbuf.animationUnsafe().animationId());
    }

    @Test
    void generationFreeMessagesUseTheIndependentCompactFieldTable() throws Exception {
        assertFieldNumbers("ysm.network.PlayerStateReport", "subject", 1, "mode", 2, "gameplay", 3, "effects", 4,
            "animation", 5, "roaming", 6);
        assertFieldNumbers("ysm.network.PlayerStateUpdate", "subject", 1, "mode", 2, "model", 3, "gameplay", 4,
            "effects", 5, "animation", 6, "roaming", 7);
        assertFieldNumbers("ysm.network.StarredModelsSnapshot", "model_hashes", 1);

        var update = PlayerStateUpdateProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(7).build())
            .setMode(StateWriteMode.FULL).setModel(ModelSelectionStateProto.newBuilder()
                .setModel(ModelReferenceProto.newBuilder().setBuiltinDefault(true).build()).build())
            .build();
        assertArrayEquals(hex(PLAYER_STATE_UPDATE_PROTO_HEX), serialize(update));
        assertEquals(ProtobufJavaOracle.parse("ysm.network.PlayerStateUpdate", hex(PLAYER_STATE_UPDATE_PROTO_HEX)),
            ProtobufJavaOracle.parse("ysm.network.PlayerStateUpdate", serialize(update)));

        var starred = StarredModelsSnapshotProto.newBuilder().addModelHashes(ByteBuffer.wrap(sequence(1, 32))).build();
        assertArrayEquals(hex(STARRED_MODELS_PROTO_HEX), serialize(starred));
        assertEquals(ProtobufJavaOracle.parse("ysm.network.StarredModelsSnapshot", hex(STARRED_MODELS_PROTO_HEX)),
            ProtobufJavaOracle.parse("ysm.network.StarredModelsSnapshot", serialize(starred)));
    }

    @Test
    void inboundBoundaryRejectsBeforePartialBusinessPublication() {
        var valid = PlayerStateReportProto.newBuilder().setSubject(EntityRefProto.newBuilder().setEntityId(7).build())
            .setMode(com.elfmcys.ysm.proto.network.StateWriteMode.FULL).build();
        var validBytes = serialize(valid);
        var calls = new AtomicInteger();
        var published = new AtomicReference<PlayerStateReportProto>();

        dispatchProbe(validBytes, null, validBytes.length, calls, published);
        assertEquals(1, calls.get());
        assertNotNull(published.get());

        calls.set(0);
        published.set(null);
        assertThrows(IllegalArgumentException.class,
            () -> dispatchProbe(new byte[0], null, validBytes.length, calls, published));
        assertEquals(0, calls.get());
        assertNull(published.get());

        calls.set(0);
        published.set(null);
        var trailing = Arrays.copyOf(validBytes, validBytes.length + 1);
        assertThrows(IllegalArgumentException.class,
            () -> dispatchProbe(trailing, null, trailing.length, calls, published));
        assertEquals(0, calls.get());
        assertNull(published.get());

        calls.set(0);
        published.set(null);
        assertThrows(IllegalArgumentException.class,
            () -> dispatchProbe(validBytes, null, validBytes.length - 1, calls, published));
        assertEquals(0, calls.get());
        assertNull(published.get());

        calls.set(0);
        published.set(null);
        assertThrows(IllegalArgumentException.class,
            () -> dispatchProbe(validBytes, new byte[] {1}, validBytes.length, calls, published));
        assertEquals(0, calls.get());
        assertNull(published.get());

        calls.set(0);
        published.set(null);
        assertThrows(IllegalArgumentException.class, () -> dispatchProbe(hex(PLAYER_STATE_FULL_PROTO_HEX), null,
            hex(PLAYER_STATE_FULL_PROTO_HEX).length, calls, published));
        assertEquals(1, calls.get());
        assertNull(published.get());

        assertTrue(ProtocolInbound.accepts(ProtocolMessages.PLAYER_STATE_REPORT_ID, MessageDirection.CLIENT_TO_SERVER));
        assertFalse(
            ProtocolInbound.accepts(ProtocolMessages.PLAYER_STATE_REPORT_ID, MessageDirection.SERVER_TO_CLIENT));
        assertFalse(ProtocolInbound.accepts(127, MessageDirection.CLIENT_TO_SERVER));
        try (var encoded = FrameCodec.encode(ProtocolMessages.PLAYER_STATE_REPORT_ID, validBytes, null)) {
            assertThrows(IllegalArgumentException.class, () -> FrameCodec.decode(encoded.bytes(),
                id -> ProtocolInbound.accepts(id, MessageDirection.SERVER_TO_CLIENT)));
        }
    }

    private static void dispatchProbe(byte[] protobuf, byte[] attachment, int maxEncodedBytes, AtomicInteger calls,
        AtomicReference<PlayerStateReportProto> published) {
        var actual = spec(ProtocolMessages.PLAYER_STATE_REPORT_ID, PlayerStateReportProto.class);
        var probe = new ProtocolMessageSpec<>(actual.id(), actual.direction(), actual.messageType(), maxEncodedBytes,
            actual.attachmentPolicy(), actual.parser(), (payload, context) -> {
                try (payload) {
                    calls.incrementAndGet();
                    if (!PlayerStateValidator.validReport(payload.protobuf())) {
                        throw new IllegalArgumentException("Invalid player-state report");
                    }
                    published.set(payload.protobuf());
                }
            });
        try (var encoded = FrameCodec.encode(actual.id(), protobuf, attachment);
            var decoded = FrameCodec.decode(encoded.bytes(), id -> ProtocolInbound.accepts(id, actual.direction()))) {
            ProtocolInbound.dispatch(decoded, probe, () -> null);
        }
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
        var entry = CatalogPublicationProto.newBuilder().setModelId(ByteBuffer.wrap(sequence(1, 32)))
            .setContainerId(ByteBuffer.wrap(sequence(33, 32))).setHierarchyPath("default")
            .setAccess(com.elfmcys.ysm.proto.network.CatalogAccess.PUBLIC).build();
        var catalog = CatalogCollectionOperationProto.newBuilder()
            .setOpType(com.elfmcys.ysm.proto.network.CollectionOperationType.FULL).addEntries(entry).build();
        return SessionFullFragmentProto.newBuilder().setTransferId(0x0102030405060708L).setSequence(3)
            .setFinalFragment(true).setCatalog(catalog).build();
    }

    private static PlayerStateReportProto playerStateFull() {
        var subject = EntityRefProto.newBuilder().setEntityId(7).setPlayerId(ByteBuffer.wrap(sequence(65, 16))).build();
        return PlayerStateReportProto.newBuilder().setSubject(subject)
            .setMode(com.elfmcys.ysm.proto.network.StateWriteMode.FULL)
            .setGameplay(
                GameplayStateProto.newBuilder().setFlying(false).setExperienceLevel(5).setFoodLevel(20).setHealth(18)
                    .setMaxHealth(20).setMoveXQ7(-12).setMoveYQ7(0).setMoveZQ7(9).setShieldCooldown(false).build())
            .setEffects(EffectStateSetProto.newBuilder()
                .addEffects(EffectStateProto.newBuilder().setEffectId("minecraft:speed").setLevel(2).build()).build())
            .setAnimation(AnimationStateProto.newBuilder().setAnimationId("idle").build())
            .setRoaming(RoamingStateProto.newBuilder().setModelKey(0x12345678)
                .addVariables(MolangVariableProto.newBuilder().setName("query.speed").setValue(1.5F).build()).build())
            .build();
    }

    private static PlayerStateReportProto playerStateDelta() {
        return PlayerStateReportProto.newBuilder()
            .setSubject(
                EntityRefProto.newBuilder().setEntityId(7).setPlayerId(ByteBuffer.wrap(sequence(65, 16))).build())
            .setMode(com.elfmcys.ysm.proto.network.StateWriteMode.DELTA)
            .setGameplay(GameplayStateProto.newBuilder().setFlying(false).build()).build();
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
