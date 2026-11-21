package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.function.Function;

/**
 * Owns the region-wide uint64 allocation sequence; allocation does not reserve a file or authorize reuse.
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
public final class NativeFileIdAllocator extends NativeBusinessObject {
    private static final Function<State, NativeFileIdAllocator> FACTORY = NativeFileIdAllocator::new;

    private NativeFileIdAllocator(State state) {
        super(state);
    }

    public static NativeFileIdAllocator open(Path layoutRoot, NativeCancellation cancelled) {
        var root = Objects.requireNonNull(layoutRoot).toAbsolutePath().normalize().toString();
        return Objects.requireNonNull(cancelled)
            .borrow(token -> NativeStorageResult.open(() -> nOpen(root, token), handle -> adopt(handle, FACTORY)));
    }

    public StorageResult<Long> allocate(NativeCancellation cancelled) {
        return execute(cancelled, token -> nAllocate(get(), token), result -> List.of(result.cacheNumber(0, 0)));
    }

    private static native long nOpen(String root, long cancelled);

    private static native long nAllocate(long handle, long cancelled);
}
