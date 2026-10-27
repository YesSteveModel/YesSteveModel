package com.elfmcys.ysm.model.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicSharedCacheTest {
    @TempDir
    Path temp;

    @Test
    void relativeRootsAreResolvedBeforeContainmentChecks() throws Exception {
        var cacheRoot = Path.of("").toAbsolutePath().relativize(temp.resolve("cache"));
        var target = cacheRoot.resolve("objects/value.bin");

        var checked = new AtomicSharedCache(cacheRoot).observe(target, true).target();

        assertFalse(cacheRoot.isAbsolute());
        assertEquals(target.toAbsolutePath().normalize(), checked);
        assertThrows(java.io.IOException.class,
            () -> new AtomicSharedCache(cacheRoot).observe(Path.of("outside.bin"), false));
    }

    @Test
    void replacedCommitDirectoryRejectsTheWriterAndPreservesBothOldTargets() throws Exception {
        var root = temp.resolve("cache");
        var target = root.resolve("objects/value.bin");
        Files.createDirectories(target.getParent());
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.readAttributes(target.getParent(),
            java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
            "Same-type replacement is observable only when the provider supplies a file key");
        Files.writeString(target, "old-valid");
        var retired = root.resolve("retired");
        var cache = new AtomicSharedCache(root);
        assertThrows(java.io.IOException.class,
            () -> cache.replaceValidated("retarget", "key", target, path -> true, temporary -> {
                Files.move(target.getParent(), retired);
                Files.createDirectory(target.getParent());
                Files.writeString(target, "replacement-directory-object");
                temporary.writeBytes("new-valid".getBytes());
            }, AtomicSharedCache.AtomicReplacement::run));
        assertEquals("old-valid", Files.readString(retired.resolve("value.bin")));
        assertEquals("replacement-directory-object", Files.readString(target));
    }

    @Test
    void observableStagingReplacementCannotPublishUnvalidatedBytes() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.readAttributes(root,
            java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
            "Same-type replacement is observable only when the provider supplies a file key");
        var target = Files.writeString(root.resolve("value.bin"), "old-valid");
        assertThrows(java.io.IOException.class,
            () -> new AtomicSharedCache(root).replaceValidated("test", "same", target,
                file -> new String(file.readAllBytes()).equals("verified"),
                file -> file.writeBytes("verified".getBytes()), replacement -> {
                    final Path staged;
                    try (var files = Files.list(root)) {
                        staged = files.filter(p -> p.getFileName().toString().startsWith("value.bin.tmp-"))
                            .findFirst().orElseThrow();
                    }
                    Files.move(staged, root.resolve("retired-staging"));
                    Files.writeString(staged, "unvalidated");
                    replacement.run();
                }));
        assertEquals("old-valid", Files.readString(target));
        assertEquals("verified", Files.readString(root.resolve("retired-staging")));
    }

    @Test
    void directoryRetargetBeforeStagingMustNotWriteIntoSourceRoot() throws Exception {
        var root = temp.resolve("cache");
        var target = root.resolve("objects/value.bin");
        Files.createDirectories(target.getParent());
        var source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("original.bin"), "original");
        var cache = new AtomicSharedCache(root);
        var retargeted = new java.util.concurrent.atomic.AtomicBoolean();

        assertThrows(java.io.IOException.class,
            () -> cache.replaceValidated("retarget", "key", target, path -> true, temporary -> {
                // This fixed interleaving represents an external retarget before the first write.
                Files.move(target.getParent(), root.resolve("retired"));
                Files.createSymbolicLink(target.getParent(), source);
                retargeted.set(true);
                temporary.writeBytes("new-cache-data".getBytes());
            }, AtomicSharedCache.AtomicReplacement::run));

        assertTrue(retargeted.get(), "The filesystem retarget must run before the first staging write");
        assertEquals("original", Files.readString(source.resolve("original.bin")));
        try (var files = Files.list(source)) {
            assertEquals(java.util.List.of("original.bin"),
                files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void jvmKeyWaitCanBeCancelledWithoutLeakingTheNextLock() throws Exception {
        var root = temp.resolve("cache");
        var first = new AtomicSharedCache(root);
        var peer = new AtomicSharedCache(root);
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        first.withKeyLock("test", "same", () -> {
            assertEquals("nested", peer.withKeyLock("test", "same", () -> "nested"));
            var contender = new Thread(() -> {
                waiting.countDown();
                try { peer.withKeyLock("test", "same", () -> { invoked.set(true); return null; }); }
                catch (Throwable error) { failure.set(error); }
            });
            contender.start();
            try {
                assertTrue(waiting.await(10, java.util.concurrent.TimeUnit.SECONDS));
                contender.interrupt();
                contender.join(10000);
                assertFalse(contender.isAlive());
                assertTrue(failure.get() instanceof java.nio.channels.FileLockInterruptionException,
                    String.valueOf(failure.get()));
            } catch (InterruptedException interrupted) {
                throw new java.io.IOException(interrupted);
            } finally { contender.interrupt(); }
            return null;
        });
        assertFalse(invoked.get());
        assertEquals("next", peer.withKeyLock("test", "same", () -> "next"));
    }

    @Test
    void absentFileKeyDoesNotDisableHealthyValidatedCommits() throws Exception {
        var root = Files.createDirectory(temp.resolve("cache"));
        var attrs = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(attrs.fileKey() == null,
            "This provider supplies file keys; the nullable observation is tested separately");
        var target = root.resolve("value.bin");
        new AtomicSharedCache(root).replaceValidated("no-key", "same", target,
            file -> new String(file.readAllBytes()).equals("verified"),
            file -> file.writeBytes("verified".getBytes()), AtomicSharedCache.AtomicReplacement::run);
        assertEquals("verified", Files.readString(target));
        assertThrows(java.io.IOException.class, () -> new AtomicSharedCache(root).replaceValidated("no-key",
            "same", target, file -> false, file -> file.writeBytes("invalid".getBytes()),
            AtomicSharedCache.AtomicReplacement::run));
        assertEquals("verified", Files.readString(target));
    }

    @Test
    void keyLocksLiveUnderTheCacheRootsOwnLockDirectory() throws Exception {
        var cacheRoot = temp.resolve("cache");
        var observed = new AtomicReference<Path>();

        new AtomicSharedCache(cacheRoot).withKeyLock("namespace", "key", () -> {
            try (var files = Files.walk(cacheRoot.resolve("locks"))) {
                observed.set(files.filter(Files::isRegularFile).findFirst().orElseThrow());
            }
            return null;
        });

        assertEquals(cacheRoot.resolve("locks").resolve("namespace"), observed.get().getParent());
        assertTrue(observed.get().getFileName().toString().endsWith(".lck"));
    }
}
