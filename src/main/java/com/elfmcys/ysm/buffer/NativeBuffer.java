package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.natives.buffer.NativeHeapBuffer;
import com.elfmcys.ysm.util.ScopeGuard;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public interface NativeBuffer extends UniBuffer {
    long ptr();

    @Override
    default BorrowedNativeBuffer slice(int offset, int size) {
        return BorrowedNativeBuffer.of(this, nio(), offset, size);
    }

    @Override
    default BorrowedNativeBuffer borrow() {
        return slice(0, size());
    }

    @Override
    default NativeStorage copy() {
        return copyNative();
    }

    @Override
    default NativeStorage copy(int offset, int size) {
        try {
            return NativeBuffer.copyOf(slice(offset, size));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    default BufferType type() {
        return BufferType.NATIVE;
    }

    static NativeStorage allocate(int size) {
        return new NativeHeapBuffer(size);
    }

    static NativeStorage allocate(int size, int alignment) {
        return new NativeHeapBuffer(size, alignment);
    }

    static ScopeGuard<NativeStorage> allocateWithScope(int size) {
        return ScopeGuard.create(allocate(size));
    }

    static ScopeGuard<NativeStorage> allocateWithScope(int size, int alignment) {
        return ScopeGuard.create(allocate(size, alignment));
    }

    static NativeStorage move(ByteBuffer data) {
        return new NativeNioBuffer(data);
    }

    static BorrowedNativeBuffer borrow(ByteBuffer data) {
        return BorrowedNativeBuffer.of(data, data, 0, data.remaining());
    }

    static NativeStorage mapFile(FileChannel file, long offset, long size) throws IOException {
        return new MappedFileBuffer(file, offset, size);
    }

    static NativeStorage copyOf(UniBuffer source) {
        var target = allocate(source.size());
        try {
            UniBufferIO.copy(source, 0, target, 0, source.size());
            return target;
        } catch (RuntimeException | Error failure) {
            target.close();
            throw failure;
        }
    }

    static NativeStorage copyOf(ByteBuffer source) {
        var target = allocate(source.remaining());
        try {
            target.nio().put(source.duplicate());
            return target;
        } catch (RuntimeException | Error failure) {
            target.close();
            throw failure;
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(target);
        }
    }
}
