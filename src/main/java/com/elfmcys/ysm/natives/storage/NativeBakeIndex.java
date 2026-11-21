package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import static com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;

/**
 * Owns new-layout geometry and complete animation-set qualifications and retained object ownership.
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
 * persistence](docs/architecture/model-management/storage/derived-artifacts.md#seam-contract).
 */
public final class NativeBakeIndex extends NativeBusinessObject {
    private static final Function<State, NativeBakeIndex> FACTORY = NativeBakeIndex::new;

    private NativeBakeIndex(State state) {
        super(state);
    }

    public static NativeBakeIndex open(Path layoutRoot, NativeCancellation cancelled) {
        var root = Objects.requireNonNull(layoutRoot).toAbsolutePath().normalize().toString();
        return Objects.requireNonNull(cancelled).borrow(
            token -> NativeStorageResult.open(() -> nOpen(root, false, token), handle -> adopt(handle, FACTORY)));
    }

    public StorageResult<Void> revoke(Location location, NativeCancellation cancelled) {
        Objects.requireNonNull(location);
        return complete(cancelled, token -> nRevoke(get(), location.fileId(), location.instance().toString(), token));
    }

    /** Requires physical deletion to have succeeded or found no file; a qualified object is rejected. */
    public StorageResult<Void> forget(Location location, NativeCancellation cancelled) {
        Objects.requireNonNull(location);
        return complete(cancelled, token -> nForget(get(), location.fileId(), location.instance().toString(), token));
    }

    /** An absent new-layout database returns empty; access, identity and content failures remain failures. */
    public static Optional<NativeBakeIndex> openReadOnly(Path layoutRoot, NativeCancellation cancelled) {
        var root = Objects.requireNonNull(layoutRoot).toAbsolutePath().normalize().toString();
        try {
            return Optional.of(Objects.requireNonNull(cancelled).borrow(
                token -> NativeStorageResult.open(() -> nOpen(root, true, token), handle -> adopt(handle, FACTORY))));
        } catch (StorageResult.OpenException failure) {
            if (failure.result().outcome() == StorageResult.Outcome.MISSING)
                return Optional.empty();
            throw failure;
        }
    }

    public StorageResult<File> lookupExact(BakeKey key, NativeCancellation cancelled) {
        Objects.requireNonNull(key);
        var fields = key.fields();
        return execute(cancelled, token -> nLookupExact(get(), key.partition().kind().ordinal(), fields, token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheFile).toList());
    }

    /** A negative result avoids constructing expected; a positive result does not validate any payload. */
    public StorageResult<Boolean> hasPartition(Partition partition, NativeCancellation cancelled) {
        Objects.requireNonNull(partition);
        var fields = partition.fields();
        return execute(cancelled, token -> nHasPartition(get(), partition.kind().ordinal(), fields, token),
            result -> List.of(result.cacheBoolean(0, 0)));
    }

    public StorageResult<Void> publish(BakeObject object, NativeCancellation cancelled) {
        Objects.requireNonNull(object);
        var fields = object.key().fields();
        var file = object.file();
        var bytes = NativeStorageResult.bytes(file.metadata());
        return complete(cancelled, token -> nPublish(get(), object.key().partition().kind().ordinal(), fields,
            file.location().fileId(), file.location().instance().toString(), bytes, token));
    }

    /** Includes retired productions and every ABI/schema, ordered by unsigned file ID; pages share no snapshot. */
    public StorageResult<BakeObject> pageObjects(OptionalLong afterId, NativeCancellation cancelled) {
        Objects.requireNonNull(afterId);
        return execute(cancelled, token -> nPageObjects(get(), afterId.isPresent(), afterId.orElse(0), token),
            result -> IntStream.range(0, result.count()).mapToObj(result::cacheBakeObject).toList());
    }

    /** Includes retired ownership so allocator reconstruction cannot reuse an unfinished deletion. */
    public StorageResult<Boolean> ownsFile(long fileId, NativeCancellation cancelled) {
        return execute(cancelled, token -> nOwnsFile(get(), fileId, token),
            result -> List.of(result.cacheBoolean(0, 0)));
    }

    private static native long nOwnsFile(long handle, long id, long cancelled);

    private static native long nOpen(String root, boolean readOnly, long cancelled);

    private static native long[] nRevoke(long handle, long id, String instance, long cancelled);

    private static native long[] nForget(long handle, long id, String instance, long cancelled);

    private static native long nLookupExact(long handle, int kind, String[] fields, long cancelled);

    private static native long nHasPartition(long handle, int kind, String[] fields, long cancelled);

    private static native long[] nPublish(long handle, int kind, String[] fields, long id, String instance,
        byte[] metadata, long cancelled);

    private static native long nPageObjects(long handle, boolean hasAfter, long after, long cancelled);
}
