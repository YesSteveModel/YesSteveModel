package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.util.internal.PlatformDependent;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.ref.Reference;

public final class ForgeUniBufferIO {
    private ForgeUniBufferIO() {}

    public static void write(FriendlyByteBuf target, UniBuffer source) {
        try {
            if (source instanceof ArrayBuffer array) {
                target.writeBytes(array.array(), array.arrayOffset(), array.size());
                return;
            }
            var nativeBuffer = (NativeBuffer)source;
            var length = nativeBuffer.size();
            var index = target.writerIndex();
            target.ensureWritable(length);
            if (target.hasMemoryAddress()) {
                PlatformDependent.copyMemory(nativeBuffer.ptr(), target.memoryAddress() + index, length);
                target.writerIndex(index + length);
            } else if (target.hasArray()) {
                PlatformDependent.copyMemory(nativeBuffer.ptr(), target.array(), target.arrayOffset() + index, length);
                target.writerIndex(index + length);
            } else {
                target.writeBytes(nativeBuffer.nio());
            }
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(target);
        }
    }

    public static NativeBuffer readNative(FriendlyByteBuf source, int length) {
        if (length < 0 || length > source.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        var target = NativeBuffer.allocate(length);
        try {
            copyFromNetty(source, source.readerIndex(), target, 0, length);
            source.skipBytes(length);
            return target;
        } catch (Throwable error) {
            target.close();
            throw error;
        }
    }

    static void copyFromNetty(ByteBuf source, int sourceIndex, NativeBuffer target, int targetOffset, int length) {
        try {
            if (sourceIndex < 0 || targetOffset < 0 || length < 0 || sourceIndex > source.writerIndex() - length
                || targetOffset > target.size() - length) {
                throw new IndexOutOfBoundsException();
            }
            if (source.hasMemoryAddress()) {
                PlatformDependent.copyMemory(source.memoryAddress() + sourceIndex, target.ptr() + targetOffset, length);
            } else if (source.hasArray()) {
                PlatformDependent.copyMemory(source.array(), source.arrayOffset() + sourceIndex,
                    target.ptr() + targetOffset, length);
            } else {
                var view = target.nio();
                view.position(targetOffset).limit(targetOffset + length);
                source.getBytes(sourceIndex, view);
            }
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(target);
        }
    }
}
