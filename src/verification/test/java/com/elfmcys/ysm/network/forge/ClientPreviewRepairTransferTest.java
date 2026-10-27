package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.proto.network.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;
import static org.junit.jupiter.api.Assertions.*;

class ClientPreviewRepairTransferTest {
    @Test
    void queryAndUploadShareUnsignedIdsAndRequireAnExactCommittedTerminal() throws Exception {
        var sender = new Sender();
        var owner = new ClientAssetTransfer(sender, -2L);
        var list = owner.queryMissingPreviews();
        owner.acceptMissingPreviews(MissingPreviewFragmentProto.newBuilder().setDataTransferId(-2L).setSequence(0)
            .setFinalFragment(true).addEntries(entry(1, ResourceFailureReason.UNSPECIFIED))
            .addEntries(entry(2, ResourceFailureReason.UNAVAILABLE)).build());
        assertEquals(2, list.join().size());
        assertEquals(ResourceFailureReason.UNAVAILABLE, list.join().get(1).probeFailure());
        try (var preview = TestPreviews.blank()) {
            var upload = owner.uploadPreview(identity(1), preview);
            assertEquals(-1L, ((PreviewUploadRequestProto)sender.requests.get(1)).dataTransferId());
            owner.tickUpload(0, 0);
            assertTrue(sender.bytes.isEmpty());
            owner.acceptUploadAdmission(PreviewUploadAdmissionProto.newBuilder().setDataTransferId(-1L).build());
            sender.writable = false;
            owner.tickUpload(0, 0);
            assertTrue(sender.bytes.isEmpty());
            sender.writable = true;
            owner.tickUpload(0, 0);
            assertArrayEquals(preview.bytes(), sender.bytes.get(0));
            assertFalse(upload.isDone());
            owner.acceptRepairResult(PreviewRepairResultProto.newBuilder().setDataTransferId(-1L)
                .setStatus(PreviewRepairStatus.COMMITTED).build());
            assertEquals(PreviewRepairStatus.COMMITTED, upload.join());
            assertThrows(CompletionException.class, () -> owner.queryMissingPreviews().join());
            assertEquals(2, sender.requests.size());
        }
    }

    @Test
    void queryDuplicateIsNotPricedAgainButConflictGapAndDuplicateIdentityFail() {
        for (var mode = 0; mode < 3; mode++) {
            var sender = new Sender();
            var owner = new ClientAssetTransfer(sender);
            var result = owner.queryMissingPreviews();
            var first = MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setSequence(0)
                .addEntries(entry(1, ResourceFailureReason.UNSPECIFIED)).build();
            owner.acceptMissingPreviews(first);
            owner.acceptMissingPreviews(first);
            assertFalse(result.isDone());
            var invalid = mode == 0 ? first.toBuilder().setFinalFragment(true).build()
                : MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setSequence(mode == 1 ? 2 : 1)
                    .setFinalFragment(true).addEntries(entry(1, ResourceFailureReason.UNSPECIFIED)).build();
            owner.acceptMissingPreviews(invalid);
            assertThrows(CompletionException.class, result::join);
            assertEquals(1, sender.cancels.size());
        }
    }

    @Test
    void emptyFinalQueryIsCompleteAndCancellationRetiresOnlyItsOwnRequest() {
        var sender = new Sender();
        var owner = new ClientAssetTransfer(sender);
        var empty = owner.queryMissingPreviews();
        owner.acceptMissingPreviews(
            MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setFinalFragment(true).build());
        assertTrue(empty.join().isEmpty());
        var cancelled = owner.queryMissingPreviews();
        cancelled.cancel(false);
        owner.acceptMissingPreviews(
            MissingPreviewFragmentProto.newBuilder().setDataTransferId(2).setFinalFragment(true).build());
        assertTrue(cancelled.isCancelled());
        assertEquals(1, sender.cancels.size());
        var current = owner.queryMissingPreviews();
        owner.acceptMissingPreviews(
            MissingPreviewFragmentProto.newBuilder().setDataTransferId(3).setFinalFragment(true).build());
        assertTrue(current.join().isEmpty());
    }

    @Test
    void uploadCannotSucceedBeforeAdmissionAndCompleteSending() {
        var sender = new Sender();
        var owner = new ClientAssetTransfer(sender);
        try (var preview = TestPreviews.blank()) {
            var output = owner.uploadPreview(identity(1), preview);
            owner.acceptRepairResult(PreviewRepairResultProto.newBuilder().setDataTransferId(1)
                .setStatus(PreviewRepairStatus.COMMITTED).build());
            assertThrows(CompletionException.class, output::join);
            assertTrue(sender.bytes.isEmpty());
            assertEquals(1, sender.cancels.size());
            try (var image = preview.open()) {
                assertEquals(1, image.width());
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void queryRejectsAReasonThatCannotRepresentAPreviewProbe() {
        var sender = new Sender();
        var owner = new ClientAssetTransfer(sender);
        var result = owner.queryMissingPreviews();
        owner.acceptMissingPreviews(MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setFinalFragment(true)
            .addEntries(entry(1, ResourceFailureReason.UNAUTHORIZED)).build());
        assertThrows(CompletionException.class, result::join);
        assertEquals(1, sender.cancels.size());
    }

    @Test
    void uploadCursorHasAFiniteFrameBudgetAndNeverWaitsForRateOrWritability() throws Exception {
        var sender = new Sender();
        var owner = new ClientAssetTransfer(sender);
        try (var pixels = com.elfmcys.ysm.buffer.NativeBuffer.allocate(512 * 512 * 4)) {
            var random = new java.util.Random(123);
            var values = new byte[pixels.size()];
            random.nextBytes(values);
            pixels.nio().put(values);
            try (var encoded = com.elfmcys.ysm.natives.image.ImageEncoder.encodeLossless(pixels, 512, 512);
                var preview = com.elfmcys.ysm.model.storage.PreviewStore.decode(encoded.data())) {
                var output = owner.uploadPreview(identity(1), preview);
                owner.acceptUploadAdmission(PreviewUploadAdmissionProto.newBuilder().setDataTransferId(1).build());
                owner.tickUpload(0, 128 * 1024);
                assertEquals(1, sender.bytes.size());
                owner.tickUpload(1, 128 * 1024);
                assertEquals(1, sender.bytes.size());
                owner.tickUpload(1_000_000_000L, 0);
                assertEquals(5, sender.bytes.size());
                assertFalse(output.isDone());
                owner.close();
                assertTrue(output.isCompletedExceptionally());
                assertTrue(sender.cancels.isEmpty());
                try (var image = preview.open()) {
                    assertEquals(512, image.width());
                }
            }
        }
    }

    private static MissingPreviewEntryProto entry(int marker, ResourceFailureReason failure) {
        var identity = identity(marker);
        return MissingPreviewEntryProto.newBuilder().setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(identity.containerId().bytes()))
            .setHierarchyPath("local:custom/fixture/" + marker).setProbeFailure(failure).build();
    }

    private static ModelFileIdentity identity(int marker) {
        return new ModelFileIdentity(hash(marker), hash(marker + 100));
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[32];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }

    private static final class Sender implements ClientAssetTransfer.Sender {
        final List<ProtoMessage<?>> requests = new ArrayList<>();
        final List<ResourceTransferCancelProto> cancels = new ArrayList<>();
        final List<byte[]> bytes = new ArrayList<>();
        boolean writable = true;

        @Override
        public void request(ProtoMessage<?> request) {
            requests.add(request);
        }

        @Override
        public void cancel(ResourceTransferCancelProto cancel) {
            cancels.add(cancel);
        }

        @Override
        public boolean writable() {
            return writable;
        }

        @Override
        public int fragment(PreviewUploadFragmentProto fragment, UniBuffer data) {
            var copy = new byte[data.size()];
            data.nio().get(copy);
            bytes.add(copy);
            return data.size() + fragment.getSerializedSize() + 32;
        }
    }
}
