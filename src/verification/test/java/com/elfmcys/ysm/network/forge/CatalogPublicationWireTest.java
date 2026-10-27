package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.dispatch.SessionPublicationPacket;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.CatalogCollectionOperationProto;
import com.elfmcys.ysm.proto.network.CatalogPublicationProto;
import com.elfmcys.ysm.proto.network.CollectionOperationType;
import com.elfmcys.ysm.proto.network.DefaultAnimationProto;
import com.elfmcys.ysm.proto.network.DefaultAnimationCollectionOperationProto;
import com.elfmcys.ysm.proto.network.GrantCollectionOperationProto;
import com.elfmcys.ysm.proto.network.PackPresentationCollectionOperationProto;
import com.elfmcys.ysm.proto.network.PackPresentationProto;
import com.elfmcys.ysm.proto.network.SessionDeltaFragmentProto;
import com.elfmcys.ysm.proto.network.SessionFullFragmentProto;
import com.elfmcys.ysm.testutil.ProtobufJavaOracle;
import com.elfmcys.ysm.util.ProtoBytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CatalogPublicationWireTest {
    private static final String ANIMATION = "ysm\0idle";

    @Test
    void publicationPreservesEveryNamespaceAndTheIntrinsicDefaultAddress() {
        for (var address : List.of("global:custom/model.mxc", "global:auth/model.mxc", "local:custom/model.mxc",
            "local:auth/model.mxc", "builtin:other", "default")) {
            var expected = new PublicationEntry(new ModelFileIdentity(hash(1), hash(11)), new HierarchyPath(address),
                CatalogAccess.PUBLIC, true);
            var receiver = new SessionCollectionPublication.Receiver(Map.of());
            var publication = completeFull(receiver, List.of(expected), Set.of(), Map.of());
            assertEquals(expected, publication.entries().get(expected.modelId()));
        }
    }

    @Test
    void publicationRejectsOldAddressesAndTheNoncanonicalDefaultAlias() {
        for (var address : List.of("$YSM_GLOBAL/custom/model.mxc", "$YSM_LOCAL/custom/model.mxc",
            "$YSM_BUILTIN/default", "builtin:default", "builtin:/other", "unknown:model")) {
            var value = publication(entry(1, 11, "model.mxc")).toBuilder().setHierarchyPath(address).build();
            var fragment = SessionFullFragmentProto.newBuilder().setTransferId(1).setSequence(0)
                .setCatalog(CatalogCollectionOperationProto.newBuilder().setOpType(full()).addEntries(value).build())
                .build();
            assertInvalid(new SessionCollectionPublication.Receiver(Map.of()).acceptFull(fragment, null));
        }
    }

    @Test
    void runtimeContentDenialChangesOnlyTheRecipientProjection() {
        var source = entry(1, 11, "restricted");
        var permitted = new PublicationEntry(source.identity(), source.path(), CatalogAccess.AUTHORIZED, true);
        var denied = new PublicationEntry(source.identity(), source.path(), CatalogAccess.AUTHORIZED, false);
        var animations = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(animations);
        var baseline = completeFull(receiver, List.of(permitted), Set.of(), animations);
        var result = receiver.acceptDelta(catalogAdd(2, 0, true, denied), baseline);
        assertEquals(SessionCollectionPublication.Status.DELTA, result.status());
        var observed = result.publication().entries().get(source.modelId());
        assertFalse(observed.runtimeContentAllowed());
        assertEquals(true, observed.sameContent(permitted));
        assertEquals(Set.of(), result.publication().grants());
    }

    @Test
    void fullPublicationAcceptsOutOfOrderCoverageAndIdenticalDuplicates() {
        var expectedAnimations = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(expectedAnimations);
        var entry = entry(1, 11, "first");
        var fragments = full(1, List.of(entry), Set.of(entry.modelId()), expectedAnimations);

        assertPending(receiver.acceptFull(fragments.get(3), null));
        assertPending(
            receiver.acceptFull(full(1, List.of(entry), Set.of(entry.modelId()), expectedAnimations).get(3), null));
        assertPending(receiver.acceptFull(fragments.get(0), null));
        assertPending(receiver.acceptFull(fragments.get(2), null));
        var result = receiver.acceptFull(fragments.get(1), null);

        assertEquals(SessionCollectionPublication.Status.FULL, result.status());
        assertEquals(Set.of(entry.modelId()), result.publication().entries().keySet());
        assertEquals(Set.of(entry.modelId()), result.publication().grants());
        assertEquals(expectedAnimations, result.publication().defaultAnimations());
    }

    @Test
    void firstPublicationMustBeFullTransferOneWithAllCollections() {
        var expected = Map.of(ANIMATION, hash(90));
        var deltaFirst =
            new SessionCollectionPublication.Receiver(expected).acceptDelta(grantDelta(1, 0, true, clear()), null);
        var wrongId = new SessionCollectionPublication.Receiver(expected)
            .acceptFull(full(2, List.of(), Set.of(), expected).get(0), null);
        var incomplete =
            new SessionCollectionPublication.Receiver(expected).acceptFull(catalogFull(1, 0, true, List.of()), null);

        assertInvalid(deltaFirst);
        assertInvalid(wrongId);
        assertInvalid(incomplete);
    }

    @Test
    void publicationIdsIncreaseUnsignedAndTerminalIdsNeverReopen() {
        var expected = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(expected);
        var baseline = completeFull(receiver, expected);

        var second = receiver.acceptDelta(grantDelta(2, 0, true, clear()), baseline);
        assertEquals(SessionCollectionPublication.Status.DELTA, second.status());
        assertInvalid(receiver.acceptDelta(grantDelta(2, 0, true, clear()), second.publication()));

        var nextReceiver = new SessionCollectionPublication.Receiver(expected);
        baseline = completeFull(nextReceiver, expected);
        var maximum = nextReceiver.acceptDelta(grantDelta(-1L, 0, true, clear()), baseline);
        assertEquals(SessionCollectionPublication.Status.DELTA, maximum.status());
        assertInvalid(nextReceiver.acceptDelta(grantDelta(3, 0, true, clear()), maximum.publication()));
    }

    @Test
    void conflictingDuplicateAndImpossibleFinalCoverageAreIntrinsicFailures() {
        var expected = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(expected);
        var first = catalogFull(1, 0, false, List.of(entry(1, 11, "first")));
        assertPending(receiver.acceptFull(first, null));
        assertInvalid(receiver.acceptFull(catalogFull(1, 0, false, List.of(entry(2, 12, "second"))), null));

        var finalReceiver = new SessionCollectionPublication.Receiver(expected);
        assertPending(finalReceiver.acceptFull(catalogFull(1, 1, true, List.of(entry(1, 11, "first"))), null));
        assertInvalid(finalReceiver.acceptFull(grantFull(1, 2, false, Set.of()), null));
    }

    @Test
    void deltaRejectsCrossPacketConflictsAndClearRemoveCombination() {
        var expected = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(expected);
        var entry = entry(1, 11, "first");
        var baseline = completeFull(receiver, List.of(entry), Set.of(), expected);
        assertPending(receiver.acceptDelta(catalogRemove(2, 0, false, entry.modelId()), baseline));
        assertInvalid(receiver.acceptDelta(catalogAdd(2, 1, true, entry), baseline));

        var clearReceiver = new SessionCollectionPublication.Receiver(expected);
        baseline = completeFull(clearReceiver, List.of(entry), Set.of(), expected);
        assertPending(clearReceiver.acceptDelta(grantDelta(2, 0, false, clear()), baseline));
        assertInvalid(clearReceiver.acceptDelta(grantDelta(2, 1, true, GrantCollectionOperationProto.newBuilder()
            .setOpType(remove()).addModelIds(ProtoBytes.wrap(entry.modelId())).build()), baseline));
    }

    @Test
    void structurallyValidDeltaForAnotherBaselineIsDiscardedAtomically() {
        var expected = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(expected);
        var stale = entry(1, 11, "shared");
        var baseline = completeFull(receiver, List.of(stale), Set.of(), expected);
        var current = entry(2, 12, "shared");

        var result = receiver.acceptDelta(catalogAdd(2, 0, true, current), baseline);

        assertEquals(SessionCollectionPublication.Status.BASELINE_DRIFT, result.status());
        assertNull(result.publication());
        assertEquals(Set.of(stale.modelId()), baseline.entries().keySet());
    }

    @Test
    void deltaAppliesAllFourCollectionsOnlyAfterExactCoverage() {
        var expected = Map.of(ANIMATION, hash(90));
        var receiver = new SessionCollectionPublication.Receiver(expected);
        var first = entry(1, 11, "first");
        var second = entry(2, 12, "second");
        var baseline = completeFull(receiver, List.of(first), Set.of(first.modelId()), List.of(pack("old")), expected);
        var fragments = List.of(catalogRemove(2, 0, false, first.modelId()), catalogAdd(2, 1, false, second),
            grantDelta(2, 2, false, clear()),
            grantDelta(2, 3, false,
                GrantCollectionOperationProto.newBuilder().setOpType(add())
                    .addModelIds(ProtoBytes.wrap(second.modelId())).build()),
            packRemove(2, 4, false, "old/"), packAdd(2, 5, false, pack("new")),
            animationDelta(2, 6, false,
                DefaultAnimationCollectionOperationProto.newBuilder()
                    .setOpType(com.elfmcys.ysm.proto.network.CollectionOperationType.CLEAR).build()),
            animationDelta(2, 7, true,
                DefaultAnimationCollectionOperationProto.newBuilder().setOpType(add()).addEntries(
                    DefaultAnimationProto.newBuilder().setName(ANIMATION).setHash(ProtoBytes.wrap(hash(90))).build())
                    .build()));

        for (var index = fragments.size() - 1; index > 0; index--) {
            assertPending(receiver.acceptDelta(fragments.get(index), baseline));
        }
        var result = receiver.acceptDelta(fragments.get(0), baseline);

        assertEquals(SessionCollectionPublication.Status.DELTA, result.status());
        assertEquals(Set.of(second.modelId()), result.publication().entries().keySet());
        assertEquals(Set.of(second.modelId()), result.publication().grants());
        assertEquals(List.of("local:custom/new"),
            result.publication().packs().stream().map(ModelPackDescriptor::hierarchy).toList());
        assertEquals(expected, result.publication().defaultAnimations());
    }

    @Test
    void businessLimitsRejectBeforeFragmentRetentionCanGrow() {
        var expected = Map.of(ANIMATION, hash(90));
        var limits = new SessionCollectionPublication.Limits(4, 10_000, 1, 1, 1, 1);
        var receiver = new SessionCollectionPublication.Receiver(expected, limits);
        assertInvalid(receiver
            .acceptFull(catalogFull(1, 0, false, List.of(entry(1, 11, "first"), entry(2, 12, "second"))), null));

        var bytes = new SessionCollectionPublication.Receiver(expected,
            new SessionCollectionPublication.Limits(4, 1, 4, 4, 4, 4));
        assertInvalid(bytes.acceptFull(catalogFull(1, 0, false, List.of()), null));

        var coverage = new SessionCollectionPublication.Receiver(expected, limits);
        assertInvalid(coverage.acceptFull(catalogFull(1, 4, false, List.of()), null));
    }

    @Test
    void grantPublicationFramesAreNeverProtocolCompressed() throws Exception {
        var message = SessionDeltaFragmentProto.newBuilder().setTransferId(2).setSequence(0).setFinalFragment(true)
            .setGrants(GrantCollectionOperationProto.newBuilder().setOpType(add()).addModelIds(ProtoBytes.wrap(hash(1)))
                .build())
            .build();
        try (var packet = new SessionPublicationPacket(List.of(message)); var frame = packet.buildFragment(0);
            var decoded = FrameCodec.decode(frame.bytes(), id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
            assertFalse(decoded.compressed());
            assertEquals(ProtocolMessages.SESSION_DELTA_FRAGMENT_ID, decoded.messageId());
            var independent = ProtobufJavaOracle.parse("ysm.network.SessionDeltaFragment", bytes(decoded.protobuf()));
            assertEquals(2L, field(independent, "transfer_id"));
            assertEquals("grants",
                independent.getOneofFieldDescriptor(independent.getDescriptorForType().getOneofs().get(0)).getName());
            var grants = (DynamicMessage)field(independent, "grants");
            assertEquals(2, ((Descriptors.EnumValueDescriptor)field(grants, "op_type")).getNumber());
            assertEquals(ByteString.copyFrom(hash(1).bytes()), repeatedField(grants, "model_ids", 0));
        }
    }

    private static byte[] bytes(UniBuffer buffer) {
        try (var array = buffer.acquireArray()) {
            return Arrays.copyOfRange(array.array(), array.arrayOffset(), array.arrayOffset() + array.size());
        }
    }

    private static Object field(DynamicMessage message, String name) {
        return message.getField(ProtobufJavaOracle.field(message.getDescriptorForType(), name));
    }

    private static Object repeatedField(DynamicMessage message, String name, int index) {
        return message.getRepeatedField(ProtobufJavaOracle.field(message.getDescriptorForType(), name), index);
    }

    private static RemotePublicationSnapshot completeFull(SessionCollectionPublication.Receiver receiver,
        Map<String, Hash256> animations) {
        return completeFull(receiver, List.of(), Set.of(), animations);
    }

    private static RemotePublicationSnapshot completeFull(SessionCollectionPublication.Receiver receiver,
        List<PublicationEntry> entries, Set<Hash256> grants, Map<String, Hash256> animations) {
        return completeFull(receiver, entries, grants, List.of(), animations);
    }

    private static RemotePublicationSnapshot completeFull(SessionCollectionPublication.Receiver receiver,
        List<PublicationEntry> entries, Set<Hash256> grants, List<ModelPackDescriptor> packs,
        Map<String, Hash256> animations) {
        SessionCollectionPublication.Result result = null;
        for (var fragment : full(1, entries, grants, packs, animations)) {
            result = receiver.acceptFull(fragment, null);
        }
        assertEquals(SessionCollectionPublication.Status.FULL, result.status());
        return result.publication();
    }

    private static List<SessionFullFragmentProto> full(long transferId, List<PublicationEntry> entries,
        Set<Hash256> grants, Map<String, Hash256> animations) {
        return full(transferId, entries, grants, List.of(), animations);
    }

    private static List<SessionFullFragmentProto> full(long transferId, List<PublicationEntry> entries,
        Set<Hash256> grants, List<ModelPackDescriptor> packs, Map<String, Hash256> animations) {
        return List.of(catalogFull(transferId, 0, false, entries), grantFull(transferId, 1, false, grants),
            packFull(transferId, 2, false, packs), animationFull(transferId, 3, true, animations));
    }

    private static SessionFullFragmentProto catalogFull(long transferId, int sequence, boolean terminal,
        List<PublicationEntry> entries) {
        var operation = CatalogCollectionOperationProto.newBuilder().setOpType(full());
        entries.forEach(entry -> operation.addEntries(publication(entry)));
        return SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setCatalog(operation.build()).build();
    }

    private static SessionFullFragmentProto grantFull(long transferId, int sequence, boolean terminal,
        Set<Hash256> grants) {
        var operation = GrantCollectionOperationProto.newBuilder().setOpType(full());
        grants.stream().sorted().forEach(hash -> operation.addModelIds(ProtoBytes.wrap(hash)));
        return SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setGrants(operation.build()).build();
    }

    private static SessionFullFragmentProto packFull(long transferId, int sequence, boolean terminal) {
        return packFull(transferId, sequence, terminal, List.of());
    }

    private static SessionFullFragmentProto packFull(long transferId, int sequence, boolean terminal,
        List<ModelPackDescriptor> packs) {
        var operation = PackPresentationCollectionOperationProto.newBuilder().setOpType(full());
        packs.forEach(pack -> operation.addEntries(packMessage(pack)));
        return SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setPackPresentations(operation.build()).build();
    }

    private static SessionFullFragmentProto animationFull(long transferId, int sequence, boolean terminal,
        Map<String, Hash256> animations) {
        var operation = DefaultAnimationCollectionOperationProto.newBuilder().setOpType(full());
        animations.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> operation.addEntries(DefaultAnimationProto.newBuilder().setName(entry.getKey())
                .setHash(ProtoBytes.wrap(entry.getValue())).build()));
        return SessionFullFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setDefaultAnimations(operation.build()).build();
    }

    private static SessionDeltaFragmentProto catalogRemove(long transferId, int sequence, boolean terminal,
        Hash256 modelId) {
        return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setCatalog(CatalogCollectionOperationProto.newBuilder().setOpType(remove())
                .addModelIds(ProtoBytes.wrap(modelId)).build())
            .build();
    }

    private static SessionDeltaFragmentProto catalogAdd(long transferId, int sequence, boolean terminal,
        PublicationEntry entry) {
        return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal)
            .setCatalog(
                CatalogCollectionOperationProto.newBuilder().setOpType(add()).addEntries(publication(entry)).build())
            .build();
    }

    private static SessionDeltaFragmentProto grantDelta(long transferId, int sequence, boolean terminal,
        GrantCollectionOperationProto operation) {
        return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setGrants(operation).build();
    }

    private static SessionDeltaFragmentProto packRemove(long transferId, int sequence, boolean terminal,
        String hierarchy) {
        return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal)
            .setPackPresentations(PackPresentationCollectionOperationProto.newBuilder().setOpType(remove())
                .addHierarchyPaths("local:custom/"
                    + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy))
                .build())
            .build();
    }

    private static SessionDeltaFragmentProto packAdd(long transferId, int sequence, boolean terminal,
        ModelPackDescriptor pack) {
        return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setPackPresentations(PackPresentationCollectionOperationProto.newBuilder()
                .setOpType(add()).addEntries(packMessage(pack)).build())
            .build();
    }

    private static SessionDeltaFragmentProto animationDelta(long transferId, int sequence, boolean terminal,
        DefaultAnimationCollectionOperationProto operation) {
        return SessionDeltaFragmentProto.newBuilder().setTransferId(transferId).setSequence(sequence)
            .setFinalFragment(terminal).setDefaultAnimations(operation).build();
    }

    private static CatalogPublicationProto publication(PublicationEntry entry) {
        return CatalogPublicationProto.newBuilder().setModelId(ProtoBytes.wrap(entry.modelId()))
            .setContainerId(ProtoBytes.wrap(entry.containerId())).setHierarchyPath(entry.path().value())
            .setRuntimeContentAllowed(entry.runtimeContentAllowed())
            .setAccess(entry.access() == CatalogAccess.PUBLIC ? com.elfmcys.ysm.proto.network.CatalogAccess.PUBLIC
                : com.elfmcys.ysm.proto.network.CatalogAccess.AUTHORIZED)
            .build();
    }

    private static PublicationEntry entry(int model, int container, String path) {
        return new PublicationEntry(new ModelFileIdentity(hash(model), hash(container)),
            new HierarchyPath("local:custom/" + path), CatalogAccess.PUBLIC, true);
    }

    private static ModelPackDescriptor pack(String hierarchy) {
        return new ModelPackDescriptor(CatalogRootKind.CUSTOM,
            "local:custom" + (hierarchy.isEmpty() ? ""
                : "/" + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy)),
            hierarchy, "", Map.of(), null, "", 0);
    }

    private static PackPresentationProto packMessage(ModelPackDescriptor pack) {
        return PackPresentationProto.newBuilder().setHierarchyPath(pack.hierarchy()).setDisplayName(pack.name())
            .setDescription(pack.description())
            .setCoverHash(pack.coverHash() == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(pack.coverHash().bytes()))
            .setCoverFormat(pack.coverFormat()).setCoverSize(pack.coverSize()).build();
    }

    private static GrantCollectionOperationProto clear() {
        return GrantCollectionOperationProto.newBuilder().setOpType(CollectionOperationType.CLEAR).build();
    }

    private static CollectionOperationType full() {
        return CollectionOperationType.FULL;
    }

    private static CollectionOperationType add() {
        return CollectionOperationType.ADD;
    }

    private static CollectionOperationType remove() {
        return CollectionOperationType.REMOVE;
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static void assertPending(SessionCollectionPublication.Result result) {
        assertEquals(SessionCollectionPublication.Status.PENDING, result.status());
    }

    private static void assertInvalid(SessionCollectionPublication.Result result) {
        assertEquals(SessionCollectionPublication.Status.INTRINSIC_INVALID, result.status());
    }
}
