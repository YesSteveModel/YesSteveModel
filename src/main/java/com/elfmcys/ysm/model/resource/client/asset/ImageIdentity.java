package com.elfmcys.ysm.model.resource.client.asset;

import java.util.List;

/** Resource-issued content proof. Presentation code cannot construct or inspect its key. */
public final class ImageIdentity {
    private final List<?> key;

    ImageIdentity(Object... parts) {
        key = List.of(parts);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageIdentity identity && key.equals(identity.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}
