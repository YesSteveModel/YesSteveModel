package com.elfmcys.ysm.natives.legacy;

/** Stable disposition for one failed legacy import operation. */
public final class LegacyModelImportException extends RuntimeException {
    private final int statusCode;

    LegacyModelImportException(NativeLegacyStatus status, String diagnostic) {
        this(status, diagnostic, null);
    }

    LegacyModelImportException(NativeLegacyStatus status, String diagnostic, Throwable cause) {
        super(status.name() + (diagnostic == null || diagnostic.isBlank() ? "" : ": " + diagnostic), cause);
        statusCode = status.code();
    }

    public int statusCode() {
        return statusCode;
    }

    public static LegacyModelImportException publicationFailure(String diagnostic, Throwable cause) {
        return new LegacyModelImportException(NativeLegacyStatus.PUBLICATION_FAILED, diagnostic, cause);
    }
}
