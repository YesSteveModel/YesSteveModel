package com.elfmcys.ysm.natives.storage;

import java.util.Objects;

/**
 * Result delivery failed after an operation may have taken effect. This exception does not grant write-retry authority.
 *
 * <p>
 * Seam: implements delivery failure in [storage
 * execution](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public final class StorageDeliveryException extends RuntimeException {
    public enum CommitState {
        UNKNOWN, NOT_COMMITTED, COMMITTED
    }

    private final CommitState commitState;

    public StorageDeliveryException(CommitState commitState, Throwable cause) {
        super("Storage result delivery failed; commit=" + Objects.requireNonNull(commitState), cause);
        this.commitState = commitState;
    }

    public CommitState commitState() {
        return commitState;
    }

    public static StorageDeliveryException after(StorageResult<?> result, Throwable cause) {
        return new StorageDeliveryException(result.committed() ? CommitState.COMMITTED : CommitState.NOT_COMMITTED,
            cause);
    }
}
