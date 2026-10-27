package com.elfmcys.ysm.model.catalog.source;

/** Public namespace; access policy remains a separate catalog fact. */
public enum CatalogRoot {
    GLOBAL_CUSTOM("global:custom", CatalogRootKind.CUSTOM), GLOBAL_AUTH("global:auth", CatalogRootKind.AUTH),
    LOCAL_CUSTOM("local:custom", CatalogRootKind.CUSTOM), LOCAL_AUTH("local:auth", CatalogRootKind.AUTH),
    BUILTIN("builtin:", CatalogRootKind.BUILTIN);

    private final String prefix;
    private final CatalogRootKind kind;

    CatalogRoot(String prefix, CatalogRootKind kind) {
        this.prefix = prefix;
        this.kind = kind;
    }

    public String prefix() {
        return prefix;
    }

    public CatalogRootKind kind() {
        return kind;
    }

    public static CatalogRoot local(CatalogRootKind kind) {
        return switch (kind) {
            case CUSTOM -> LOCAL_CUSTOM;
            case AUTH -> LOCAL_AUTH;
            case BUILTIN -> BUILTIN;
        };
    }
}
