package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.elfmcys.ysm.natives.storage.NativeBakeIndex;
import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.NativeCacheRemote;
import com.elfmcys.ysm.natives.storage.StorageResult;
import com.elfmcys.ysm.version.VersionCompatibility;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/**
 * Reclaims known converted and bake productions inside a borrowed root cleanup window.
 *
 * <p>
 * Requires: the startup owner has completed observation, final consistency and source-index publication for every
 * enabled source root in this region. Unobserved persistent scopes remain authoritative. The caller holds the root's
 * continuous cleanup window through this result and its maintenance commit. Database workers never acquire that gate.
 *
 * <p>
 * Failure: ordinary I/O returns a failed result with completed reclamations preserved. Cancellation, closure, internal
 * failures and Error propagate. This owner neither schedules cleanup nor changes maintenance dates.
 *
 * <p>
 * Seam: implements [shared reclamation](docs/architecture/model-management/storage/overview.md#共享存储与清理).
 */
public final class CachePruner {
    private final AtomicSharedCache cache;
    private final CacheDatabase<NativeCacheCatalog> catalog;
    private final CacheDatabase<NativeCacheRemote> remote;
    private final CacheDatabase<NativeBakeIndex> bakeReads;
    private final CacheDatabase<NativeBakeIndex> bakeWrites;
    private final Checkpoint checkpoint;

    public CachePruner(AtomicSharedCache cache) {
        this(cache, (phase, pool, location) -> {
        });
    }

    CachePruner(AtomicSharedCache cache, Checkpoint checkpoint) {
        this.cache = java.util.Objects.requireNonNull(cache);
        this.checkpoint = java.util.Objects.requireNonNull(checkpoint);
        catalog = new CacheDatabase<>(cache, NativeCacheCatalog::openExisting);
        remote = new CacheDatabase<>(cache, NativeCacheRemote::openExisting);
        bakeReads = new CacheDatabase<>(cache,
            (root, token) -> NativeBakeIndex.openReadOnly(root, token)
                .orElseThrow(() -> new StorageResult.OpenException(
                    new StorageResult<>(StorageResult.Outcome.MISSING, StorageResult.Failure.NONE, false, List.of()))));
        bakeWrites = new CacheDatabase<>(cache, NativeBakeIndex::open);
    }

    /**
     * Establishes coverage before startup delivers consumers, only when no bake index has been established. Unknown
     * files remain unowned. An existing bake database, including an empty one, never grants this proof. Requires the
     * borrowed cleanup window; returns false when existing bake provenance must remain unchanged.
     */
    public boolean initializeReferences() throws IOException {
        cache.coordinator().requireCleanup();
        var inventory =
            StorageIndex.require(bakeReads.read((db, token) -> db.pageObjects(OptionalLong.empty(), token)));
        if (inventory.outcome() != StorageResult.Outcome.MISSING)
            return false;
        committed(cache.catalog().execute((db, token) -> db.setReferencesComplete(true, token)));
        committed(cache.remoteIndex().execute((db, token) -> db.setReferencesComplete(true, token)));
        return true;
    }

    /**
     * Accepts the complete scopes committed by this startup. Other scopes retain their persisted relationships.
     * removeOtherSchemas is true for local, and for global only after the caller accepts its pending all request. The
     * result does not release the borrowed cleanup window or record a successful maintenance cycle.
     */
    public Result prune(Set<String> fullyObservedScopes, boolean removeOtherSchemas) throws IOException {
        cache.coordinator().requireCleanup();
        var scopes = Set.copyOf(fullyObservedScopes);
        if (scopes.isEmpty())
            return new Result(Status.INCOMPLETE_OBSERVATION, 0, 0, Optional.empty());
        long convertedCount = 0;
        long bakeCount = 0;
        Phase phase = Phase.OBSERVE;
        Pool pool = Pool.CONVERTED;
        Location location = null;
        try {
            checkpoint.reached(phase, pool, null);
            var converted = convertedObjects();
            // Required authorities must be readable before either pool loses a qualification.
            var probe = new Hash256(new byte[Hash256.SIZE]);
            found(remote.read((db, token) -> db.references(probe, token)));
            for (var scope : scopes)
                committed(catalog.execute((db, token) -> db.setScopeComplete(scope, true, token)));
            var associations = new HashSet<Location>();
            var afterScope = "";
            while (true) {
                var cursor = afterScope;
                var page = found(catalog.read((db, token) -> db.pageScopes(cursor, token)));
                if (page.isEmpty())
                    break;
                for (var scope : page) {
                    var afterSource = "";
                    while (true) {
                        var sourceCursor = afterSource;
                        var sources =
                            found(catalog.read((db, token) -> db.pageSources(scope.scope(), sourceCursor, token)));
                        if (sources.isEmpty())
                            break;
                        for (var source : sources) {
                            source.converted().ifPresent(object -> associations.add(object.location()));
                            afterSource = source.source();
                        }
                    }
                    afterScope = scope.scope();
                }
            }
            pool = Pool.BAKE;
            checkpoint.reached(Phase.OBSERVE, pool, null);
            var bake = bakeObjects();
            var references = new HashMap<Hash256, ReferenceState>();
            var pending = new LinkedHashSet<Hash256>();
            for (var object : bake) {
                var container = object.key().partition().container();
                if (!references.containsKey(container))
                    pending.add(container);
                if (pending.size() == StorageResult.MAXIMUM_ROWS) {
                    readReferenceBatch(pending, references);
                    pending.clear();
                }
            }
            if (!pending.isEmpty())
                readReferenceBatch(pending, references);
            pool = Pool.CONVERTED;
            for (var object : converted) {
                location = object.location();
                if (cache.coordinator().retained(pool, location))
                    continue;
                if (!(scopes.contains(object.scope()) && !associations.contains(location))
                    && !(removeOtherSchemas && !AbiVersion.MODEL_SCHEMA.equals(object.cacheVersion())))
                    continue;
                phase = Phase.REVOKE;
                checkpoint.reached(phase, pool, location);
                var revoked =
                    StorageIndex.require(catalog.execute((db, token) -> db.revokeConverted(object.location(), token)));
                if (!revoked.committed())
                    throw new IOException("Converted production changed during cleanup");
                phase = Phase.DELETE;
                delete(pool, location);
                phase = Phase.FORGET;
                checkpoint.reached(phase, pool, location);
                committed(catalog.execute((db, token) -> db.forgetConverted(object.location(), token)));
                convertedCount++;
            }
            pool = Pool.BAKE;
            for (var object : bake) {
                location = object.file().location();
                if (cache.coordinator().retained(pool, location))
                    continue;
                var partition = object.key().partition();
                if (references.get(partition.container()) != ReferenceState.UNREFERENCED
                    && abiSupport(partition.kind(), object.key().abi()) != AbiSupport.UNSUPPORTED
                    && !(removeOtherSchemas && !schema(partition.kind()).equals(partition.cacheVersion())))
                    continue;
                phase = Phase.REVOKE;
                checkpoint.reached(phase, pool, location);
                committed(bakeWrites.execute((db, token) -> db.revoke(object.file().location(), token)));
                phase = Phase.DELETE;
                delete(pool, location);
                phase = Phase.FORGET;
                checkpoint.reached(phase, pool, location);
                committed(bakeWrites.execute((db, token) -> db.forget(object.file().location(), token)));
                bakeCount++;
            }
            return new Result(Status.COMPLETED, convertedCount, bakeCount, Optional.empty());
        } catch (IOException | SecurityException failure) {
            if (failure instanceof InterruptedIOException || failure instanceof ClosedChannelException)
                throw failure;
            return new Result(Status.FAILED, convertedCount, bakeCount,
                Optional.of(new Failure(phase, pool, Optional.ofNullable(location),
                    failure instanceof IOException io ? io : new IOException("Cache cleanup access denied", failure))));
        }
    }

    private List<Converted> convertedObjects() throws IOException {
        var objects = new ArrayList<Converted>();
        var after = OptionalLong.empty();
        while (true) {
            var cursor = after;
            var page = found(catalog.read((db, token) -> db.pageConverted(cursor, token)));
            if (page.isEmpty())
                return objects;
            objects.addAll(page);
            after = OptionalLong.of(page.get(page.size() - 1).location().fileId());
        }
    }

    private List<BakeObject> bakeObjects() throws IOException {
        var objects = new ArrayList<BakeObject>();
        var after = OptionalLong.empty();
        while (true) {
            var cursor = after;
            var result = StorageIndex.require(bakeReads.read((db, token) -> db.pageObjects(cursor, token)));
            var page = result.rows();
            if (page.isEmpty())
                return objects;
            objects.addAll(page);
            after = OptionalLong.of(page.get(page.size() - 1).file().location().fileId());
        }
    }

    private void delete(Pool pool, Location location) throws IOException {
        checkpoint.reached(Phase.DELETE, pool, location);
        try {
            var observation = cache.observe(cache.path(pool, location.fileId()), false);
            observation.deleteIfExists(observation.fileIdentity());
        } catch (NoSuchFileException missing) {
            // The original production no longer has physical backing.
        }
    }

    private void readReferenceBatch(Set<Hash256> pending, Map<Hash256, ReferenceState> references) throws IOException {
        if (!references.isEmpty())
            checkpoint.reached(Phase.OBSERVE, Pool.BAKE, null);
        var batch = List.copyOf(pending);
        var local = found(catalog.read((db, token) -> db.referencesMany(batch, token)));
        var downloaded = found(remote.read((db, token) -> db.referencesMany(batch, token)));
        for (int row = 0; row < batch.size(); row++) {
            var localState = local.get(row).state();
            var remoteState = downloaded.get(row).state();
            references.put(batch.get(row),
                localState == ReferenceState.REFERENCED || remoteState == ReferenceState.REFERENCED
                    ? ReferenceState.REFERENCED
                    : localState == ReferenceState.UNREFERENCED && remoteState == ReferenceState.UNREFERENCED
                        ? ReferenceState.UNREFERENCED : ReferenceState.UNKNOWN);
        }
    }

    private static <T> List<T> found(StorageResult<T> result) throws IOException {
        StorageIndex.require(result);
        if (result.outcome() != StorageResult.Outcome.FOUND)
            throw new IOException("Required cleanup index is missing");
        return result.rows();
    }

    private static void committed(StorageResult<?> result) throws IOException {
        StorageIndex.require(result);
        if (!result.committed())
            throw new IOException("Cleanup mutation did not commit");
    }

    private static String schema(BakeKind kind) {
        return kind == BakeKind.GEOMETRY ? AbiVersion.CACHE_GEOMETRY : AbiVersion.CACHE_ANIMATION;
    }

    public static AbiSupport abiSupport(BakeKind kind, String candidate) {
        return abiSupport(schema(kind), candidate);
    }

    static AbiSupport abiSupport(String currentSchema, String candidate) {
        if (candidate.isEmpty())
            return AbiSupport.UNKNOWN;
        return VersionCompatibility.isCompatible(currentSchema, candidate,
            (current, stored) -> new DefaultArtifactVersion(current).equals(new DefaultArtifactVersion(stored)))
                ? AbiSupport.SUPPORTED : AbiSupport.UNSUPPORTED;
    }

    public enum AbiSupport {
        SUPPORTED, UNSUPPORTED, UNKNOWN
    }

    public enum Status {
        COMPLETED, INCOMPLETE_OBSERVATION, FAILED
    }

    public enum Phase {
        OBSERVE, REVOKE, DELETE, FORGET
    }

    public record Failure(Phase phase, Pool pool, Optional<Location> location, IOException cause) {
    }

    public record Result(Status status, long convertedReclaimed, long bakeReclaimed, Optional<Failure> failure) {
        public boolean completed() {
            return status == Status.COMPLETED;
        }
    }

    @FunctionalInterface
    interface Checkpoint {
        void reached(Phase phase, Pool pool, Location location) throws IOException;
    }
}
