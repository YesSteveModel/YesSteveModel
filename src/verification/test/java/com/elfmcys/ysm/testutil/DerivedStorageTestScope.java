package com.elfmcys.ysm.testutil;

import com.elfmcys.ysm.expression.aot.ExpressionArtifactStore;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Keeps test temporary directories alive through optional writes; readiness assertions never call drain. */
public final class DerivedStorageTestScope implements BeforeEachCallback, AfterEachCallback {
    private static final ThreadLocal<List<Await>> WORK = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<AutoCloseable>> OWNED = ThreadLocal.withInitial(ArrayList::new);

    public static ExpressionArtifactStore track(ExpressionArtifactStore store) {
        WORK.get().add(store::awaitPersistence);
        return store;
    }

    public static AtomicSharedCache track(AtomicSharedCache cache) {
        WORK.get().add(cache.derivedWork()::awaitIdle);
        OWNED.get().add(() -> {
            try {
                cache.derivedWork().close();
            } finally {
                cache.close();
            }
        });
        return cache;
    }

    public static <T extends AutoCloseable> T track(T owner) {
        OWNED.get().add(owner);
        return owner;
    }

    public static void drain() throws InterruptedException {
        for (var work : WORK.get())
            work.await();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        WORK.remove();
        OWNED.remove();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Throwable failure = null;
        try {
            drain();
        } catch (Exception | Error next) {
            failure = next;
        } finally {
            var owners = OWNED.get();
            for (var index = owners.size() - 1; index >= 0; index--) {
                try {
                    owners.get(index).close();
                } catch (Exception | Error next) {
                    if (failure == null)
                        failure = next;
                    else if (failure != next)
                        failure.addSuppressed(next);
                }
            }
            OWNED.remove();
            WORK.remove();
        }
        if (failure instanceof Exception exception)
            throw exception;
        if (failure instanceof Error error)
            throw error;
    }

    @FunctionalInterface
    private interface Await {
        void await() throws InterruptedException;
    }
}
