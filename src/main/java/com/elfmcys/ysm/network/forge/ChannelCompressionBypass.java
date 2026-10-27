package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.network.NetworkHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ICustomPacket;

public final class ChannelCompressionBypass {
    public static final String MARKER_HANDLER = "ysm_compression_marker";
    private static final ThreadLocal<Boolean> BYPASS_COMPRESSION = new ThreadLocal<>();

    private ChannelCompressionBypass() {}

    public static final class Marker extends ChannelOutboundHandlerAdapter {
        private final ResourceLocation channelName;

        public Marker() {
            this(NetworkHandler.CHANNEL_NAME);
        }

        Marker(ResourceLocation channelName) {
            this.channelName = channelName;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            var bypass = message instanceof ICustomPacket<?> packet && channelName.equals(packet.getName());
            var previous = BYPASS_COMPRESSION.get();
            BYPASS_COMPRESSION.set(bypass);
            try {
                context.write(message, promise);
            } finally {
                if (previous == null) {
                    BYPASS_COMPRESSION.remove();
                } else {
                    BYPASS_COMPRESSION.set(previous);
                }
            }
        }
    }

    public static final class Encoder extends CompressionEncoder {
        public Encoder(int threshold) {
            super(threshold);
        }

        @Override
        protected void encode(ChannelHandlerContext context, ByteBuf source, ByteBuf target) {
            if (Boolean.TRUE.equals(BYPASS_COMPRESSION.get())) {
                var output = new FriendlyByteBuf(target);
                output.writeVarInt(0);
                output.writeBytes(source);
                return;
            }
            super.encode(context, source, target);
        }
    }
}
