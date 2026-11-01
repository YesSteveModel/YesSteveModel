package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.buffer.BorrowedNativeBuffer;
import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements VertexBufferAccessor {
    @Shadow
    private ByteBuffer buffer;
    @Shadow
    private int nextElementByte;
    @Shadow
    private int vertices;
    @Shadow
    private VertexFormat format;

    @Shadow
    protected abstract void ensureCapacity(int increaseAmount);

    @Unique
    @Override
    public boolean ysm$ok() {
        return true;
    }

    @Unique
    @Override
    public VertexFormat ysm$vertexFormat() {
        return format;
    }

    @Unique
    @Override
    public BorrowedNativeBuffer ysm$reserve(int vertexCount) {
        if (vertexCount < 0)
            throw new IllegalArgumentException("Negative vertex count");
        var requiredBytes = Math.multiplyExact(vertexCount, this.format.getVertexSize());
        ensureCapacity(requiredBytes);
        return BorrowedNativeBuffer.at(this, this.buffer, this.nextElementByte, requiredBytes);
    }

    @Override
    public void ysm$advance(int vertexCount) {
        this.vertices += vertexCount;
        this.nextElementByte += vertexCount * this.format.getVertexSize();
    }
}
