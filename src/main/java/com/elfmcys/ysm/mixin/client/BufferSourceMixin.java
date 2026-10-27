package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.accessor.IExtendedBufferSource;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@Mixin(MultiBufferSource.BufferSource.class)
public class BufferSourceMixin implements IExtendedBufferSource {
    @Shadow
    @Final
    protected Map<RenderType, BufferBuilder> fixedBuffers;

    @Unique
    public void endBatchFixedRenderType() {
        for (RenderType type : fixedBuffers.keySet()) {
            ((MultiBufferSource.BufferSource)(Object)this).endBatch(type);
        }
    }
}
