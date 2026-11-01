package com.elfmcys.ysm.network;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import us.hebi.quickbuf.ProtoMessage;

import java.util.Objects;
import java.util.Optional;

/** Retains an optional shared attachment until the transport or receiver consumes it. */
public final class NetworkPayload<T extends ProtoMessage<T>> implements AutoCloseable {
    private final T protobuf;
    private SharedBuffer raw;

    private NetworkPayload(T protobuf, SharedBuffer raw) {
        this.protobuf = Objects.requireNonNull(protobuf, "protobuf");
        this.raw = raw;
    }

    public static <T extends ProtoMessage<T>> NetworkPayload<T> protobuf(T protobuf) {
        return new NetworkPayload<>(protobuf, null);
    }

    /** Retains shared bytes, or copies a borrowed range. Consumers must not modify them. */
    public static <T extends ProtoMessage<T>> NetworkPayload<T> withRaw(T protobuf, UniBuffer raw) {
        return new NetworkPayload<>(protobuf, Objects.requireNonNull(raw, "raw").share());
    }

    public T protobuf() {
        return protobuf;
    }

    public synchronized Optional<SharedBuffer> raw() {
        return Optional.ofNullable(raw);
    }

    public synchronized int rawSize() {
        return raw == null ? 0 : raw.size();
    }

    @Override
    public synchronized void close() {
        raw = null;
    }
}
