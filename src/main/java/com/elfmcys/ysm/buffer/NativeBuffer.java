package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.natives.buffer.NativeHeapBuffer;
import com.elfmcys.ysm.util.ScopeGuard;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public interface NativeBuffer extends UniBuffer {
    long ptr();

    @Override
    NativeBuffer slice(int offset, int size);

    @Override
    default NativeBuffer borrow() {
        return new NativeNioBuffer(nio(), false);
    }

    @Override
    NativeBuffer acquire();

    @Override
    default NativeBuffer copy() {
        var size = size();
        try (var bufScope = allocateWithScope(size)) {
            var target = bufScope.get();
            try {
                if (size != 0) {
                    PlatformDependent.copyMemory(ptr(), target.ptr(), size);
                }
            } finally {
                Reference.reachabilityFence(this);
                Reference.reachabilityFence(target);
            }
            return bufScope.release();
        }
    }

    @Override
    default BufferType type() {
        return BufferType.NATIVE;
    }

    @Override
    default NativeBuffer acquireNative() {
        return acquire();
    }

    @Override
    default ArrayBuffer acquireArray() {
        var result = ArrayBuffer.allocate(size());
        try {
            UniBufferIO.copy(this, 0, result, 0, size());
            return result;
        } catch (Throwable error) {
            result.close();
            throw error;
        }
    }

    static NativeBuffer allocate(int size) {
        return new NativeHeapBuffer(size);
    }

    static NativeBuffer allocate(int size, int alignment) {
        return new NativeHeapBuffer(size, alignment);
    }

    static ScopeGuard<NativeBuffer> allocateWithScope(int size) {
        return ScopeGuard.create(allocate(size));
    }

    static ScopeGuard<NativeBuffer> allocateWithScope(int size, int alignment) {
        return ScopeGuard.create(allocate(size, alignment));
    }

    static NativeBuffer move(ByteBuffer data) {
        return new NativeNioBuffer(data, true);
    }

    static NativeBuffer borrow(ByteBuffer data) {
        return new NativeNioBuffer(data, false);
    }

    static NativeBuffer mapFile(FileChannel file, long offset, long size) throws IOException {
        return new MappedFileBuffer(file, offset, size);
    }

    static NativeBuffer copyOf(ByteBuffer data) {
        var result = allocate(data.remaining());
        try {
            if (data.isDirect() && result.size() != 0) {
                PlatformDependent.copyMemory(PlatformDependent.directBufferAddress(data) + data.position(),
                    result.ptr(), result.size());
            } else {
                result.nio().put(data.duplicate());
            }
        } catch (RuntimeException | Error error) {
            result.close();
            throw error;
        } finally {
            Reference.reachabilityFence(data);
            Reference.reachabilityFence(result);
        }
        return result;
    }
}
