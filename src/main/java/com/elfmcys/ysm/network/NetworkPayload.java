package com.elfmcys.ysm.network;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import us.hebi.quickbuf.ProtoMessage;

import java.util.Objects;
import java.util.Optional;

/**
 * Owns a typed message's optional raw attachment across one network handoff.
 *
 * <p><b>Requires:</b> The producer stops mutating the protobuf and shared attachment aliases before
 * handoff. This wrapper retains the protobuf object; it does not freeze or copy its mutable fields.
 * Typed decoding establishes structure, not business authority, current membership or request permission.
 *
 * <p><b>Ownership:</b> The receiving handler closes the payload or transfers retained backing to its
 * asynchronous owner. Raw access borrows this payload's reference; retain an independent share before
 * payload close if work outlives the payload. Close drops the raw reference and is idempotent.
 *
 * <p><b>Seam:</b> [typed inbound ownership](docs/architecture/network/overview.md#接收准入与执行权限).
 */
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
