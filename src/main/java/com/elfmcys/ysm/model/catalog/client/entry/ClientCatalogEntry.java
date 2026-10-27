package com.elfmcys.ysm.model.catalog.client.entry;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelRepresentation;

import java.util.Objects;

/** Client presentation plus an internal content lease; representation identity is not exposed. */
public record ClientCatalogEntry(CatalogEntry entry, ModelContent content, Origin origin) {
    public ClientCatalogEntry {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(origin, "origin");
        if (!entry.modelId().equals(content.modelId())) {
            throw new IllegalArgumentException("Client entry content does not match its model id");
        }
    }

    public Hash256 modelHash() {
        return entry.modelId();
    }

    public ModelRepresentation displayRepresentation() {
        return content.representation();
    }

    public String displayPath() {
        return entry.path().value();
    }

    public boolean builtin() {
        return origin == Origin.BUILTIN;
    }

    public boolean hasLocalSource() {
        return origin != Origin.SERVER;
    }

    public boolean authorizationRequired() {
        return entry.access() == CatalogAccess.AUTHORIZED;
    }

    public enum Origin {
        BUILTIN, CUSTOM, AUTH, SERVER
    }
}
