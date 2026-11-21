package com.elfmcys.ysm.model.catalog.source;

/** Public region namespace; access policy is a separate catalog fact. */
public enum CatalogRoot {
    GLOBAL("global:", CatalogRootKind.MODELS), LOCAL("local:", CatalogRootKind.MODELS),
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
        return kind == CatalogRootKind.BUILTIN ? BUILTIN : LOCAL;
    }
}
