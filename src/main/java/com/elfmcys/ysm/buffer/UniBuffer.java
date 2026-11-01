package com.elfmcys.ysm.buffer;

import com.elfmcys.ysm.util.ScopeGuard;
import java.nio.ByteBuffer;

public interface UniBuffer {
    int MAX_SIZE = 256 * 1024 * 1024;

    ByteBuffer nio();

    BufferType type();

    int size();

    /** Borrows a range; the original owner must cover every access. */
    UniBuffer slice(int offset, int size);

    default UniBuffer borrow() {
        return slice(0, size());
    }

    /** Retains a shared view, or copies this range into GC-owned storage. Does not isolate shared content. */
    default SharedBuffer share() {
        return this instanceof SharedBuffer shared ? shared : SharedBuffer.copyOf(this);
    }

    /** Retains a shared native view, or copies this range into GC-owned native storage. */
    default SharedNativeBuffer shareNative() {
        return this instanceof SharedNativeBuffer shared ? shared : SharedNativeBuffer.copyOf(this);
    }

    /** Retains a shared array view, or copies this range into GC-owned array storage. */
    default SharedArrayBuffer shareArray() {
        return this instanceof SharedArrayBuffer shared ? shared : SharedArrayBuffer.copyOf(this);
    }

    default BufferStorage copy() {
        return type() == BufferType.NATIVE ? copyNative() : copyArray();
    }

    default BufferStorage copy(int offset, int size) {
        try {
            return slice(offset, size).copy();
        } finally {
            java.lang.ref.Reference.reachabilityFence(this);
        }
    }

    default NativeStorage copyNative() {
        return NativeBuffer.copyOf(this);
    }

    default ArrayStorage copyArray() {
        return ArrayBuffer.copyOf(this);
    }

    static BufferStorage move(ByteBuffer data) {
        return data.isDirect() ? NativeBuffer.move(data) : ArrayBuffer.move(data);
    }

    static UniBuffer borrow(ByteBuffer data) {
        return data.isDirect() ? NativeBuffer.borrow(data) : ArrayBuffer.borrow(data);
    }

    static BufferStorage allocate(int size, BufferType type) {
        return type == BufferType.NATIVE ? NativeBuffer.allocate(size) : ArrayBuffer.allocate(size);
    }

    static ScopeGuard<BufferStorage> allocateWithScope(int size, BufferType type) {
        return ScopeGuard.create(allocate(size, type));
    }
}
