package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.service.ModelWorkSummary;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import com.elfmcys.ysm.network.session.SessionMode;
import com.elfmcys.ysm.proto.network.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRepairBatchTest {
    @Test
    void completeSelectionKeepsProbeFailureAndKnownRepairAsFiniteOutcomes() {
        var fixture = new Fixture();
        fixture.query(ResourceFailureReason.UNAVAILABLE, ResourceFailureReason.UNSPECIFIED);
        fixture.batch.repaired(identity(2));
        fixture.batch.tick();
        fixture.batch.tick();
        fixture.batch.tick();
        assertTrue(fixture.batch.terminal());
        var summary = fixture.summaries.get(0);
        assertEquals(2, summary.total());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertEquals(0, summary.unprocessed());
        assertEquals(1, fixture.requests.size());
    }

    @Test
    void removeAndReaddDoesNotRestoreAnUnstartedSelectionIncarnation() {
        var fixture = new Fixture();
        fixture.query(ResourceFailureReason.UNSPECIFIED);
        fixture.batch.tick(); // Metadata remains Pending; the fixed item has not started.
        fixture.session.publishPublication(new RemotePublicationSnapshot(List.of(), Set.of(), List.of(), Map.of()));
        fixture.session.publishPublication(publication(1));
        fixture.batch.tick();
        fixture.batch.tick();
        assertEquals(1, fixture.summaries.get(0).skipped());
        assertEquals(1, fixture.requests.size());
    }

    @Test
    void disconnectAfterCompleteQueryAccountsEveryUnstartedItem() {
        var fixture = new Fixture();
        fixture.query(ResourceFailureReason.UNSPECIFIED, ResourceFailureReason.UNSPECIFIED);
        fixture.batch.close();
        fixture.batch.close();
        var summary = fixture.summaries.get(0);
        assertTrue(summary.stopped());
        assertEquals(2, summary.unprocessed());
        assertTrue(summary.items().isEmpty());
        assertEquals(1, fixture.summaries.size());
    }

    @Test
    void queryFailureDoesNotPretendAnIncompleteSelectionIsACompleteBatch() {
        var fixture = new Fixture();
        fixture.transfer.fail(ResourceTransferFailureProto.newBuilder().setDataTransferId(1)
            .setReason(ResourceFailureReason.BUSY).build());
        fixture.batch.tick();
        assertTrue(fixture.batch.terminal());
        assertEquals(1, fixture.failures.size());
        assertTrue(fixture.summaries.isEmpty());
    }

    private static final class Fixture {
        final ClientModelSession session = new ClientModelSession(SessionMode.AUTO, true, CatalogSnapshot.empty());
        final List<us.hebi.quickbuf.ProtoMessage<?>> requests = new ArrayList<>();
        final ClientAssetTransfer transfer;
        final List<ModelWorkSummary> summaries = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final PreviewRepairBatch batch;

        Fixture() {
            session.onServerHello(ProtocolVersion.CURRENT, false);
            session.publishFull(publication(1, 2));
            transfer = new ClientAssetTransfer(new ClientAssetTransfer.Sender() {
                @Override
                public void request(us.hebi.quickbuf.ProtoMessage<?> message) {
                    requests.add(message);
                }

                @Override
                public void cancel(ResourceTransferCancelProto message) {}
            });
            batch = new PreviewRepairBatch(session, transfer, (content, fetcher) -> {
                throw new AssertionError("Unstarted items must not generate");
            }, ignored -> {
            }, summaries::add, failures::add);
        }

        void query(ResourceFailureReason... failures) {
            var result = MissingPreviewFragmentProto.newBuilder().setDataTransferId(1).setFinalFragment(true);
            for (int i = 0; i < failures.length; i++) {
                var member = identity(i + 1);
                result.addEntries(
                    MissingPreviewEntryProto.newBuilder().setModelId(ByteBuffer.wrap(member.modelId().bytes()))
                        .setContainerId(ByteBuffer.wrap(member.containerId().bytes()))
                        .setHierarchyPath("local:custom/fixture/" + (i + 1)).setProbeFailure(failures[i]).build());
            }
            transfer.acceptMissingPreviews(result.build());
        }
    }

    private static RemotePublicationSnapshot publication(int... markers) {
        var entries = new ArrayList<PublicationEntry>();
        for (int marker : markers)
            entries.add(new PublicationEntry(identity(marker), new HierarchyPath("local:custom/fixture/" + marker),
                CatalogAccess.PUBLIC, true));
        return new RemotePublicationSnapshot(entries, Set.of(), List.of(), Map.of());
    }

    private static ModelFileIdentity identity(int marker) {
        var bytes = new byte[32];
        bytes[0] = (byte)marker;
        var container = new byte[32];
        container[0] = (byte)(marker + 100);
        return new ModelFileIdentity(new Hash256(bytes), new Hash256(container));
    }
}
