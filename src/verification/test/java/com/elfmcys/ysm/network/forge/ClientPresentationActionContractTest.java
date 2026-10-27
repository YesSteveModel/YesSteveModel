package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PresentationDelivery;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewMemberProto;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;

import static org.junit.jupiter.api.Assertions.*;

class ClientPresentationActionContractTest {
    @Test
    void absentIsSuccessfulAndSuppressesOnlyTheSameSessionsPreviewRequest() {
        var sender = new RecordingSender();
        var session = previewSession();
        var transfers = new ClientAssetTransfer(sender, session);
        var member = new RemotePresentationFetcher.Preview(0, identity());
        var outcomes = new ArrayList<RemotePresentationFetcher.Outcome>();
        var first = transfers.fetch(List.of(member), (ignored, value) -> outcomes.add(value));
        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.ABSENT);
        first.join();
        assertInstanceOf(RemotePresentationFetcher.Absent.class, outcomes.get(0));
        assertTrue(session.previewAbsent(identity()));
        transfers.fetch(List.of(member), (ignored, value) -> {
            assertFalse(Thread.holdsLock(session));
            outcomes.add(value);
        }).join();
        var cached = assertInstanceOf(RemotePresentationFetcher.Absent.class, outcomes.get(1));
        assertTrue(cached.current().getAsBoolean());
        assertEquals(1, sender.requests.size());
        session.invalidatePreview(identity());
        assertFalse(cached.current().getAsBoolean());
        var pending = transfers.fetch(List.of(member), (ignored, value) -> outcomes.add(value));
        assertEquals(2, sender.requests.size());
        session.invalidatePreview(identity());
        acceptPreview(transfers, 2, 0, 0, true, PresentationDelivery.ABSENT);
        pending.join();
        assertEquals(3, outcomes.size());
        assertFalse(((RemotePresentationFetcher.Absent)outcomes.get(2)).current().getAsBoolean());
        assertFalse(session.previewAbsent(identity()));
        assertTrue(sender.cancels.isEmpty());
    }

    @Test
    void obsoletePreviewDoesNotDiscardItsUnrelatedIconSibling() {
        var session = previewSession();
        var transfers = new ClientAssetTransfer(new RecordingSender(), session);
        var outcomes = new ArrayList<RemotePresentationFetcher.Outcome>();
        var result = transfers.fetch(
            List.of(new RemotePresentationFetcher.Preview(0, identity()),
                new RemotePresentationFetcher.Icon(0, identity(), chunk("icon", 1))),
            (member, outcome) -> outcomes.add(outcome));
        session.invalidatePreview(identity());
        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.ABSENT);
        assertFalse(result.isDone());
        acceptIcon(transfers, 1, 0, 0, true, PresentationDelivery.DATA, 1);
        result.join();
        assertFalse(((RemotePresentationFetcher.Absent)outcomes.get(0)).current().getAsBoolean());
        assertInstanceOf(RemotePresentationFetcher.Data.class, outcomes.get(1));
        assertFalse(session.previewAbsent(identity()));
    }

    @Test
    void previewDataCarriesTheSendTimeObservationAndIconsCarryNone() {
        var session = previewSession();
        var original = session.observePreview(identity()).orElseThrow();
        var transfers = new ClientAssetTransfer(new RecordingSender(), session);
        var outcomes = new ArrayList<RemotePresentationFetcher.Data>();
        var result = transfers.fetch(
            List.of(new RemotePresentationFetcher.Preview(0, identity()),
                new RemotePresentationFetcher.Icon(1, identity(), chunk("icon", 1))),
            (member, outcome) -> outcomes.add(assertInstanceOf(RemotePresentationFetcher.Data.class, outcome)));
        session.invalidatePreview(identity());
        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.DATA, 1);
        acceptIcon(transfers, 1, 1, 0, true, PresentationDelivery.DATA, 1);
        result.join();
        assertSame(original, outcomes.get(0).observation());
        assertFalse(session.previewObservationCurrent(outcomes.get(0).observation()));
        assertNull(outcomes.get(1).observation());
        assertFalse(session.previewAbsent(identity()));
    }

    @Test
    void previewDataWithoutASessionObservationIsRejected() {
        var transfers = new ClientAssetTransfer(new RecordingSender());
        var result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
            (member, outcome) -> fail("Preview DATA must carry a session observation"));
        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.DATA, 1);
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void absenceRejectsPayloadOffsetNonterminalAndOtherPresentationKinds() {
        for (var malformed : List.of(PreviewFragmentProto.newBuilder().setOffset(1).setFinalFragment(true).build(),
            PreviewFragmentProto.newBuilder().setOffset(0).setFinalFragment(false).build(),
            PreviewFragmentProto.newBuilder().setOffset(0).setFinalFragment(true).build())) {
            var transfers = new ClientAssetTransfer(new RecordingSender(), previewSession());
            var result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
                (ignored, outcome) -> fail());
            try (var data = buffer(malformed.finalFragment() && malformed.offset() == 0 ? new int[] {1} : new int[0])) {
                transfers.acceptPreview(malformed.toBuilder().setDataTransferId(1).setSlot(0)
                    .setDelivery(PresentationDelivery.ABSENT).build(), data);
            }
            assertThrows(CompletionException.class, result::join);
        }
        var transfers = new ClientAssetTransfer(new RecordingSender(), previewSession());
        var result = transfers.fetch(List.of(new RemotePresentationFetcher.Icon(0, identity(), chunk("icon", 1))),
            (ignored, outcome) -> fail());
        acceptIcon(transfers, 1, 0, 0, true, PresentationDelivery.ABSENT);
        assertThrows(CompletionException.class, result::join);
        transfers = new ClientAssetTransfer(new RecordingSender(), previewSession());
        result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
            (ignored, outcome) -> fail());
        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.UNAVAILABLE);
        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void busyRefusalFailureAndCloseTerminateWithoutAbsent() {
        for (var reason : List.of(com.elfmcys.ysm.proto.network.ResourceFailureReason.BUSY,
            com.elfmcys.ysm.proto.network.ResourceFailureReason.UNAUTHORIZED,
            com.elfmcys.ysm.proto.network.ResourceFailureReason.UNAVAILABLE)) {
            var session = previewSession();
            var transfers = new ClientAssetTransfer(new RecordingSender(), session);
            var result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
                (ignored, outcome) -> fail());
            transfers.fail(com.elfmcys.ysm.proto.network.ResourceTransferFailureProto.newBuilder().setDataTransferId(1)
                .setReason(reason).build());
            assertThrows(CompletionException.class, result::join);
            assertFalse(session.previewAbsent(identity()));
        }
        var session = previewSession();
        var transfers = new ClientAssetTransfer(new RecordingSender(), session);
        var result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
            (ignored, outcome) -> fail());
        result.cancel(false);
        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.ABSENT);
        assertFalse(session.previewAbsent(identity()));
    }

    private static com.elfmcys.ysm.model.session.client.ClientModelSession previewSession() {
        var session = new com.elfmcys.ysm.model.session.client.ClientModelSession(
            com.elfmcys.ysm.network.session.SessionMode.AUTO, true,
            com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot.empty());
        session.onServerHello(com.elfmcys.ysm.network.protocol.ProtocolVersion.CURRENT, false);
        session.publishFull(new com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot(
            List.of(new com.elfmcys.ysm.model.session.client.state.PublicationEntry(identity(),
                new com.elfmcys.ysm.model.domain.HierarchyPath("local:custom/fixture"),
                com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess.PUBLIC, true)),
            java.util.Set.of(), List.of(), Map.of()));
        return session;
    }

    @Test
    void previewAcceptsUnsignedTransferIdAboveSignedRange() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender, previewSession(), Long.MIN_VALUE);
        var result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
            (member, outcome) -> assertInstanceOf(RemotePresentationFetcher.Absent.class, outcome));
        assertEquals(Long.MIN_VALUE, ((PresentationPageRequestProto)sender.requests.get(0)).dataTransferId());
        acceptPreview(transfers, Long.MIN_VALUE, 0, 0, true, PresentationDelivery.ABSENT);
        result.join();
    }

    @Test
    void fittingPageUsesOneIdAndUnavailableIsACompletedSlotOutcome() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender, previewSession());
        var preview = new RemotePresentationFetcher.Preview(0, identity());
        var icon = new RemotePresentationFetcher.Icon(0, identity(), chunk("icon", 1));
        var cover = new RemotePresentationFetcher.PackCover(1, pack("pack", 1));
        var outcomes = new ArrayList<RemotePresentationFetcher.Outcome>();
        var result = transfers.fetch(List.of(cover, icon, preview), (member, outcome) -> outcomes.add(outcome));

        var request = assertInstanceOf(PresentationPageRequestProto.class, sender.requests.get(0));
        assertEquals(1, request.dataTransferId());
        assertEquals(1, request.previews().size());
        assertEquals(0,
            request.previews().get(0).getSerializedSize()
                - PreviewMemberProto.newBuilder().setSlot(0).setModelId(ByteBuffer.wrap(identity().modelId().bytes()))
                    .setContainerId(ByteBuffer.wrap(identity().containerId().bytes())).build().getSerializedSize());
        assertEquals(1, request.icons().size());
        assertEquals(1, request.packCovers().size());

        acceptPreview(transfers, 1, 0, 0, true, PresentationDelivery.DATA, 1, 2);
        acceptIcon(transfers, 1, 0, 0, true, PresentationDelivery.UNAVAILABLE);
        acceptCover(transfers, 1, 1, 0, true, PresentationDelivery.DATA, 3);

        result.join();
        assertEquals(3, outcomes.size());
        assertEquals(1, outcomes.stream().filter(RemotePresentationFetcher.Unavailable.class::isInstance).count());
    }

    @Test
    void wrongTypedSlotFailsTheWholeChild() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender, previewSession());
        var result =
            transfers.fetch(List.of(new RemotePresentationFetcher.Preview(4, identity())), (member, outcome) -> fail());

        acceptIcon(transfers, 1, 4, 0, true, PresentationDelivery.DATA, 1);

        assertThrows(CompletionException.class, result::join);
        assertEquals(List.of(1L), sender.cancels);
    }

    @Test
    void descriptorlessPreviewUsesTerminalLengthAndRejectsOverlapAndOversize() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender, previewSession());
        var outcomes = new ArrayList<RemotePresentationFetcher.Outcome>();
        var result = transfers.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())),
            (member, outcome) -> outcomes.add(outcome));

        acceptPreview(transfers, 1, 0, 1, true, com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, 2);
        acceptPreview(transfers, 1, 0, 0, false, com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, 1);
        result.join();
        assertEquals(1, outcomes.size());

        var overlapSender = new RecordingSender();
        var overlap = new ClientAssetTransfer(overlapSender, previewSession());
        var rejected =
            overlap.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())), (member, outcome) -> fail());
        acceptPreview(overlap, 1, 0, 0, false, com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, 1, 2);
        acceptPreview(overlap, 1, 0, 1, true, com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, 3);
        assertThrows(CompletionException.class, rejected::join);
        assertEquals(List.of(1L), overlapSender.cancels);

        var oversizeSender = new RecordingSender();
        var oversize = new ClientAssetTransfer(oversizeSender, previewSession());
        var tooLarge =
            oversize.fetch(List.of(new RemotePresentationFetcher.Preview(0, identity())), (member, outcome) -> fail());
        acceptPreview(oversize, 1, 0, PreviewStore.MAX_STORED_BYTES, true,
            com.elfmcys.ysm.proto.network.PresentationDelivery.DATA, 1);
        assertThrows(CompletionException.class, tooLarge::join);
    }

    @Test
    void duplicateTypedSlotIsRejectedBeforeIdAllocation() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender, previewSession());
        var result = transfers.fetch(List.of(new RemotePresentationFetcher.Icon(2, identity(), chunk("a", 1)),
            new RemotePresentationFetcher.Icon(2, identity(), chunk("b", 1))), (member, outcome) -> fail());

        assertThrows(CompletionException.class, result::join);
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void oversizedPageStartsChildrenSequentiallyAndCancelStopsTheCurrentChild() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender, previewSession());
        var random = new Random(31);
        var members = new ArrayList<RemotePresentationFetcher.Member>();
        for (var slot = 0; slot < ClientAssetTransfer.MAX_ACTION_MEMBERS; slot++) {
            members.add(new RemotePresentationFetcher.PackCover(slot, pack(randomText(random, 240) + slot, 1)));
        }
        var result = transfers.fetch(members, (member, outcome) -> {
        });

        assertEquals(1, sender.requests.size());
        var first = (PresentationPageRequestProto)sender.requests.get(0);
        assertTrue(first.packCovers().size() < members.size());
        for (var member : first.packCovers()) {
            acceptCover(transfers, first.dataTransferId(), member.slot(), 0, true, PresentationDelivery.DATA, 1);
        }
        assertEquals(2, sender.requests.size());
        var second = (PresentationPageRequestProto)sender.requests.get(1);

        assertTrue(result.cancel(false));
        assertEquals(List.of(second.dataTransferId()), sender.cancels);
        acceptCover(transfers, second.dataTransferId(), second.packCovers().get(0).slot(), 0, true,
            PresentationDelivery.DATA, 1);
        assertEquals(2, sender.requests.size());
    }

    private static void acceptPreview(ClientAssetTransfer transfers, long id, int slot, long offset, boolean terminal,
        PresentationDelivery delivery, int... values) {
        try (var bytes = buffer(values)) {
            transfers.acceptPreview(PreviewFragmentProto.newBuilder().setDataTransferId(id).setSlot(slot)
                .setOffset(offset).setFinalFragment(terminal).setDelivery(delivery).build(), bytes);
        }
    }

    private static void acceptIcon(ClientAssetTransfer transfers, long id, int slot, long offset, boolean terminal,
        PresentationDelivery delivery, int... values) {
        try (var bytes = buffer(values)) {
            transfers.acceptIcon(IconFragmentProto.newBuilder().setDataTransferId(id).setSlot(slot).setOffset(offset)
                .setFinalFragment(terminal).setDelivery(delivery).build(), bytes);
        }
    }

    private static void acceptCover(ClientAssetTransfer transfers, long id, int slot, long offset, boolean terminal,
        PresentationDelivery delivery, int... values) {
        try (var bytes = buffer(values)) {
            transfers.acceptPackCover(PackCoverFragmentProto.newBuilder().setDataTransferId(id).setSlot(slot)
                .setOffset(offset).setFinalFragment(terminal).setDelivery(delivery).build(), bytes);
        }
    }

    private static ArrayBuffer buffer(int... values) {
        var bytes = new byte[values.length];
        for (var index = 0; index < values.length; index++)
            bytes[index] = (byte)values[index];
        return ArrayBuffer.borrow(bytes);
    }

    private static AssetContainerView.ChunkInfo chunk(String name, int size) {
        return new AssetContainerView.ChunkInfo(name, "png", 0, size, size, 0, 0, 0, hash(3).bytes());
    }

    private static ModelPackDescriptor pack(String hierarchy, int size) {
        return new ModelPackDescriptor(CatalogRootKind.CUSTOM,
            "local:custom" + (hierarchy.isEmpty() ? ""
                : "/" + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy)),
            "", "", Map.of(), hash(hierarchy.hashCode()), "png", size);
    }

    private static ModelFileIdentity identity() {
        return new ModelFileIdentity(hash(1), hash(2));
    }

    private static Hash256 hash(int value) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte)value);
        return new Hash256(bytes);
    }

    private static String randomText(Random random, int size) {
        var alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var result = new StringBuilder(size);
        for (var index = 0; index < size; index++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private static final class RecordingSender implements ClientAssetTransfer.Sender {
        private final List<ProtoMessage<?>> requests = new ArrayList<>();
        private final List<Long> cancels = new ArrayList<>();

        @Override
        public void request(ProtoMessage<?> request) {
            requests.add(request);
        }

        @Override
        public void cancel(ResourceTransferCancelProto cancel) {
            cancels.add(cancel.dataTransferId());
        }
    }
}
