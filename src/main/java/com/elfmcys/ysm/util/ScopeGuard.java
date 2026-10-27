package com.elfmcys.ysm.util;

import java.util.Objects;

public final class ScopeGuard<T extends AutoCloseable> implements AutoCloseable {
    private final T obj;
    private boolean released;

    private ScopeGuard(T obj) {
        this.obj = Objects.requireNonNull(obj);
    }

    public T release() {
        released = true;
        return obj;
    }

    public T get() {
        return obj;
    }

    public static <T extends AutoCloseable> ScopeGuard<T> create(T obj) {
        return new ScopeGuard<>(obj);
    }

    @Override
    public void close() {
        if (!released) {
            try {
                obj.close();
            } catch (Exception e) { // 为什么析构要抛异常？
                throw new RuntimeException(e);
            }
        }
    }
}
