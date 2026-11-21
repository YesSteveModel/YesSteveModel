package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.model.domain.Hash256;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import static com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;

/**
 * Owns new-layout source facts, converted ownership, preview proofs and root maintenance state.
 *
 * <p>
 * Requires: the caller supplies the fixed, guarded CACHE/f root. Published files are complete and independently
 * validated; file retention and deletion exclusion belong to the region owner.
 *
 * <p>
 * Failure: operations return {@link StorageResult}; delivery failure preserves the known commit state in
 * {@link StorageDeliveryException}. Open rejects invalid existing databases without replacement. No operation retries.
 *
 * <p>
 * Execution: blocking calls belong to DatabaseWork. Operations, result copying and close are serialized per owner;
 * cancellation uses its independent token. Results are snapshots and do not retain the native owner.
 *
 * <p>
 * Seam: implements [typed cache
 * persistence](docs/architecture/model-management/storage/implementation/persistence.md#来源与-converted).
 */
public final class NativeCacheCatalog extends NativeBusinessObject implements NativeArtifactIndex {
    private static final Function<State, NativeCacheCatalog> FACTORY = NativeCacheCatalog::new;

    private NativeCacheCatalog(State state) {
        super(state);
    }

    public static NativeCacheCatalog open(Path layoutRoot, NativeCancellation cancelled) {
        return open(layoutRoot, cancelled, false);
    }

    /** Opens a required maintenance index without initializing a missing source authority. */
    public static NativeCacheCatalog openExisting(Path layoutRoot, NativeCancellation cancelled) {
        return open(layoutRoot, cancelled, true);
    }

    private static NativeCacheCatalog open(Path layoutRoot, NativeCancellation cancelled, boolean existingOnly) {
        var root = Objects.requireNonNull(layoutRoot).toAbsolutePath().normalize().toString();
        return Objects.requireNonNull(cancelled).borrow(token -> NativeStorageResult
            .open(() -> nOpen(root, existingOnly, token), handle -> adopt(handle, FACTORY)));
    }

    public StorageResult<File> lookupExact(NativeArtifactIndex.ExactKind kind, String key,
        NativeCancellation cancelled) {
        requireKind(kind);
        NativeStorageResult.key(key);
        return execute(cancelled, token -> nLookupExact(get(), kind.nativeId(), key, token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheFile).toList());
    }

    public StorageResult<Void> publishExact(NativeArtifactIndex.ExactKind kind, String key, File file,
        NativeCancellation cancelled) {
        requireKind(kind);
        NativeStorageResult.key(key);
        Objects.requireNonNull(file);
        var bytes = NativeStorageResult.bytes(file.metadata());
        return complete(cancelled, token -> nPublishExact(get(), kind.nativeId(), key, file.location().fileId(),
            file.location().instance().toString(), bytes, token));
    }

    public StorageResult<References> references(Hash256 container, NativeCancellation cancelled) {
        return referencesMany(List.of(container), cancelled);
    }

    /** Reads a nonempty, bounded batch in input order under one database snapshot. */
    public StorageResult<References> referencesMany(List<Hash256> containers, NativeCancellation cancelled) {
        return queryReferences(containers, cancelled, (fields, token) -> nReferencesMany(get(), fields, token));
    }

    public StorageResult<com.elfmcys.ysm.buffer.SharedArrayBuffer> lookupDirectory(String key,
        NativeCancellation cancelled) {
        NativeStorageResult.key(key);
        return execute(cancelled, token -> nLookupDirectory(get(), key, token),
            result -> IntStream.range(0, result.count()).mapToObj(row -> result.cacheBytes(row, 0)).toList());
    }

    public StorageResult<Void> publishDirectory(String key, com.elfmcys.ysm.buffer.UniBuffer metadata,
        NativeCancellation cancelled) {
        NativeStorageResult.key(key);
        var bytes = NativeStorageResult.bytes(metadata);
        return complete(cancelled, token -> nPublishDirectory(get(), key, bytes, token));
    }

    private static void requireKind(NativeArtifactIndex.ExactKind kind) {
        Objects.requireNonNull(kind);
        if (kind != NativeArtifactIndex.ExactKind.PREVIEW)
            throw new IllegalArgumentException("Artifact kind belongs to a different index");
    }

    public StorageResult<Source> lookupSource(String scope, String source, NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        NativeStorageResult.key(source);
        return execute(cancelled, token -> nLookupSource(get(), scope, source, token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheSource).toList());
    }

    /** Bounded source-key pages; only an empty page ends traversal. Calls share no snapshot. */
    public StorageResult<Source> pageSources(String scope, String source, NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        NativeStorageResult.key(source);
        return execute(cancelled, token -> nPageSources(get(), scope, source, token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheSource).toList());
    }

    public StorageResult<Void> publishSource(String scope, Source source, NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        Objects.requireNonNull(source);
        var object = source.converted();
        if (object.isPresent() && !scope.equals(object.get().scope()))
            throw new IllegalArgumentException("Converted scope mismatch");
        var fields = new String[] {scope, source.source(), source.identity().modelId().toString(),
            source.identity().containerId().toString(),
            object.map(value -> value.location().instance().toString()).orElse(""),
            object.map(Converted::cacheVersion).orElse("")};
        var observation = NativeStorageResult.bytes(source.observation());
        var metadata = NativeStorageResult.bytes(source.metadata());
        return complete(cancelled, token -> nPublishSource(get(), fields, object.isPresent(),
            object.map(value -> value.location().fileId()).orElse(0L), observation, metadata, token));
    }

    /** Commits one nonempty, bounded batch. Replaced sources lose their converted links and payloads. */
    public StorageResult<Void> publishSourceReferences(String scope, List<SourceReference> sources,
        NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        int size = sources.size();
        if (scope.isEmpty() || size == 0 || size > StorageResult.MAXIMUM_ROWS)
            throw new IllegalArgumentException("Invalid source reference batch");
        var fields = new String[size * 3];
        int offset = 0;
        for (var source : sources) {
            fields[offset++] = source.source();
            fields[offset++] = source.identity().modelId().toString();
            fields[offset++] = source.identity().containerId().toString();
        }
        return complete(cancelled, token -> nPublishSourceReferences(get(), scope, fields, token));
    }

    /** Register before publishing a scan; set complete only after all source facts and retirements are committed. */
    public StorageResult<Void> setScopeComplete(String scope, boolean complete, NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        return complete(cancelled, token -> nSetScopeComplete(get(), scope, complete, token));
    }

    public StorageResult<Void> finishScan(String scope, List<String> obsoleteSources, NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        if (obsoleteSources.size() >= StorageResult.MAXIMUM_ROWS)
            throw new IllegalArgumentException("Source retirement exceeds bound");
        var fields = obsoleteSources.stream().map(NativeStorageResult::key).toArray(String[]::new);
        return complete(cancelled, token -> nFinishScan(get(), scope, fields, token));
    }

    /** Includes retired and unlinked productions across all scopes and schemas, ordered by unsigned file ID. */
    public StorageResult<Converted> pageConverted(OptionalLong afterId, NativeCancellation cancelled) {
        Objects.requireNonNull(afterId);
        return execute(cancelled, token -> nPageConverted(get(), afterId.isPresent(), afterId.orElse(0), token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheConverted).toList());
    }

    /** Requires proof of first establishment or complete coverage of all applicable source domains. */
    public StorageResult<Void> setReferencesComplete(boolean complete, NativeCancellation cancelled) {
        return complete(cancelled, token -> nSetReferencesComplete(get(), complete, token));
    }

    public StorageResult<Scope> pageScopes(String afterScope, NativeCancellation cancelled) {
        NativeStorageResult.key(afterScope);
        return execute(cancelled, token -> nPageScopes(get(), afterScope, token),
            result -> IntStream.range(0, result.count())
                .mapToObj(row -> new Scope(result.cacheText(row, 0), result.cacheBoolean(row, 0))).toList());
    }

    public StorageResult<Converted> pageConvertedScope(String scope, OptionalLong afterId,
        NativeCancellation cancelled) {
        NativeStorageResult.key(scope);
        Objects.requireNonNull(afterId);
        return execute(cancelled,
            token -> nPageConvertedScope(get(), scope, afterId.isPresent(), afterId.orElse(0), token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheConverted).toList());
    }

    public StorageResult<Maintenance> readMaintenance(NativeCancellation cancelled) {
        return execute(cancelled, token -> nReadMaintenance(get(), token),
            result -> List.of(result.cacheMaintenance(0)));
    }

    /** Atomically clears the date and advances a non-wrapping revision; a plain reset preserves pending all. */
    public StorageResult<Void> resetMaintenance(boolean requestAll, NativeCancellation cancelled) {
        return complete(cancelled, token -> nResetMaintenance(get(), requestAll, token));
    }

    /**
     * After the whole prune succeeds, records UTC seconds and consumes all only if the accepted revision still matches.
     */
    public StorageResult<Void> completeMaintenance(long acceptedRevision, long utcSeconds,
        NativeCancellation cancelled) {
        if (utcSeconds < 0)
            throw new IllegalArgumentException("Negative maintenance time");
        return complete(cancelled, token -> nCompleteMaintenance(get(), acceptedRevision, utcSeconds, token));
    }

    /** Revokes only this production, preserves source facts and ownership, and rejects a different instance. */
    public StorageResult<Void> revokeConverted(Location location, NativeCancellation cancelled) {
        Objects.requireNonNull(location);
        return complete(cancelled,
            token -> nRevokeConverted(get(), location.fileId(), location.instance().toString(), token));
    }

    /** Requires physical deletion to have succeeded or found no file; a still qualified object is rejected. */
    public StorageResult<Void> forgetConverted(Location location, NativeCancellation cancelled) {
        Objects.requireNonNull(location);
        return complete(cancelled,
            token -> nForgetConverted(get(), location.fileId(), location.instance().toString(), token));
    }

    /** Includes retired ownership so allocator reconstruction cannot reuse an unfinished deletion. */
    public StorageResult<Boolean> ownsFile(long fileId, NativeCancellation cancelled) {
        return execute(cancelled, token -> nOwnsFile(get(), fileId, token),
            result -> List.of(result.cacheBoolean(0, 0)));
    }

    private static native long nOwnsFile(long handle, long id, long cancelled);

    private static native long nOpen(String root, boolean existingOnly, long cancelled);

    private static native long nLookupExact(long handle, int kind, String key, long cancelled);

    private static native long[] nPublishExact(long handle, int kind, String key, long id, String instance,
        byte[] metadata, long cancelled);

    private static native long[] nReferencesMany(long handle, String[] containers, long cancelled);

    private static native long nLookupDirectory(long handle, String key, long cancelled);

    private static native long[] nPublishDirectory(long handle, String key, byte[] metadata, long cancelled);

    private static native long nLookupSource(long handle, String scope, String source, long cancelled);

    private static native long nPageSources(long handle, String scope, String source, long cancelled);

    private static native long[] nPublishSource(long handle, String[] fields, boolean hasObject, long id,
        byte[] observation, byte[] metadata, long cancelled);

    private static native long[] nPublishSourceReferences(long handle, String scope, String[] fields, long cancelled);

    private static native long[] nSetScopeComplete(long handle, String scope, boolean complete, long cancelled);

    private static native long[] nFinishScan(long handle, String scope, String[] sources, long cancelled);

    private static native long nPageConverted(long handle, boolean hasAfter, long after, long cancelled);

    private static native long[] nSetReferencesComplete(long handle, boolean complete, long cancelled);

    private static native long nPageScopes(long handle, String after, long cancelled);

    private static native long nPageConvertedScope(long handle, String scope, boolean hasAfter, long after,
        long cancelled);

    private static native long nReadMaintenance(long handle, long cancelled);

    private static native long[] nResetMaintenance(long handle, boolean all, long cancelled);

    private static native long[] nCompleteMaintenance(long handle, long revision, long utc, long cancelled);

    private static native long[] nRevokeConverted(long handle, long id, String instance, long cancelled);

    private static native long[] nForgetConverted(long handle, long id, String instance, long cancelled);
}
