package com.elfmcys.ysm.network.frame;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.UniBuffer;

import java.util.Objects;

/** Owns one encoded frame until the synchronous transport call returns. */
public final class OutboundFrame implements AutoCloseable {
    private final BufferStorage data;

    public OutboundFrame(BufferStorage data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    public int size() {
        return data.size();
    }

    public UniBuffer borrow() {
        return data.borrow();
    }

    public byte[] bytes() {
        var result = new byte[data.size()];
        data.nio().get(result);
        return result;
    }

    public boolean contentEquals(OutboundFrame other) {
        return other != null && data.nio().equals(other.data.nio());
    }

    @Override
    public void close() {
        data.close();
    }
}
