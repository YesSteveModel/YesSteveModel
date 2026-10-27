package com.elfmcys.ysm.format;

import java.io.IOException;
import java.util.Objects;

/** Preserves whether an asset load failed while accessing bytes or interpreting content. */
public final class AssetLoadException extends IOException {
    private final Reason reason;

    public AssetLoadException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public AssetLoadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public static AssetLoadException access(String message) {
        return new AssetLoadException(Reason.ACCESS, message);
    }

    public static AssetLoadException access(String message, Throwable cause) {
        return new AssetLoadException(Reason.ACCESS, message, cause);
    }

    public static AssetLoadException content(String message) {
        return new AssetLoadException(Reason.CONTENT, message);
    }

    public static AssetLoadException content(String message, Throwable cause) {
        return new AssetLoadException(Reason.CONTENT, message, cause);
    }

    public enum Reason {
        ACCESS, CONTENT
    }
}
