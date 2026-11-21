package com.elfmcys.ysm.model.resource.client;

import java.util.Objects;

public record ResourceFailure(Kind kind, Throwable cause) {
    public ResourceFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cause, "cause");
    }

    public boolean isMemoryFailure() {
        for (var failure = cause; failure != null; failure = failure.getCause()) {
            if (failure instanceof OutOfMemoryError)
                return true;
        }
        return false;
    }

    public enum Kind {
        DETERMINISTIC, TRANSIENT
    }
}
