package com.elfmcys.ysm.model.catalog.source;

/** Scanner-local root classification; never appears in source-neutral catalogs or the unstable protocol. */
public enum CatalogRootKind {
    BUILTIN("builtin", AccessPolicy.PUBLIC), CUSTOM("custom", AccessPolicy.PUBLIC),
    AUTH("auth", AccessPolicy.SESSION_AUTHORIZED);

    private final String namespace;
    private final AccessPolicy accessPolicy;

    CatalogRootKind(String namespace, AccessPolicy accessPolicy) {
        this.namespace = namespace;
        this.accessPolicy = accessPolicy;
    }

    public String namespace() {
        return namespace;
    }

    public AccessPolicy accessPolicy() {
        return accessPolicy;
    }
}
