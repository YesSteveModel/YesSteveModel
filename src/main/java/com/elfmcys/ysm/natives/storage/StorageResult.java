package com.elfmcys.ysm.natives.storage;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A complete storage observation. Committed reports the operation's actual storage effect, independently of file
 * publication and runtime acceptance. Payloads are Java-owned snapshots.
 *
 * <p>
 * Seam: implements the stable result boundary in [storage
 * execution](docs/architecture/model-management/storage/overview.md#数据库执行).
 */
public record StorageResult<T>(Outcome outcome, Failure failure, boolean committed, List<T> rows) {

    public static final int MAXIMUM_BYTES = 1024 * 1024;
    public static final int MAXIMUM_ROWS = 64;

    public StorageResult {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(failure);
        rows = List.copyOf(rows);
    }

    public enum Outcome {
        FOUND, MISSING, COMMITTED, REJECTED, FAILED, CANCELLED,
        /** Java logical coalescing only; this operation did not execute a database effect. */
        SUPERSEDED
    }

    public enum Failure {
        NONE, RESOURCE_BUSY, CAPACITY, ACCESS_IO, INCOMPATIBLE, CONTENT, CLOSED, CANCELLED, INTERNAL
    }

    public boolean succeeded() {
        return outcome == Outcome.FOUND || outcome == Outcome.MISSING || outcome == Outcome.COMMITTED
            || outcome == Outcome.SUPERSEDED;
    }

    public <U> StorageResult<U> map(Function<T, U> mapper) {
        return new StorageResult<>(outcome, failure, committed, rows.stream().map(mapper).toList());
    }

    public static <T> StorageResult<T> refused(Failure failure) {
        return new StorageResult<>(failure == Failure.CANCELLED ? Outcome.CANCELLED : Outcome.REJECTED, failure, false,
            List.of());
    }

    public static final class OpenException extends RuntimeException {
        private final StorageResult<?> result;

        public OpenException(StorageResult<?> result) {
            super("Storage open failed: " + result.failure());
            this.result = result;
        }

        public StorageResult<?> result() {
            return result;
        }
    }
}
