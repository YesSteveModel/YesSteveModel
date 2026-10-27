package com.elfmcys.ysm.model.catalog.content;

import com.elfmcys.ysm.model.domain.Hash256;

import java.util.Objects;

/** Content-addressed reference used by presentation and resource requests. */
public record AssetRef(Kind kind, String name, Hash256 hash, int storedSize, int decodedSize, String encoding) {
    public AssetRef {
        Objects.requireNonNull(kind, "kind");
        name = Objects.requireNonNullElse(name, "");
        Objects.requireNonNull(hash, "hash");
        encoding = Objects.requireNonNullElse(encoding, "");
        if (storedSize < 0 || decodedSize < 0) {
            throw new IllegalArgumentException("Asset sizes cannot be negative");
        }
    }

    public enum Kind {
        PREVIEW, ICON, CHUNK, PACK_COVER
    }
}
