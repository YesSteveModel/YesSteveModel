package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.natives.NativeCancellation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Holds one scope's continuous consumer registration. The scope owner seals local object acquisition before
 * beginCleanup and resumes it only after endCleanup succeeds. Close requires all disk users to have ended.
 *
 * <p>
 * Failure: failed restoration retains admission; the owner drains the scope before closing this resource. A busy
 * cleanup returns REJECTED with this consumer's protection retained or restored. Database files must never be replaced
 * or removed.
 *
 * <p>
 * Seam: implements [consumer coordination](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public final class NativeCoordination extends NativeBusinessObject {
    private static final Function<State, NativeCoordination> FACTORY = NativeCoordination::new;

    private NativeCoordination(State state) {
        super(state);
    }

    public static NativeCoordination open(Path scope, NativeCancellation cancelled) {
        var location = Objects.requireNonNull(scope).toAbsolutePath().normalize().toString();
        return Objects.requireNonNull(cancelled)
            .borrow(token -> NativeStorageResult.open(() -> nOpen(location, token), handle -> adopt(handle, FACTORY)));
    }

    public StorageResult<Void> beginCleanup(NativeCancellation cancelled) {
        return complete(cancelled, token -> nBeginCleanup(get(), token));
    }

    /** Restores the real SHARED transaction before opening admission, including after cancellation. */
    public StorageResult<Void> endCleanup() {
        return recover(() -> nEndCleanup(get()));
    }

    private static native long nOpen(String path, long cancelled);

    private static native long[] nBeginCleanup(long handle, long cancelled);

    private static native long[] nEndCleanup(long handle);
}
