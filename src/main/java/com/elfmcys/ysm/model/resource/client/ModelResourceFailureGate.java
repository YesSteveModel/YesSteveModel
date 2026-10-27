package com.elfmcys.ysm.model.resource.client;

import java.util.Optional;

/** Shared gate for one lazy resource in one model content version. */
public interface ModelResourceFailureGate {
    Optional<Throwable> failure();

    void fail(Throwable cause);

    static ModelResourceFailureGate none() {
        return NoFailureGate.INSTANCE;
    }

    enum NoFailureGate implements ModelResourceFailureGate {
        INSTANCE;

        @Override
        public Optional<Throwable> failure() {
            return Optional.empty();
        }

        @Override
        public void fail(Throwable cause) {}
    }
}
