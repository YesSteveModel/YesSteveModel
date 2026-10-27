package com.elfmcys.ysm.natives.legacy;

enum NativeLegacyStatus {
    SUCCESS(0), UNSUPPORTED_VERSION(2), INVALID_CONTENT(3), RESOURCE_LIMIT(4), SOURCE_IO(5), INTERNAL(8),
    RESULT_PROTOCOL(9), PUBLICATION_FAILED(10), TARGET_REPRESENTATION(11);

    private final int code;

    NativeLegacyStatus(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    static NativeLegacyStatus fromNative(int code) {
        return switch (code) {
            case 0 -> SUCCESS;
            case 2 -> UNSUPPORTED_VERSION;
            case 3 -> INVALID_CONTENT;
            case 4 -> RESOURCE_LIMIT;
            case 5 -> SOURCE_IO;
            case 8 -> INTERNAL;
            case 11 -> TARGET_REPRESENTATION;
            default -> null;
        };
    }
}
