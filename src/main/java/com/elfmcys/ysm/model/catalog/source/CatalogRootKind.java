package com.elfmcys.ysm.model.catalog.source;

/** Scanner namespace; it carries no authorization. */
public enum CatalogRootKind {
    BUILTIN("builtin"), MODELS("models");

    private final String namespace;

    CatalogRootKind(String namespace) {
        this.namespace = namespace;
    }

    public String namespace() {
        return namespace;
    }
}
