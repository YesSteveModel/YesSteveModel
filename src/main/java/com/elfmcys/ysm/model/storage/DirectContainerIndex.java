package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Retains validated direct-container metadata and preview declarations by exact container digest. */
public final class DirectContainerIndex {
    private static final String PROFILE = "direct-inline-1/";
    private final AtomicSharedCache cache;
    private final CacheDatabase<com.elfmcys.ysm.natives.storage.NativeCacheCatalog> index;

    DirectContainerIndex(AtomicSharedCache cache) {
        this.cache = cache;
        index = cache.catalog();
    }

    Optional<UniBuffer> find(Hash256 container) throws IOException {
        try {
            var result =
                StorageIndex.require(index.read((db, cancelled) -> db.lookupDirectory(PROFILE + container, cancelled)));
            return result.outcome() == StorageResult.Outcome.MISSING ? Optional.empty()
                : Optional.of(result.rows().get(0));
        } catch (IOException failure) {
            CacheReadFailure.miss(container, failure);
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Direct metadata lookup failed: {}", container, failure);
            return Optional.empty();
        }
    }

    public ManagedContainer open(Path file, CatalogLocation location, @Nullable ManagedContainer reusable)
        throws IOException {
        var admission = ManagedContainer.openDirect(file, location, reusable, this);
        var content = admission.content();
        if (admission.validated())
            return content;
        try {
            DirectContainerAdmission.requireEmbeddedPreview(content);
            var identity = content.representation().identity();
            try {
                var prefix = content.representation().metadataPrefix().orElseThrow();
                cache.coordinator().acquire(() -> {
                    StorageIndex.require(index.execute(
                        (db, cancelled) -> db.publishDirectory(PROFILE + identity.containerId(), prefix, cancelled)));
                    return null;
                });
            } catch (IOException failure) {
                CacheReadFailure.miss(file, failure);
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Direct metadata was not persisted: {}", file, failure);
            }
            return content;
        } catch (IOException | RuntimeException | Error failure) {
            content.representation().close();
            throw failure;
        }
    }
}
