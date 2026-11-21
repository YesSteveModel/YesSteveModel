package com.elfmcys.ysm.model.storage;

import org.jetbrains.annotations.Nullable;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Flat converted pool. The caller owns staging through conversion and complete publication. */
public final class ConvertedObjectStore {
    private final AtomicSharedCache cache;

    public ConvertedObjectStore(AtomicSharedCache cache) {
        this.cache = Objects.requireNonNull(cache);
    }

    public AtomicSharedCache.Staging stage() throws IOException {
        return cache.reserve(Pool.CONVERTED);
    }

    public Optional<ModelFileIdentity> findIdentity(ConvertedSourceIndex entry) throws IOException {
        return findIdentity(entry, null);
    }

    public Optional<ModelFileIdentity> findIdentity(ConvertedSourceIndex entry, @Nullable String originVer)
        throws IOException {
        cache.coordinator().retain(Pool.CONVERTED, entry.location());
        try (var representation = ManagedContainer.readConvertedMetadata(entry, cache)) {
            if (!representation.identity().equals(entry.identity()))
                return Optional.empty();
            if (originVer != null
                && !originVer.equals(representation.view().getManifest().info().properties().originVer()))
                return Optional.empty();
            return Optional.of(representation.identity());
        } catch (IOException invalid) {
            CacheReadFailure.miss(entry.file(), invalid);
            return Optional.empty();
        }
    }

    public Stored commit(RawCompileResult compiled, CatalogLocation location, AtomicSharedCache.Staging staging)
        throws IOException {
        if (!compiled.stagedContainer().equals(staging.path()))
            throw new IllegalArgumentException("Compiler did not use caller-owned staging");
        final ModelFileIdentity identity;
        final com.elfmcys.ysm.buffer.SharedArrayBuffer metadata;
        try (var checked = ManagedContainer.verifyCachedFile(staging.file())) {
            identity = checked.identity();
            metadata = checked.metadataPrefix().orElseThrow().shareArray();
        }
        if (!compiled.modelHash().equals(identity.modelId()))
            throw new ModelHashMismatchException(location, compiled.modelHash(), identity.modelId());
        // The same exclusively owned channel has not changed since complete verification.
        var target = staging.publish(file -> true, AtomicSharedCache.AtomicReplacement::run);
        return new Stored(staging.location(), identity, target, metadata);
    }

    /** Requires the cleanup owner's continuous exclusive window through revocation and physical deletion. */
    public boolean delete(Converted object) throws IOException {
        return cache.coordinator().acquire(() -> {
            cache.coordinator().requireCleanup();
            if (cache.coordinator().retained(Pool.CONVERTED, object.location()))
                return false;
            var revoked = cache.catalog().execute((db, token) -> db.revokeConverted(object.location(), token));
            if (revoked.outcome() == com.elfmcys.ysm.natives.storage.StorageResult.Outcome.REJECTED
                && revoked.failure() == com.elfmcys.ysm.natives.storage.StorageResult.Failure.NONE)
                return false;
            StorageIndex.require(revoked);
            if (!revoked.committed())
                return false;
            try {
                var observation = cache.observe(cache.path(Pool.CONVERTED, object.location().fileId()), false);
                observation.deleteIfExists(observation.fileIdentity());
            } catch (java.nio.file.NoSuchFileException missing) {
                // The original instance already has no physical backing.
            }
            var forgotten = StorageIndex
                .require(cache.catalog().execute((db, token) -> db.forgetConverted(object.location(), token)));
            if (!forgotten.committed())
                throw new IOException("Converted ownership changed during reclamation");
            return true;
        });
    }

    public record Stored(Location location, ModelFileIdentity identity, Path file,
        com.elfmcys.ysm.buffer.SharedArrayBuffer metadata) {
        public java.util.UUID instance() {
            return location.instance();
        }
    }
}
