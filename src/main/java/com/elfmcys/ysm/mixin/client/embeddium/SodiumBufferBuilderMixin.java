package com.elfmcys.ysm.mixin.client.embeddium;

import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.jellysquid.mods.sodium.client.render.vertex.buffer.ExtendedBufferBuilder;
import me.jellysquid.mods.sodium.client.render.vertex.buffer.SodiumBufferBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SodiumBufferBuilder.class, remap = false)
public class SodiumBufferBuilderMixin implements VertexBufferAccessor {
    @Shadow
    @Final
    private ExtendedBufferBuilder builder;

    @Unique
    @Override
    public boolean ysm$ok() {
        return builder instanceof VertexBufferAccessor;
    }

    @Unique
    @Override
    public VertexFormat ysm$vertexFormat() {
        return ((VertexBufferAccessor)builder).ysm$vertexFormat();
    }

    @Unique
    @Override
    public BorrowedNativeBuffer ysm$reserve(int vertexCount) {
        return ((VertexBufferAccessor)builder).ysm$reserve(vertexCount);
    }

    @Unique
    @Override
    public void ysm$advance(int vertexCount) {
        ((VertexBufferAccessor)builder).ysm$advance(vertexCount);
    }
}
