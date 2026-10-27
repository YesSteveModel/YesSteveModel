package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import io.netty.util.internal.PlatformDependent;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

final class LegacyPayloadBuffer implements NativeBuffer {
    private final ByteBuffer data;
    private final NativeLegacyImportResult publicOwner;
    private final NativeLegacyImportResult lease;
    private final AtomicBoolean localOpen;
    private final boolean closesLease;

    static LegacyPayloadBuffer publicView(ByteBuffer data, NativeLegacyImportResult owner) {
        return new LegacyPayloadBuffer(data, owner, null, new AtomicBoolean(true), false);
    }

    private LegacyPayloadBuffer(ByteBuffer data, NativeLegacyImportResult publicOwner, NativeLegacyImportResult lease,
        AtomicBoolean localOpen, boolean closesLease) {
        this.data = data.duplicate();
        this.publicOwner = publicOwner;
        this.lease = lease;
        this.localOpen = localOpen;
        this.closesLease = closesLease;
    }

    private void requireOpen() {
        if (!localOpen.get()) {
            throw new IllegalStateException("Legacy payload view is closed");
        }
        if (lease != null) {
            lease.get();
        } else {
            publicOwner.get();
        }
    }

    @Override
    public LegacyPayloadBuffer acquire() {
        requireOpen();
        var acquired = lease == null ? publicOwner.acquire() : lease.acquire();
        try {
            return new LegacyPayloadBuffer(data, null, acquired, new AtomicBoolean(true), true);
        } catch (RuntimeException | Error failure) {
            acquired.close();
            throw failure;
        }
    }

    @Override
    public LegacyPayloadBuffer slice(int offset, int size) {
        requireOpen();
        int currentSize = data.remaining();
        if (offset < 0 || size < 0 || offset > currentSize - size) {
            throw new IndexOutOfBoundsException();
        }
        return new LegacyPayloadBuffer(data.slice(offset, size), publicOwner, lease, localOpen, closesLease);
    }

    @Override
    public LegacyPayloadBuffer borrow() {
        requireOpen();
        return new LegacyPayloadBuffer(data, publicOwner, lease, new AtomicBoolean(true), false);
    }

    @Override
    public NativeBuffer copy() {
        return NativeBuffer.copyOf(nio());
    }

    @Override
    public NativeBuffer acquireNative() {
        return acquire();
    }

    @Override
    public ArrayBuffer acquireArray() {
        return NativeBuffer.super.acquireArray();
    }

    @Override
    public ByteBuffer nio() {
        requireOpen();
        return data.duplicate();
    }

    @Override
    public long ptr() {
        requireOpen();
        try {
            return PlatformDependent.directBufferAddress(data) + data.position();
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public int size() {
        requireOpen();
        return data.remaining();
    }

    @Override
    public BufferType type() {
        return BufferType.NATIVE;
    }

    @Override
    public void close() {
        if (closesLease) {
            lease.close();
        } else {
            localOpen.set(false);
        }
    }
}
