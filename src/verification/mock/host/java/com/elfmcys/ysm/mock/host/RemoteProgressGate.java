package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

/** Holds actual activation work and received metadata packets; it never supplies an activation or transfer result. */
final class RemoteProgressGate implements AutoCloseable {
    private final Object activation;
    private final Object transfer;
    private final Executor workers;
    private final Object localIndex;
    private final boolean localCandidatesMasked;
    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
    private final ArrayDeque<Object> packets = new ArrayDeque<>();
    private final AtomicInteger metadataPackets = new AtomicInteger();
    private final AtomicInteger metadataBytes = new AtomicInteger();
    private final io.netty.channel.Channel channel;
    private ChannelHandlerContext context;
    private boolean workReleased;
    private boolean packetsReleased;

    RemoteProgressGate(Minecraft minecraft) throws Exception {
        var field = ClientSessionRuntime.class.getDeclaredField("current");
        field.setAccessible(true);
        var connection = field.get(null);
        activation = GuiEntryObservation.field(connection, "activation");
        transfer = GuiEntryObservation.field(connection, "assetTransfer");
        workers = (Executor)GuiEntryObservation.field(activation, "workers");
        localIndex = GuiEntryObservation.field(activation, "localIndex");
        localCandidatesMasked = minecraft.hasSingleplayerServer();
        // An integrated server shares the local index. Hide that shortcut to exercise the real cold wire path.
        if (localCandidatesMasked)
            GuiEntryObservation.set(activation, "localIndex",
                (java.util.function.Supplier<com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot>)
                    com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot::empty);
        GuiEntryObservation.set(activation, "workers", (Executor)command -> {
            synchronized (queued) {
                if (!workReleased) {
                    queued.add(command);
                    return;
                }
            }
            workers.execute(command);
        });
        channel = minecraft.getConnection().getConnection().channel();
        channel.eventLoop().submit(() -> channel.pipeline().addBefore("packet_handler", "ysm_remote_progress",
            new ChannelInboundHandlerAdapter() {
                @Override
                public void handlerAdded(ChannelHandlerContext value) {
                    context = value;
                }

                @Override
                public void channelRead(ChannelHandlerContext value, Object message) throws Exception {
                    if (!packetsReleased && metadata(message)) {
                        packets.add(message);
                        return;
                    }
                    super.channelRead(value, message);
                }
            })).syncUninterruptibly();
    }

    private boolean metadata(Object message) throws Exception {
        if (!(message instanceof ClientboundCustomPayloadPacket packet)
            || !packet.getIdentifier().equals(NetworkHandler.CHANNEL_NAME))
            return false;
        var data = packet.getData();
        var bytes = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), bytes);
        try (var frame = FrameCodec.decode(bytes, id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
            if (frame.messageId() != ProtocolMessages.METADATA_PREFIX_FRAGMENT_ID)
                return false;
            metadataPackets.incrementAndGet();
            metadataBytes.addAndGet(bytes.length);
            return true;
        }
    }

    boolean activationHeld() {
        synchronized (queued) {
            return !queued.isEmpty();
        }
    }

    boolean transferHeld() {
        return metadataPackets.get() > 0;
    }

    void releaseWork() {
        synchronized (queued) {
            workReleased = true;
            while (!queued.isEmpty())
                workers.execute(queued.remove());
        }
    }

    void releasePackets() {
        channel.eventLoop().submit(() -> {
            packetsReleased = true;
            while (!packets.isEmpty())
                context.fireChannelRead(packets.remove());
            context.fireChannelReadComplete();
        }).syncUninterruptibly();
    }

    Map<String, ?> snapshot() throws Exception {
        var facts = new LinkedHashMap<String, Object>();
        synchronized (queued) {
            facts.put("activationWorkHeld", queued.size());
        }
        facts.put("receivedMetadataPackets", metadataPackets.get());
        facts.put("receivedMetadataFrameBytes", metadataBytes.get());
        facts.put("localCandidatesMasked", localCandidatesMasked);
        var active = (Map<?, ?>)GuiEntryObservation.field(transfer, "active");
        facts.put("activeTransferIds", active.keySet().stream().map(Object::toString).toList());
        facts.put("pendingActivationEntries", ClientSessionRuntime.session().activationSnapshot().orElseThrow()
            .entries().values().stream().filter(com.elfmcys.ysm.model.session.client.state.ActivationSnapshot.Pending.class::isInstance).count());
        facts.put("nanos", System.nanoTime());
        return facts;
    }

    @Override
    public void close() throws Exception {
        GuiEntryObservation.set(activation, "workers", workers);
        GuiEntryObservation.set(activation, "localIndex", localIndex);
        releaseWork();
        releasePackets();
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get("ysm_remote_progress") != null)
                channel.pipeline().remove("ysm_remote_progress");
        }).syncUninterruptibly();
    }
}
