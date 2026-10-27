package com.elfmcys.ysm.model.session.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConnectionRegistryTest {
    @Test
    void staleConnectionCannotRunOrCloseTheNewStateForTheSamePlayer() {
        var registry = new ServerConnectionRegistry<TestState>();
        var playerId = UUID.randomUUID();
        var connectionA = new Object();
        var connectionB = new Object();
        var stateA = new TestState();
        var stateB = new TestState();
        var mutations = new AtomicInteger();

        registry.replace(playerId, connectionA, stateA);
        registry.replace(playerId, connectionB, stateB);

        assertEquals(1, stateA.closes.get());
        assertFalse(registry.runIfCurrent(playerId, connectionA, stateA, mutations::incrementAndGet));
        registry.remove(playerId, connectionA);
        assertEquals(0, stateB.closes.get());
        assertSame(stateB, registry.current(playerId, connectionB).orElseThrow());

        assertTrue(registry.runIfCurrent(playerId, connectionB, stateB, mutations::incrementAndGet));
        assertEquals(1, mutations.get());
        registry.remove(playerId, connectionB);
        assertEquals(1, stateB.closes.get());
        assertTrue(registry.current(playerId).isEmpty());
    }

    private static final class TestState implements AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
