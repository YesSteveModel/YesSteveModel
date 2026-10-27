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

    public static ExpressionArtifactStore track(ExpressionArtifactStore store) {
        WORK.get().add(store::awaitPersistence);
        return store;
    }

    public static AtomicSharedCache track(AtomicSharedCache cache) {
        WORK.get().add(cache.derivedWork()::awaitIdle);
        return cache;
    }

    public static void drain() throws InterruptedException {
        for (var work : WORK.get())
            work.await();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        WORK.remove();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        try {
            drain();
        } finally {
            WORK.remove();
        }
    }

    @FunctionalInterface
    private interface Await {
        void await() throws InterruptedException;
    }
}
