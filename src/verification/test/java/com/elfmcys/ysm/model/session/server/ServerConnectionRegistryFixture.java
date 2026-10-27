package com.elfmcys.ysm.model.session.server;

import java.util.Objects;
import java.util.UUID;

/** Test-only access to the production connection-generation gate. */
public final class ServerConnectionRegistryFixture<T extends AutoCloseable> implements AutoCloseable {
    private final UUID playerId;
    private final ServerConnectionRegistry<T> registry = new ServerConnectionRegistry<>();

    public ServerConnectionRegistryFixture(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
    }

    public Generation<T> replace(String connectionId, T state) {
        var generation = new Generation<>(connectionId, state);
        registry.replace(playerId, generation.connection, generation.state);
        return generation;
    }

    public boolean runIfCurrent(Generation<T> generation, Runnable action) {
        Objects.requireNonNull(generation, "generation");
        return registry.runIfCurrent(playerId, generation.connection, generation.state, action);
    }

    public void disconnect(Generation<T> generation) {
        Objects.requireNonNull(generation, "generation");
        registry.remove(playerId, generation.connection);
    }

    @Override
    public void close() {
        registry.close();
    }

    public static final class Generation<T extends AutoCloseable> {
        private final String connectionId;
        private final Object connection = new Object();
        private final T state;

        private Generation(String connectionId, T state) {
            if (connectionId == null || connectionId.isBlank()) {
                throw new IllegalArgumentException("connectionId must not be blank");
            }
            this.connectionId = connectionId;
            this.state = Objects.requireNonNull(state, "state");
        }

        public String connectionId() {
            return connectionId;
        }
    }
}
