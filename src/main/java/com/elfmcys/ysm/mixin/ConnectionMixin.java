package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.network.forge.ChannelCompressionBypass;
import io.netty.channel.Channel;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    private Channel channel;

    @Inject(method = "setupCompression", at = @At("RETURN"))
    private void ysm$configureCompressionBypass(int threshold, boolean validateDecompressed, CallbackInfo callback) {
        var pipeline = channel.pipeline();
        if (threshold < 0) {
            if (pipeline.get(ChannelCompressionBypass.MARKER_HANDLER) != null) {
                pipeline.remove(ChannelCompressionBypass.MARKER_HANDLER);
            }
            return;
        }
        var current = pipeline.get("compress");
        if (current instanceof CompressionEncoder compression
            && !(current instanceof ChannelCompressionBypass.Encoder)) {
            pipeline.replace("compress", "compress", new ChannelCompressionBypass.Encoder(compression.getThreshold()));
        }
        if (pipeline.get(ChannelCompressionBypass.MARKER_HANDLER) == null) {
            pipeline.addAfter("encoder", ChannelCompressionBypass.MARKER_HANDLER,
                new ChannelCompressionBypass.Marker());
        }
    }
}
