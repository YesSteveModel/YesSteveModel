package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;

import static org.junit.jupiter.api.Assertions.*;

class ClientMetadataActionContractTest {
    @Test
    void fittingDescriptorUsesOneIdAndCompletesExactCoverage() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var first = identity(2);
        var second = identity(1);
        var received = new HashMap<Hash256, byte[]>();

        var result = transfers.fetchMetadata(List.of(first, second), (identity, bytes) -> {
            var copy = new byte[bytes.size()];
            bytes.nio().get(copy);
            received.put(identity.containerId(), copy);
        });

        var request = assertInstanceOf(MetadataPrefixRequestProto.class, sender.requests.get(0));
        assertEquals(1, request.dataTransferId());
        assertEquals(2, request.containerIds().size());
        assertEquals(second.containerId(), hash(request.containerIds().get(0)));
        assertEquals(first.containerId(), hash(request.containerIds().get(1)));

        transfers.acceptMetadata(fragment(1, second.containerId(), 1, true, 3));
        assertFalse(result.isDone());
        transfers.acceptMetadata(fragment(1, second.containerId(), 0, false, 1, 2));
        transfers.acceptMetadata(fragment(1, first.containerId(), 0, true, 4));

        result.join();
        assertArrayEquals(new byte[] {1, 2, 3}, received.get(second.containerId()));
        assertArrayEquals(new byte[] {4}, received.get(first.containerId()));
        transfers.acceptMetadata(fragment(1, first.containerId(), 0, true, 4));
        assertEquals(2, received.size(), "late terminal ids must not reopen a child");
    }

    @Test
    void conflictingDuplicateFailsTheWholeChildAndCancelsOnce() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var identity = identity(3);
        var result = transfers.fetchMetadata(List.of(identity), (ignored, bytes) -> fail());

        transfers.acceptMetadata(fragment(1, identity.containerId(), 0, false, 1));
        transfers.acceptMetadata(fragment(1, identity.containerId(), 0, false, 1));
        assertFalse(result.isDone());
        transfers.acceptMetadata(fragment(1, identity.containerId(), 0, false, 2));

        assertThrows(CompletionException.class, result::join);
        assertEquals(List.of(1L), sender.cancels);
    }

    @Test
    void oversizedDescriptorStartsFiniteChildrenSequentiallyAndKeepsEarlyOutcomes() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var random = new Random(17);
        var identities = new ArrayList<ModelFileIdentity>();
        for (var index = 0; index < 2_000; index++) {
            var model = new byte[Hash256.SIZE];
            var container = new byte[Hash256.SIZE];
            random.nextBytes(model);
            random.nextBytes(container);
            identities.add(new ModelFileIdentity(new Hash256(model), new Hash256(container)));
        }
        var delivered = new ArrayList<Hash256>();
        var result = transfers.fetchMetadata(identities, (identity, bytes) -> delivered.add(identity.containerId()));

        assertEquals(1, sender.requests.size());
        var first = (MetadataPrefixRequestProto)sender.requests.get(0);
        assertTrue(first.containerIds().size() < identities.size());
        for (var container : first.containerIds()) {
            transfers
                .acceptMetadata(fragment(first.dataTransferId(), new Hash256(ProtoBytes.copy(container)), 0, true, 1));
        }

        assertEquals(2, sender.requests.size(), "the next child starts only after child one succeeds");
        var second = (MetadataPrefixRequestProto)sender.requests.get(1);
        transfers.fail(ResourceTransferFailureProto.newBuilder().setDataTransferId(second.dataTransferId())
            .setReason(ResourceFailureReason.BUSY).build());

        assertThrows(CompletionException.class, result::join);
        assertEquals(first.containerIds().size(), delivered.size());
        assertEquals(2, sender.requests.size());
    }

    @Test
    void transferIdsCrossTheSignedBoundaryAndExhaustOnlyAfterUnsignedMax() {
        var signedBoundarySender = new RecordingSender();
        var signedBoundary = new ClientAssetTransfer(signedBoundarySender, Long.MAX_VALUE);

        signedBoundary.fetchMetadata(List.of(identity(1)), (ignored, bytes) -> {
        });
        signedBoundary.fetchMetadata(List.of(identity(2)), (ignored, bytes) -> {
        });

        assertEquals(Long.MAX_VALUE, requestId(signedBoundarySender.requests.get(0)));
        assertEquals(Long.MIN_VALUE, requestId(signedBoundarySender.requests.get(1)));
        signedBoundary.close();

        var unsignedMaxSender = new RecordingSender();
        var unsignedMax = new ClientAssetTransfer(unsignedMaxSender, -2L);
        unsignedMax.fetchMetadata(List.of(identity(3)), (ignored, bytes) -> {
        });
        unsignedMax.fetchMetadata(List.of(identity(4)), (ignored, bytes) -> {
        });
        var exhausted = unsignedMax.fetchMetadata(List.of(identity(5)), (ignored, bytes) -> {
        });

        assertEquals(-2L, requestId(unsignedMaxSender.requests.get(0)));
        assertEquals(-1L, requestId(unsignedMaxSender.requests.get(1)));
        assertEquals(2, unsignedMaxSender.requests.size());
        assertThrows(CompletionException.class, exhausted::join);
        unsignedMax.close();
    }

    private static MetadataPrefixFragmentProto fragment(long id, Hash256 containerId, int sequence, boolean terminal,
        int... payload) {
        var bytes = new byte[payload.length];
        for (var index = 0; index < payload.length; index++)
            bytes[index] = (byte)payload[index];
        return MetadataPrefixFragmentProto.newBuilder().setDataTransferId(id)
            .setContainerId(ProtoBytes.wrap(containerId)).setSequence(sequence).setFinalFragment(terminal)
            .setPayload(ByteBuffer.wrap(bytes)).build();
    }

    private static ModelFileIdentity identity(int value) {
        var model = new byte[Hash256.SIZE];
        var container = new byte[Hash256.SIZE];
        Arrays.fill(model, (byte)(value + 40));
        Arrays.fill(container, (byte)value);
        return new ModelFileIdentity(new Hash256(model), new Hash256(container));
    }

    private static Hash256 hash(ByteBuffer bytes) {
        return new Hash256(ProtoBytes.copy(bytes));
    }

    private static long requestId(ProtoMessage<?> request) {
        return assertInstanceOf(MetadataPrefixRequestProto.class, request).dataTransferId();
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
