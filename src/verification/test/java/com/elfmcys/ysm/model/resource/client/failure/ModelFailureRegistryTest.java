package com.elfmcys.ysm.model.resource.client.failure;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelFailureRegistryTest {
    @TempDir
    static Path fixtureRoot;

    private static ManagedContainer content;

    @BeforeAll
    static void createFixture() throws Exception {
        var manifest = ModelFailureRegistryTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        final Path fixture;
        try (var vfs = new Directory(source)) {
            fixture = ModelParser.parse(vfs, Files.createDirectories(fixtureRoot.resolve("model")),
                DefaultAnimationFilter.keepAll());
        }
        content = ManagedContainer.openDirect(fixture,
            new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("failure-registry-fixture")));
    }

    @Test
    void concurrentFailuresKeepOneExactCauseAndNotifyOnce() {
        var registry = new ModelFailureRegistry();
        var notifications = new AtomicInteger();
        var notifiedCause = new AtomicReference<Throwable>();
        Consumer<Throwable> notifyFirst = cause -> {
            notifiedCause.set(cause);
            notifications.incrementAndGet();
        };
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(8);
        var tasks = new CompletableFuture<?>[32];
        try {
            for (var index = 0; index < tasks.length; index++) {
                var cause = new IllegalStateException("failure-" + index);
                tasks[index] = CompletableFuture.runAsync(() -> {
                    await(start);
                    registry.gate(content, ModelFailureRegistry.Stage.TEXTURE, "player/base/uv", notifyFirst)
                        .fail(cause);
                }, workers);
            }
            start.countDown();
            CompletableFuture.allOf(tasks).join();
        } finally {
            workers.shutdownNow();
        }

        assertEquals(1, notifications.get());
        assertSame(notifiedCause.get(),
            registry.gate(content, ModelFailureRegistry.Stage.TEXTURE, "player/base/uv", ignored -> {
            }).failure().orElseThrow());
    }

    @Test
    void exactGateBecomesEligibleAgainOnlyAfterClear() {
        var registry = new ModelFailureRegistry();
        var notifications = new AtomicInteger();
        var gate = registry.gate(content, ModelFailureRegistry.Stage.ANIMATION, "player/main/idle",
            ignored -> notifications.incrementAndGet());
        var first = new IllegalStateException("first");
        var ignored = new IllegalStateException("ignored");

        assertTrue(gate.failure().isEmpty());
        gate.fail(first);
        gate.fail(ignored);

        assertSame(first, gate.failure().orElseThrow());
        assertEquals(1, notifications.get());
        assertTrue(registry.hasMatching(content, ModelFailureRegistry.Stage.ANIMATION,
            resource -> resource.equals("player/main/idle")));
        assertTrue(registry.gate(content, ModelFailureRegistry.Stage.ANIMATION, "player/main/walk", ignoredCause -> {
        }).failure().isEmpty());

        registry.clear(content);

        assertTrue(gate.failure().isEmpty());
        assertFalse(registry.hasMatching(content, ModelFailureRegistry.Stage.ANIMATION, resource -> true));
        var second = new IllegalStateException("second");
        gate.fail(second);
        assertSame(second, gate.failure().orElseThrow());
        assertEquals(2, notifications.get());

        registry.clear();
        assertTrue(gate.failure().isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
