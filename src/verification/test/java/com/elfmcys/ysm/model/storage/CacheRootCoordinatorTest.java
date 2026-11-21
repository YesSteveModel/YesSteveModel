package com.elfmcys.ysm.model.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheRootCoordinatorTest {
    @TempDir
    Path temp;

    @Test
    void sameJvmHandlesShareOneRegistrationAndCloseIndependently() throws Exception {
        var cache = temp.resolve("cache");
        try (var first = new CacheRootCoordinator(cache); var second = new CacheRootCoordinator(cache.resolve("."))) {
            first.register();
            second.register();
            assertEquals("exclusive", first.cleanup(() -> "exclusive", "peer"));
            first.close();
            assertTrue(second.registered());
            assertEquals("exclusive", second.cleanup(() -> "exclusive", "peer"));
        }
        assertTrue(Files.isRegularFile(cache.resolve("f/admission.sqlite")));
        assertFalse(Files.exists(cache.resolve("converted")));
    }

    @Test
    void closeWaitsForBorrowAndSealsDelayedAcquisition() throws Exception {
        var owner = new CacheRootCoordinator(temp.resolve("cache"));
        var use = owner.borrow();
        var closing = CompletableFuture.runAsync(owner::close);
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertThrows(java.nio.channels.ClosedChannelException.class, owner::borrow);
        } finally {
            use.close();
        }
        closing.get(10, TimeUnit.SECONDS);
    }

    @Test
    void allowedRootAliasesShareTheSameProtectionAndLifetimeRetention() throws Exception {
        var root = Files.createDirectory(temp.resolve("real"));
        for (var junction : System.getProperty("os.name").startsWith("Windows") ? java.util.List.of(false, true)
            : java.util.List.of(false)) {
            var alias = temp.resolve("alias-" + junction);
            if (junction) {
                var link = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), root.toString())
                    .redirectErrorStream(true).start();
                var output = new String(link.getInputStream().readAllBytes());
                assertEquals(0, link.waitFor(), output);
            } else
                Files.createSymbolicLink(alias, root);
            var location =
                new com.elfmcys.ysm.natives.storage.CacheStorageTypes.Location(7, java.util.UUID.randomUUID());
            var pool = com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.CONVERTED;
            try (var first = new CacheRootCoordinator(root); var second = new CacheRootCoordinator(alias)) {
                first.register();
                second.register();
                first.retain(pool, location);
                assertTrue(second.cleanup(() -> second.retained(pool, location), false));
            }
            try (var reopened = new CacheRootCoordinator(root)) {
                assertTrue(reopened.acquire(() -> reopened.retained(pool, location)));
            }
            Files.delete(alias);
        }
    }

    @Test
    void failedCleanupRestoresSharedBeforeAllowingAnotherWindow() throws Exception {
        try (var owner = new CacheRootCoordinator(temp.resolve("cache"))) {
            owner.register();
            assertThrows(java.io.IOException.class, () -> owner.cleanup(() -> {
                throw new java.io.IOException("injected");
            }, false));
            assertTrue(owner.registered());
            assertEquals(true, owner.cleanup(() -> true, false));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void admissionContentionDoesNotRevokeExistingConsumer() throws Exception {
        var cache = temp.resolve("cache");
        try (var owner = new CacheRootCoordinator(cache)) {
            owner.register();
            var cleanupEntered = new java.util.concurrent.atomic.AtomicBoolean();
            try (var contender = new com.elfmcys.ysm.testutil.SqliteProcess(cache.resolve("f/admission.sqlite"))) {
                // Another startup can retain admission while this consumer still holds SHARED.
                contender.execute("BEGIN IMMEDIATE");
                try {
                    owner.cleanup(() -> {
                        cleanupEntered.set(true);
                        return true;
                    }, false);
                } catch (java.io.IOException busy) {
                    // The failed maintenance attempt does not revoke the existing consumer.
                } finally {
                    contender.execute("ROLLBACK");
                }
            }
            assertFalse(cleanupEntered.get());
            assertTrue(owner.registered(), "Ordinary admission contention must retain consumer protection");
            assertEquals("active", owner.acquire(() -> "active"));
        }
    }

    @Test
    void cacheCloseWaitsForItsOpenReadChannel() throws Exception {
        var cache = new AtomicSharedCache(temp.resolve("cache"));
        final Path path;
        try (var staging = cache.reserve(com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool.CONVERTED)) {
            staging.file().writeBytes(new byte[] {1, 2, 3});
            path = staging.publish(file -> true, AtomicSharedCache.AtomicReplacement::run);
        }
        var channel = cache.openRead(path);
        var closing = CompletableFuture.runAsync(cache::close);
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertEquals(3, channel.size());
            assertThrows(java.nio.channels.ClosedChannelException.class, () -> cache.openRead(path));
        } finally {
            channel.close();
        }
        closing.get(10, TimeUnit.SECONDS);
    }

    @Test
    void distinctRootsCanOverlapWindows() throws Exception {
        try (var a = new CacheRootCoordinator(temp.resolve("a")); var b = new CacheRootCoordinator(temp.resolve("b"))) {
            a.register();
            b.register();
            assertTrue(a.cleanup(() -> CompletableFuture.supplyAsync(() -> {
                try {
                    return b.cleanup(() -> true, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).orTimeout(10, TimeUnit.SECONDS).join(), false));
        }
    }
}
