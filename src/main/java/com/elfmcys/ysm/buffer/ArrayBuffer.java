package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.util.ScopeGuard;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;

public interface ArrayBuffer extends UniBuffer {
    byte[] array();

    int arrayOffset();

    @Override
    default BorrowedArrayBuffer slice(int offset, int size) {
        return BorrowedArrayBuffer.of(this, array(), arrayOffset(), size(), offset, size);
    }

    @Override
    default BorrowedArrayBuffer borrow() {
        return slice(0, size());
    }

    @Override
    default ArrayStorage copy() {
        return copyArray();
    }

    @Override
    default ArrayStorage copy(int offset, int size) {
        try {
            return ArrayBuffer.copyOf(slice(offset, size));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    default BufferType type() {
        return BufferType.ARRAY;
    }

    static ArrayStorage allocate(int size) {
        return new ArrayHeapBuffer(size, size > 1024);
    }

    static ScopeGuard<ArrayStorage> allocateWithScope(int size) {
        return ScopeGuard.create(allocate(size));
    }

    static ArrayStorage move(byte[] array) {
        return new ArrayHeapBuffer(array, 0, array.length);
    }

    static ArrayStorage move(byte[] array, int offset, int size) {
        return new ArrayHeapBuffer(array, offset, size);
    }

    static ArrayStorage move(ByteBuffer data) {
        return move(data.array(), data.arrayOffset() + data.position(), data.remaining());
    }

    static BorrowedArrayBuffer borrow(byte[] array) {
        return borrow(array, 0, array.length);
    }

    static BorrowedArrayBuffer borrow(byte[] array, int offset, int size) {
        return BorrowedArrayBuffer.of(array, array, 0, array.length, offset, size);
    }

    static BorrowedArrayBuffer borrow(ByteBuffer data) {
        return borrow(data.array(), data.arrayOffset() + data.position(), data.remaining());
    }

    static ArrayStorage copyOf(UniBuffer source) {
        var target = allocate(source.size());
        try {
            UniBufferIO.copy(source, 0, target, 0, source.size());
            return target;
        } catch (RuntimeException | Error failure) {
            target.close();
            throw failure;
        }
    }

    static ArrayStorage copyOf(ByteBuffer source) {
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
