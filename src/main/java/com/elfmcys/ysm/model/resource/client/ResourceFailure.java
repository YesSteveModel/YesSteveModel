package com.elfmcys.ysm.model.resource.client;

import java.util.Objects;

public record ResourceFailure(Kind kind, Throwable cause) {
    public ResourceFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cause, "cause");
    }

    public enum Kind {
        DETERMINISTIC, TRANSIENT
    }
}
