package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.NativeObject;
import java.lang.ref.Reference;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** Keeps operation, result copying, and physical close in one resource window. */
abstract class NativeBusinessObject extends NativeObject {
    private boolean closed;

    protected NativeBusinessObject(State state) {
        super(state);
    }

    final synchronized <T> StorageResult<T> execute(NativeCancellation cancelled, LongUnaryOperator operation,
        Function<NativeStorageResult, List<T>> payload) {
        Objects.requireNonNull(cancelled);
        if (closed)
            return StorageResult.refused(StorageResult.Failure.CLOSED);
        try {
            return cancelled.borrow(token -> NativeStorageResult.read(() -> operation.applyAsLong(token), payload));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    final synchronized StorageResult<Void> complete(NativeCancellation cancelled, LongFunction<long[]> operation) {
        Objects.requireNonNull(cancelled);
        if (closed)
            return StorageResult.refused(StorageResult.Failure.CLOSED);
        try {
            return cancelled.borrow(token -> NativeStorageResult.terminal(() -> operation.apply(token)));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    final synchronized StorageResult<Long> queryUsage(NativeCancellation cancelled, LongFunction<long[]> operation) {
        Objects.requireNonNull(cancelled);
        if (closed)
            return StorageResult.refused(StorageResult.Failure.CLOSED);
        try {
            return cancelled.borrow(token -> NativeStorageResult.usage(() -> operation.apply(token)));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    final synchronized StorageResult<CacheStorageTypes.References> queryReferences(List<Hash256> containers,
        NativeCancellation cancelled, BiFunction<String[], Long, long[]> operation) {
        Objects.requireNonNull(cancelled);
        if (containers.isEmpty() || containers.size() > StorageResult.MAXIMUM_ROWS)
            throw new IllegalArgumentException("Invalid reference batch");
        if (closed)
            return StorageResult.refused(StorageResult.Failure.CLOSED);
        var fields = containers.stream().map(Hash256::toString).toArray(String[]::new);
        try {
            return cancelled
                .borrow(token -> NativeStorageResult.references(() -> operation.apply(fields, token), fields.length));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    final synchronized StorageResult<Void> recover(Supplier<long[]> operation) {
        if (closed)
            return StorageResult.refused(StorageResult.Failure.CLOSED);
        try {
            return NativeStorageResult.terminal(operation);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public final synchronized void close() {
        closed = true;
        super.close();
    }
}
