package com.elfmcys.ysm.model.session.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Owns the one exact physical connection state currently published for each player. */
public final class ServerConnectionRegistry<T extends AutoCloseable> implements AutoCloseable {
    private final Map<UUID, Entry<T>> entries = new HashMap<>();
    private boolean closed;

    public synchronized void replace(UUID playerId, Object connection, T state) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(state, "state");
        if (closed) {
            close(state);
            throw new IllegalStateException("Server connection registry is closed");
        }
        var previous = entries.put(playerId, new Entry<>(connection, state));
        if (previous != null) {
            close(previous.state);
        }
    }

    public synchronized Optional<T> current(UUID playerId) {
        var entry = entries.get(playerId);
        return entry == null ? Optional.empty() : Optional.of(entry.state);
    }

    public synchronized Optional<T> current(UUID playerId, Object connection) {
        var entry = entries.get(playerId);
        return entry != null && entry.connection == connection ? Optional.of(entry.state) : Optional.empty();
    }

    public synchronized boolean runIfCurrent(UUID playerId, Object connection, T expected, Runnable action) {
        Objects.requireNonNull(action, "action");
        var entry = entries.get(playerId);
        if (entry == null || entry.connection != connection || entry.state != expected) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized void remove(UUID playerId, Object connection) {
        var entry = entries.get(playerId);
        if (entry == null || entry.connection != connection) {
            return;
        }
        entries.remove(playerId);
        close(entry.state);
    }

    public synchronized void forEach(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        entries.values().forEach(entry -> action.accept(entry.state));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        entries.values().forEach(entry -> close(entry.state));
        entries.clear();
    }

    private static void close(AutoCloseable state) {
        try {
            state.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to close server connection state", failure);
        }
    }

    private record Entry<T>(Object connection, T state) {
    }
}
