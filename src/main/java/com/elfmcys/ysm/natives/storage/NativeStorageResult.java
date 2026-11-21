package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.NativeObject;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns one operation's result until its complete payload has been copied. No native view escapes this package. */
final class NativeStorageResult extends NativeObject {
    private static final Function<State, NativeStorageResult> FACTORY = NativeStorageResult::new;
    private static final StorageResult.Outcome[] OUTCOMES =
        java.util.Arrays.copyOf(StorageResult.Outcome.values(), StorageResult.Outcome.SUPERSEDED.ordinal());
    private static final StorageResult.Failure[] FAILURES = StorageResult.Failure.values();
    private StorageResult.Outcome outcome;
    private StorageResult.Failure failure;
    private boolean headerKnown;
    private boolean committed;
    private int count;

    private NativeStorageResult(State state) {
        super(state);
    }

    static StorageResult<Void> terminal(Supplier<long[]> operation) {
        return simple(operation, value -> {
            if (value.length != 4 || value[3] != 0)
                throw new IllegalStateException("Native storage terminal result contains a payload");
            return List.of();
        });
    }

    static StorageResult<Long> usage(Supplier<long[]> operation) {
        return simple(operation, value -> {
            if (value[0] == StorageResult.Outcome.FOUND.ordinal()) {
                if (value.length != 5 || value[3] != 1 || value[4] < 0)
                    throw new IllegalStateException("Incomplete native usage result");
                return List.of(value[4]);
            }
            if (value.length != 4 || value[3] != 0 || value[0] == StorageResult.Outcome.COMMITTED.ordinal())
                throw new IllegalStateException("Invalid native usage result");
            return List.of();
        });
    }

    static StorageResult<CacheStorageTypes.References> references(Supplier<long[]> operation, int expected) {
        return simple(operation, value -> {
            if (value[0] != StorageResult.Outcome.FOUND.ordinal()) {
                if (value.length != 4 || value[3] != 0 || value[0] == StorageResult.Outcome.COMMITTED.ordinal())
                    throw new IllegalStateException("Invalid native reference batch result");
                return List.of();
            }
            if (value[2] != 0 || value[3] != expected || value.length != 6 + expected)
                throw new IllegalStateException("Incomplete native reference batch result");
            var states = CacheStorageTypes.ReferenceState.values();
            var rows = new ArrayList<CacheStorageTypes.References>(expected);
            for (int i = 0; i < expected; i++) {
                long state = value[6 + i];
                if (state < 0 || state >= states.length)
                    throw new IllegalStateException("Invalid native reference state");
                rows.add(new CacheStorageTypes.References(states[(int)state], value[4], value[5]));
            }
            return rows;
        });
    }

    static <T> StorageResult<T> simple(Supplier<long[]> operation, Function<long[], List<T>> payload) {
        var commit = StorageDeliveryException.CommitState.UNKNOWN;
        try {
            var value = operation.get();
            validateHeader(value);
            commit = value[2] != 0 ? StorageDeliveryException.CommitState.COMMITTED
                : StorageDeliveryException.CommitState.NOT_COMMITTED;
            validateHeaderSemantics(value);
            var rows = payload.apply(value);
            if (rows.size() != value[3])
                throw new IllegalStateException("Incomplete native storage result payload");
            return new StorageResult<>(OUTCOMES[(int)value[0]], FAILURES[(int)value[1]], value[2] != 0, rows);
        } catch (RuntimeException | Error failure) {
            throw new StorageDeliveryException(commit, failure);
        }
    }

    static <T> StorageResult<T> read(LongSupplier operation, Function<NativeStorageResult, List<T>> payload) {
        NativeStorageResult result = null;
        try {
            result = receive(operation.getAsLong());
            result.readHeader();
            try {
                var values = result.succeeded() ? payload.apply(result) : List.<T>of();
                return result.result(values);
            } catch (InvalidValue invalid) {
                return new StorageResult<>(StorageResult.Outcome.FAILED, StorageResult.Failure.CONTENT,
                    result.committed, List.of());
            }
        } catch (StorageDeliveryException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw delivery(result, failure);
        } finally {
            if (result != null)
                result.close();
        }
    }

    static <T> T open(LongSupplier operation, LongFunction<T> adoption) {
        NativeStorageResult result = null;
        try {
            result = receive(operation.getAsLong());
            result.readHeader();
            if (result.outcome != StorageResult.Outcome.FOUND)
                throw new StorageResult.OpenException(result.result(List.of()));
            if (result.count != 0 || result.committed)
                throw new IllegalStateException("Native storage open returned an operation payload");
            long owner = nTakeOwner(result.get());
            if (owner == 0)
                throw new IllegalStateException("Native storage did not deliver its owner");
            return adoption.apply(owner);
        } catch (StorageResult.OpenException | StorageDeliveryException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw delivery(result, failure);
        } finally {
            if (result != null)
                result.close();
        }
    }

    private static NativeStorageResult receive(long handle) {
        if (handle == 0)
            throw new IllegalStateException("Native storage did not deliver a result");
        return adopt(handle, FACTORY);
    }

    private static StorageDeliveryException delivery(NativeStorageResult result, Throwable failure) {
        var commit =
            result == null || !result.headerKnown ? StorageDeliveryException.CommitState.UNKNOWN : result.committed
                ? StorageDeliveryException.CommitState.COMMITTED : StorageDeliveryException.CommitState.NOT_COMMITTED;
        return new StorageDeliveryException(commit, failure);
    }

    private void readHeader() {
        var value = nHeader(get());
        validateHeader(value);
        outcome = OUTCOMES[(int)value[0]];
        failure = FAILURES[(int)value[1]];
        committed = value[2] != 0;
        count = (int)value[3];
        headerKnown = true;
        if (value.length != 4)
            throw new IllegalStateException("Native result owner returned an extended header");
        validateHeaderSemantics(value);
    }

    private static void validateHeader(long[] value) {
        if (value == null || value.length < 4 || value[0] < 0 || value[0] >= OUTCOMES.length || value[1] < 0
            || value[1] >= FAILURES.length || value[2] < 0 || value[2] > 1 || value[3] < 0
            || value[3] > StorageResult.MAXIMUM_ROWS)
            throw new IllegalStateException("Incomplete native storage result header");
    }

    private static void validateHeaderSemantics(long[] value) {
        var outcome = OUTCOMES[(int)value[0]];
        var failure = FAILURES[(int)value[1]];
        boolean succeeded = outcome == StorageResult.Outcome.FOUND || outcome == StorageResult.Outcome.MISSING
            || outcome == StorageResult.Outcome.COMMITTED;
        if ((succeeded && failure != StorageResult.Failure.NONE)
            || ((outcome == StorageResult.Outcome.FAILED || outcome == StorageResult.Outcome.CANCELLED)
                && failure == StorageResult.Failure.NONE)
            || (outcome == StorageResult.Outcome.COMMITTED && value[2] == 0)
            || ((!succeeded || outcome == StorageResult.Outcome.MISSING) && value[3] != 0))
            throw new IllegalStateException("Inconsistent native storage result header");
    }

    int count() {
        return count;
    }

    String preferenceIdentity(int index) {
        return require(nPreferenceIdentity(get(), index));
    }

    SharedArrayBuffer preferenceValue(int index) {
        return buffer(nPreferenceValue(get(), index));
    }

    long preferenceTime(int index) {
        return nonnegative(nPreferenceTime(get(), index));
    }

    String cacheText(int row, int field) {
        return require(nCacheText(get(), row, field));
    }

    long cacheNumber(int row, int field) {
        var value = nCacheNumber(get(), row, field);
        if (value == null || value.length != 1)
            throw new IllegalStateException("Missing native cache number");
        return value[0];
    }

    SharedArrayBuffer cacheBytes(int row, int field) {
        return buffer(nCacheBytes(get(), row, field));
    }

    boolean cacheBoolean(int row, int field) {
        long value = cacheNumber(row, field);
        if (value != 0 && value != 1)
            throw invalidValue(new IllegalArgumentException("Invalid native boolean"));
        return value != 0;
    }

    CacheStorageTypes.Location cacheLocation(int row, int numberField, int textField) {
        try {
            return new CacheStorageTypes.Location(cacheNumber(row, numberField),
                java.util.UUID.fromString(cacheText(row, textField)));
        } catch (IllegalArgumentException invalid) {
            throw invalidValue(invalid);
        }
    }

    com.elfmcys.ysm.model.domain.ModelFileIdentity cacheIdentity(String model, String container) {
        try {
            return new com.elfmcys.ysm.model.domain.ModelFileIdentity(com.elfmcys.ysm.model.domain.ModelId.parse(model),
                com.elfmcys.ysm.model.domain.Hash256.parse(container));
        } catch (IllegalArgumentException invalid) {
            throw invalidValue(invalid);
        }
    }

    CacheStorageTypes.File cacheFile(int row) {
        return new CacheStorageTypes.File(cacheLocation(row, 0, 0), cacheBytes(row, 0));
    }

    CacheStorageTypes.Converted cacheConverted(int row) {
        return new CacheStorageTypes.Converted(cacheLocation(row, 0, 0), cacheText(row, 1), cacheText(row, 2),
            cacheIdentity(cacheText(row, 3), cacheText(row, 4)), cacheBoolean(row, 1));
    }

    CacheStorageTypes.Source cacheSource(int row) {
        var identity = cacheIdentity(cacheText(row, 1), cacheText(row, 2));
        var object = cacheBoolean(row, 0)
            ? java.util.Optional.of(new CacheStorageTypes.Converted(cacheLocation(row, 1, 3), cacheText(row, 4),
                cacheText(row, 5), identity, true))
            : java.util.Optional.<CacheStorageTypes.Converted>empty();
        return new CacheStorageTypes.Source(cacheText(row, 0), identity, object, cacheBytes(row, 0),
            cacheBytes(row, 1));
    }

    CacheStorageTypes.Maintenance cacheMaintenance(int row) {
        try {
            return new CacheStorageTypes.Maintenance(
                cacheBoolean(row, 0) ? java.util.OptionalLong.of(cacheNumber(row, 1)) : java.util.OptionalLong.empty(),
                cacheNumber(row, 2), cacheBoolean(row, 3));
        } catch (IllegalArgumentException invalid) {
            throw invalidValue(invalid);
        }
    }

    CacheStorageTypes.BakeObject cacheBakeObject(int row) {
        try {
            long kind = cacheNumber(row, 1);
            if (kind < 0 || kind >= CacheStorageTypes.BakeKind.values().length)
                throw new IllegalArgumentException("Invalid bake kind");
            var partition = new CacheStorageTypes.Partition(CacheStorageTypes.BakeKind.values()[(int)kind],
                cacheText(row, 1), com.elfmcys.ysm.model.domain.Hash256.parse(cacheText(row, 2)), cacheText(row, 3),
                cacheText(row, 4));
            var key = new CacheStorageTypes.BakeKey(partition, cacheText(row, 5), cacheText(row, 6), cacheText(row, 7),
                cacheText(row, 8), cacheText(row, 9), cacheText(row, 10));
            return new CacheStorageTypes.BakeObject(cacheFile(row), key);
        } catch (IllegalArgumentException invalid) {
            throw invalidValue(invalid);
        }
    }

    private static native String nCacheText(long handle, int row, int field);

    private static native long[] nCacheNumber(long handle, int row, int field);

    private static native byte[] nCacheBytes(long handle, int row, int field);

    static String key(String value) {
        Objects.requireNonNull(value);
        if (value.indexOf('\0') >= 0 || value.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("Storage key exceeds bounds");
        return value;
    }

    static byte[] bytes(UniBuffer value) {
        Objects.requireNonNull(value);
        if (value.size() > StorageResult.MAXIMUM_BYTES)
            throw new IllegalArgumentException("Storage payload exceeds byte bound");
        var bytes = new byte[value.size()];
        try {
            value.nio().get(bytes);
            return bytes;
        } finally {
            Reference.reachabilityFence(value);
        }
    }

    static RuntimeException invalidValue(RuntimeException cause) {
        return new InvalidValue(cause);
    }

    private static String require(String value) {
        return Objects.requireNonNull(value, "Native storage result string is absent");
    }

    private static SharedArrayBuffer buffer(byte[] value) {
        return SharedArrayBuffer
            .adopt(ArrayBuffer.move(Objects.requireNonNull(value, "Native storage result bytes are absent")));
    }

    private static long nonnegative(long value) {
        if (value < 0)
            throw new IllegalStateException("Native storage result number is absent");
        return value;
    }

    private boolean succeeded() {
        return outcome == StorageResult.Outcome.FOUND || outcome == StorageResult.Outcome.MISSING
            || outcome == StorageResult.Outcome.COMMITTED;
    }

    private <T> StorageResult<T> result(List<T> rows) {
        if (rows.size() != count)
            throw new IllegalStateException("Incomplete native storage result payload");
        return new StorageResult<>(outcome, failure, committed, rows);
    }

    private static final class InvalidValue extends RuntimeException {
        private InvalidValue(RuntimeException cause) {
            super(cause);
        }
    }

    private static native long[] nHeader(long handle);

    private static native long nTakeOwner(long handle);

    private static native String nPreferenceIdentity(long handle, int index);

    private static native byte[] nPreferenceValue(long handle, int index);

    private static native long nPreferenceTime(long handle, int index);

}
