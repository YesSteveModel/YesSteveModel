package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;

import static org.junit.jupiter.api.Assertions.*;

class ClientModelActionContractTest {
    @Test
    void fittingDescriptorUsesOneIdAndCompletesOutOfOrderRanges() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var received = new HashMap<String, byte[]>();
        var result = transfers.fetch(identity(), List.of(chunk("b", 4), chunk("a", 2)), (chunk, bytes) -> {
            var copy = new byte[bytes.size()];
            bytes.nio().get(copy);
            received.put(chunk.type(), copy);
        });

        var request = assertInstanceOf(ModelChunkRequestProto.class, sender.requests.get(0));
        assertEquals(1, request.dataTransferId());
        assertEquals("a", request.chunks().get(0).name());
        assertEquals("b", request.chunks().get(1).name());

        accept(transfers, 1, "b", 2, true, 3, 4);
        accept(transfers, 1, "b", 0, false, 1, 2);
        accept(transfers, 1, "b", 0, false, 1, 2);
        accept(transfers, 1, "a", 0, true, 5, 6);

        result.join();
        assertArrayEquals(new byte[] {1, 2, 3, 4}, received.get("b"));
        assertArrayEquals(new byte[] {5, 6}, received.get("a"));
    }

    @Test
    void wrongTypedMemberFailsWholeChildAndLateDataCannotReopenIt() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var result = transfers.fetch(identity(), List.of(chunk("required", 1)), (chunk, bytes) -> fail());

        accept(transfers, 1, "other", 0, true, 1);
        assertThrows(CompletionException.class, result::join);
        assertEquals(List.of(1L), sender.cancels);
        accept(transfers, 1, "required", 0, true, 1);
        assertEquals(1, sender.requests.size());
    }

    @Test
    void oversizedDescriptorUsesSequentialExactChildrenAndKeepsCommittedMembers() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var random = new Random(23);
        var chunks = new ArrayList<AssetContainerView.ChunkInfo>();
        for (var index = 0; index < ClientAssetTransfer.MAX_ACTION_MEMBERS; index++) {
            chunks.add(chunk(randomText(random, 240) + index, 1));
        }
        var delivered = new ArrayList<String>();
        var result = transfers.fetch(identity(), chunks, (chunk, bytes) -> delivered.add(chunk.type()));

        assertEquals(1, sender.requests.size());
        var first = (ModelChunkRequestProto)sender.requests.get(0);
        assertTrue(first.chunks().size() < chunks.size());
        for (var member : first.chunks()) {
            accept(transfers, first.dataTransferId(), member.name(), 0, true, 7);
        }
        assertEquals(2, sender.requests.size());
        var second = (ModelChunkRequestProto)sender.requests.get(1);
        transfers.fail(ResourceTransferFailureProto.newBuilder().setDataTransferId(second.dataTransferId())
            .setReason(ResourceFailureReason.BUSY).build());

        assertThrows(CompletionException.class, result::join);
        assertEquals(first.chunks().size(), delivered.size());
    }

    @Test
    void oneOversizedMemberFailsLocallyWithoutAllocatingAnId() {
        var sender = new RecordingSender();
        var transfers = new ClientAssetTransfer(sender);
        var name = randomText(new Random(29), 70_000);
        var result = transfers.fetch(identity(), List.of(chunk(name, 1)), (chunk, bytes) -> fail());

        assertThrows(CompletionException.class, result::join);
        assertTrue(sender.requests.isEmpty());
    }

    private static void accept(ClientAssetTransfer transfers, long id, String name, long offset, boolean terminal,
        int... values) {
        var bytes = new byte[values.length];
        for (var index = 0; index < values.length; index++)
            bytes[index] = (byte)values[index];
        transfers.acceptChunk(ChunkFragmentProto.newBuilder().setDataTransferId(id).setName(name).setOffset(offset)
            .setFinalFragment(terminal).build(), ArrayBuffer.borrow(bytes));
    }

    private static AssetContainerView.ChunkInfo chunk(String name, int size) {
        var hash = new byte[Hash256.SIZE];
        Arrays.fill(hash, (byte)name.hashCode());
        return new AssetContainerView.ChunkInfo(name, "raw", 0, size, size, 0, 0, 0, hash);
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
