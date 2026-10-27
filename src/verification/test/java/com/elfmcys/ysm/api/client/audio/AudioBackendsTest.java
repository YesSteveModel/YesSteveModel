package com.elfmcys.ysm.api.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioBackendsTest {
    @Test
    void registryFreezesOneCompleteBackendWithoutFallback() {
        var registry = new AudioBackends.Registry();
        AudioBackend backend = request -> new AudioBackend.Rejected("fixture");

        registry.register(backend);
        assertSame(backend, registry.freeze().orElseThrow());
        assertThrows(IllegalStateException.class, () -> registry.register(backend));
    }

    @Test
    void conflictingRegistrationFailsBeforeFreeze() {
        var registry = new AudioBackends.Registry();
        AudioBackend first = request -> new AudioBackend.Rejected("first");
        AudioBackend second = request -> new AudioBackend.Rejected("second");

        registry.register(first);
        assertThrows(IllegalStateException.class, () -> registry.register(second));
        assertSame(first, registry.freeze().orElseThrow());
    }
}
