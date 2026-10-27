package com.elfmcys.ysm.expression.frontend;

import java.io.Serial;
import java.util.Objects;

public final class MolangFrontendException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        SOURCE_LIMIT, TOKEN_LIMIT, SYNTAX, UNSUPPORTED, LOWERING
    }

    private final Kind kind;
    private final MolangFrontend.SourceLocation location;
    private final int sourceOffset;

    MolangFrontendException(Kind kind, MolangFrontend.SourceLocation location, int sourceOffset, String message) {
        this(kind, location, sourceOffset, message, null);
    }

    MolangFrontendException(Kind kind, MolangFrontend.SourceLocation location, int sourceOffset, String message,
        Throwable cause) {
        super(message(kind, location, sourceOffset, message), cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.location = Objects.requireNonNull(location, "location");
        this.sourceOffset = sourceOffset;
    }

    public Kind kind() {
        return kind;
    }

    public MolangFrontend.SourceLocation location() {
        return location;
    }

    public int sourceOffset() {
        return sourceOffset;
    }

    private static String message(Kind kind, MolangFrontend.SourceLocation location, int sourceOffset, String detail) {
        return kind + " at " + location.value() + ':' + sourceOffset + ": " + detail;
    }
}
