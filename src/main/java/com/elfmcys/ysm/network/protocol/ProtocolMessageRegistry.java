package com.elfmcys.ysm.network.protocol;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stable protocol metadata. Transport adapters bind these specs to their own framing API. */
public final class ProtocolMessageRegistry {
    private final Map<Integer, ProtocolMessageSpec<?>> byId;
    private final Map<Class<?>, ProtocolMessageSpec<?>> byType;

    private ProtocolMessageRegistry(Map<Integer, ProtocolMessageSpec<?>> byId,
        Map<Class<?>, ProtocolMessageSpec<?>> byType) {
        this.byId = Map.copyOf(byId);
        this.byType = Map.copyOf(byType);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ProtocolMessageSpec<?>> find(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<ProtocolMessageSpec<?>> find(Class<?> type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Collection<ProtocolMessageSpec<?>> messages() {
        return byId.values();
    }

    public static final class Builder {
        private final Map<Integer, ProtocolMessageSpec<?>> byId = new LinkedHashMap<>();
        private final Map<Class<?>, ProtocolMessageSpec<?>> byType = new LinkedHashMap<>();

        public Builder add(ProtocolMessageSpec<?> spec) {
            Objects.requireNonNull(spec, "spec");
            if (byId.putIfAbsent(spec.id(), spec) != null) {
                throw new IllegalArgumentException("Duplicate protocol message id: " + spec.id());
            }
            if (byType.putIfAbsent(spec.messageType(), spec) != null) {
                byId.remove(spec.id());
                throw new IllegalArgumentException("Duplicate protocol message type: " + spec.messageType().getName());
            }
            return this;
        }

        public ProtocolMessageRegistry build() {
            return new ProtocolMessageRegistry(byId, byType);
        }
    }
}
