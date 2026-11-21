package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.model.domain.Hash256;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import static com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;

/**
 * Owns new-layout remote exact locations, accepted content references and usage.
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
 * Seam: implements [typed cache persistence](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public final class NativeCacheRemote extends NativeBusinessObject implements NativeArtifactIndex {
    private static final Function<State, NativeCacheRemote> FACTORY = NativeCacheRemote::new;

    private NativeCacheRemote(State state) {
        super(state);
    }

    public static NativeCacheRemote open(Path layoutRoot, NativeCancellation cancelled) {
        return open(layoutRoot, cancelled, false);
    }

    /** Opens a required maintenance index without initializing a missing source authority. */
    public static NativeCacheRemote openExisting(Path layoutRoot, NativeCancellation cancelled) {
        return open(layoutRoot, cancelled, true);
    }

    private static NativeCacheRemote open(Path layoutRoot, NativeCancellation cancelled, boolean existingOnly) {
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

    /** Publishes verified metadata and its accepted source identity in one transaction. */
    public StorageResult<Void> publishMetadata(String source, File file, ModelFileIdentity identity,
        NativeCancellation cancelled) {
        NativeStorageResult.key(source);
        Objects.requireNonNull(file);
        Objects.requireNonNull(identity);
        var bytes = NativeStorageResult.bytes(file.metadata());
        return complete(cancelled,
            token -> nPublishMetadata(get(), source, file.location().fileId(), file.location().instance().toString(),
                bytes, identity.modelId().toString(), identity.containerId().toString(), token));
    }

    public StorageResult<References> references(Hash256 container, NativeCancellation cancelled) {
        return referencesMany(List.of(container), cancelled);
    }

    /** Reads a nonempty, bounded batch in input order under one database snapshot. */
    public StorageResult<References> referencesMany(List<Hash256> containers, NativeCancellation cancelled) {
        return queryReferences(containers, cancelled, (fields, token) -> nReferencesMany(get(), fields, token));
    }

    private static void requireKind(NativeArtifactIndex.ExactKind kind) {
        Objects.requireNonNull(kind);
        if (kind != NativeArtifactIndex.ExactKind.METADATA && kind != NativeArtifactIndex.ExactKind.CHUNK
            && kind != NativeArtifactIndex.ExactKind.PREVIEW)
            throw new IllegalArgumentException("Artifact kind belongs to a different index");
    }

    /** Source retention is an accepted full content identity; mere metadata/chunk presence is insufficient. */
    public StorageResult<Void> setReference(String source, ModelFileIdentity identity, boolean present,
        NativeCancellation cancelled) {
        NativeStorageResult.key(source);
        Objects.requireNonNull(identity);
        return complete(cancelled, token -> nSetReference(get(), source, identity.modelId().toString(),
            identity.containerId().toString(), present, token));
    }

    /** Requires proof of first establishment or complete coverage of all applicable remote sources. */
    public StorageResult<Void> setReferencesComplete(boolean complete, NativeCancellation cancelled) {
        return complete(cancelled, token -> nSetReferencesComplete(get(), complete, token));
    }

    public StorageResult<Void> maxUsage(com.elfmcys.ysm.model.domain.Hash256 container, long utcSeconds,
        NativeCancellation cancelled) {
        Objects.requireNonNull(container);
        if (utcSeconds < 0)
            throw new IllegalArgumentException("Negative usage time");
        return complete(cancelled, token -> nMaxUsage(get(), container.toString(), utcSeconds, token));
    }

    public StorageResult<Long> readUsage(com.elfmcys.ysm.model.domain.Hash256 container, NativeCancellation cancelled) {
        Objects.requireNonNull(container);
        return queryUsage(cancelled, token -> nReadUsage(get(), container.toString(), token));
    }

    private static native long nOpen(String root, boolean existingOnly, long cancelled);

    private static native long nLookupExact(long handle, int kind, String key, long cancelled);

    private static native long[] nPublishExact(long handle, int kind, String key, long id, String instance,
        byte[] metadata, long cancelled);

    private static native long[] nPublishMetadata(long handle, String source, long id, String instance, byte[] metadata,
        String model, String container, long cancelled);

    private static native long[] nReferencesMany(long handle, String[] containers, long cancelled);

    private static native long[] nSetReference(long handle, String source, String model, String container,
        boolean present, long cancelled);

    private static native long[] nSetReferencesComplete(long handle, boolean complete, long cancelled);

    private static native long[] nMaxUsage(long handle, String container, long utc, long cancelled);

    private static native long[] nReadUsage(long handle, String container, long cancelled);
}
