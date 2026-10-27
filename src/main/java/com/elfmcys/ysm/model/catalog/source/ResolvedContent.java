package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

/** Owns only newly created content until the scan adopts it into its shared candidate graph. */
public final class ResolvedContent implements AutoCloseable {
    private ManagedContainer content;
    private boolean owned;

    public ResolvedContent(ManagedContainer created, @Nullable ManagedContainer reusable) throws IOException {
        content = created;
        owned = created != reusable;
        if (reusable != null) {
            reuse(reusable);
        }
    }

    public void reuse(ManagedContainer reusable) throws IOException {
        if (owned) {
            content = content.reuse(reusable);
            owned = content != reusable;
        }
    }

    public ManagedContainer content() {
        return content;
    }

    public ManagedContainer adopt() {
        owned = false;
        return content;
    }

    @Override
    public void close() {
        if (owned) {
            owned = false;
            content.representation().close();
        }
    }
}
