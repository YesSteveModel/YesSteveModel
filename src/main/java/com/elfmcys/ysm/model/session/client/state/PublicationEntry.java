package com.elfmcys.ysm.model.session.client.state;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.util.Objects;

/** Lean server authority for one exact representation. */
public record PublicationEntry(ModelFileIdentity identity, HierarchyPath path, CatalogAccess access,
    boolean runtimeContentAllowed) {
    public PublicationEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
    }

    public Hash256 modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }

    public boolean sameContent(PublicationEntry other) {
        return other != null && identity.equals(other.identity) && path.equals(other.path) && access == other.access;
    }
}
