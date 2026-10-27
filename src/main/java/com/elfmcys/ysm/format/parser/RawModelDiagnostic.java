package com.elfmcys.ysm.format.parser;

import java.util.Objects;

/** Non-fatal fact produced while interpreting one editable raw source. */
public record RawModelDiagnostic(Kind kind) {
    public RawModelDiagnostic {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        UNKNOWN_AUDIO, INVALID_AUDIO
    }
}
