package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.PresentationDelivery;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;
import us.hebi.quickbuf.ProtoMessage;

/** Test Adapter that feeds wire fragments through the production presentation receiver. */
public final class ClientPresentationTransportFixture {
    private final ClientAssetTransfer transfers;
    private long transferId;
    private int requests;

    public ClientPresentationTransportFixture(ClientModelSession session) {
        transfers = new ClientAssetTransfer(new ClientAssetTransfer.Sender() {
            @Override
            public void request(ProtoMessage<?> request) {
                transferId = ((PresentationPageRequestProto)request).dataTransferId();
                requests++;
            }

            @Override
            public void cancel(ResourceTransferCancelProto cancel) {}
        }, session);
    }

    public RemotePresentationFetcher fetcher() {
        return transfers;
    }

    public int requests() {
        return requests;
    }

    public void preview(int slot, long offset, boolean terminal, PresentationDelivery delivery, byte[] payload) {
        try (var bytes = ArrayBuffer.borrow(payload)) {
            transfers.acceptPreview(PreviewFragmentProto.newBuilder().setDataTransferId(transferId).setSlot(slot)
                .setOffset(offset).setFinalFragment(terminal).setDelivery(delivery).build(), bytes);
        }
    }

    public void icon(int slot, byte[] payload) {
        try (var bytes = ArrayBuffer.borrow(payload)) {
            transfers.acceptIcon(IconFragmentProto.newBuilder().setDataTransferId(transferId).setSlot(slot).setOffset(0)
                .setFinalFragment(true).setDelivery(PresentationDelivery.DATA).build(), bytes);
        }
    }
}
