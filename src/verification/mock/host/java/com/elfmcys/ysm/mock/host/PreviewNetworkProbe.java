package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;

/** Observes actual Forge payloads, including dispatch frames that bypass typed trace logging. */
final class PreviewNetworkProbe {
    private static final Map<String, Map<String, Counter>> counters = new ConcurrentHashMap<>();

    static io.netty.util.concurrent.Future<?>[] install(MinecraftServer server) {
        counters.clear();
        return server.getPlayerList().getPlayers().stream().map(player -> {
            var name = player.getGameProfile().getName();
            var channel = player.connection.connection.channel();
            return channel.eventLoop().submit(() -> {
                if (channel.pipeline().get("ysm_preview_cost") != null)
                    return;
                channel.pipeline().addBefore("packet_handler", "ysm_preview_cost", new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                        throws Exception {
                        if (message instanceof ClientboundCustomPayloadPacket packet
                            && packet.getIdentifier().equals(NetworkHandler.CHANNEL_NAME))
                            count(name, "S2C", packet.getData());
                        super.write(context, message, promise);
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet
                            && packet.getIdentifier().equals(NetworkHandler.CHANNEL_NAME))
                            count(name, "C2S", packet.getData());
                        super.channelRead(context, message);
                    }
                });
            });
        }).toArray(io.netty.util.concurrent.Future<?>[]::new);
    }

    private static void count(String player, String direction, net.minecraft.network.FriendlyByteBuf payload) {
        var bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        try (var frame = FrameCodec.decode(bytes, id -> ProtocolMessages.REGISTRY.find(id).isPresent())) {
            var counter = counters.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(direction + "/" + frame.messageId(), ignored -> new Counter());
            counter.packets.incrementAndGet();
            counter.frameBytes.addAndGet(bytes.length);
            counter.protobufBytes.addAndGet(frame.protobuf().size());
            counter.attachmentBytes.addAndGet(frame.attachment().size());
        }
    }

    static Map<String, ?> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        counters.forEach((player, rows) -> {
            var values = new LinkedHashMap<String, Object>();
            rows.forEach((key, counter) -> values.put(key,
                Map.of("packets", counter.packets.get(), "frameBytes", counter.frameBytes.get(), "protobufBytes",
                    counter.protobufBytes.get(), "attachmentBytes", counter.attachmentBytes.get())));
            result.put(player, values);
        });
        return result;
    }

    private static final class Counter {
        private final AtomicLong packets = new AtomicLong();
        private final AtomicLong frameBytes = new AtomicLong();
        private final AtomicLong protobufBytes = new AtomicLong();
        private final AtomicLong attachmentBytes = new AtomicLong();
    }
}
