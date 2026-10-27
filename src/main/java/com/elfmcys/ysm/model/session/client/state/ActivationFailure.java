package com.elfmcys.ysm.model.session.client.state;

import java.util.Objects;

/** Stable failure provenance for one exact activation attempt. */
public record ActivationFailure(Kind kind, String message) {
    public ActivationFailure {
        Objects.requireNonNull(kind, "kind");
        message = Objects.requireNonNull(message, "message");
    }

    public enum Kind {
        TRANSIENT_ACCESS, DETERMINISTIC_CONTENT
    }
}
