package com.elfmcys.ysm.util;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class SpinLock {
    private final AtomicBoolean flag = new AtomicBoolean();

    public Closeable enter() {
        while (!flag.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
        return this::unlock;
    }

    @Nullable
    public Closeable tryEnter() {
        if (flag.compareAndSet(false, true)) {
            return this::unlock;
        }
        return null;
    }

    public void enter(Runnable task) {
        try (var lock = enter()) {
            task.run();
        }
    }

    public <T> T enter(Supplier<T> task) {
        try (var lock = enter()) {
            return task.get();
        }
    }

    private void unlock() {
        flag.set(false);
    }
}
