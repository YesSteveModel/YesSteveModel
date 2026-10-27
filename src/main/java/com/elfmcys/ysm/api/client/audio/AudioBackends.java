package com.elfmcys.ysm.api.client.audio;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.Optional;

/** Process-wide registration point for the optional complete model-audio backend. */
public final class AudioBackends {
    private static final Registry REGISTRY = new Registry();

    private AudioBackends() {}

    /** Registers the process's only external backend before the client model runtime starts. */
    public static void register(AudioBackend backend) {
        REGISTRY.register(backend);
    }

    @ApiStatus.Internal
    public static Optional<AudioBackend> freeze() {
        return REGISTRY.freeze();
    }

    static final class Registry {
        private AudioBackend backend;
        private boolean frozen;

        synchronized void register(AudioBackend candidate) {
            Objects.requireNonNull(candidate, "backend");
            if (frozen) {
                throw new IllegalStateException("Audio backend registration is already frozen");
            }
            if (backend != null) {
                throw new IllegalStateException("Only one external audio backend may be registered");
            }
            backend = candidate;
        }

        synchronized Optional<AudioBackend> freeze() {
            frozen = true;
            return Optional.ofNullable(backend);
        }
    }
}
