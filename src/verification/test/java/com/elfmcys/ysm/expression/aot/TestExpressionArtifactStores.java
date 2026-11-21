package com.elfmcys.ysm.expression.aot;

import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import java.io.IOException;
import java.nio.file.Path;

/** Package-local storage seams used by host integration tests. */
public final class TestExpressionArtifactStores {
    private TestExpressionArtifactStores() {}

    public static Path path(ExpressionArtifactStore store, ExpressionArtifactIdentity identity) throws IOException {
        return store.path(identity);
    }

    public static ExpressionArtifactStore failingPersistence(Path root, AtomicSharedCache cache) {
        return new ExpressionArtifactStore(root, cache, (key, target, validator, writer, gate) -> {
            throw new IOException("injected AOT persistence failure");
        }, 16);
    }
}
