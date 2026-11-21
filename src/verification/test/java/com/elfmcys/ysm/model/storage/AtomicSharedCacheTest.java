package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AtomicSharedCacheTest {
    @TempDir
    Path temp;

    @Test
    void relativeRootsAreResolvedBeforeContainmentChecks() throws Exception {
        var root = Path.of("").toAbsolutePath().relativize(temp.resolve("cache"));
        try (var cache = new AtomicSharedCache(root)) {
            var target = root.resolve("f/b/0000000000000001");
            assertEquals(target.toAbsolutePath().normalize(), cache.observe(target, true).target());
            assertThrows(java.io.IOException.class, () -> cache.observe(Path.of("outside.bin"), false));
        }
    }

    @Test
    void replacedCommitDirectoryRejectsPublicationAndPreservesForeignObjects() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache")); var staging = cache.reserve(Pool.BAKE)) {
            var parent = cache.path(Pool.BAKE, staging.location().fileId()).getParent();
            org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.readAttributes(parent, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null);
            staging.file().writeBytes("verified".getBytes());
            var retired = parent.resolveSibling("retired");
            assertThrows(java.io.IOException.class, () -> staging.publish(file -> true, move -> {
                Files.move(parent, retired);
                Files.createDirectory(parent);
                Files.writeString(parent.resolve("foreign"), "unchanged");
                move.run();
            }));
            assertEquals("unchanged", Files.readString(parent.resolve("foreign")));
        }
    }

    @Test
    void observableStagingReplacementCannotPublishUnvalidatedBytes() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache")); var staging = cache.reserve(Pool.BAKE)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(Files
                .readAttributes(staging.path(), java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null);
            staging.file().writeBytes("verified".getBytes());
            var retired = staging.path().resolveSibling("retired-staging");
            assertThrows(java.io.IOException.class,
                () -> staging.publish(file -> new String(file.readAllBytes()).equals("verified"), move -> {
                    Files.move(staging.path(), retired);
                    Files.writeString(staging.path(), "unvalidated");
                    move.run();
                }));
            assertFalse(Files.exists(cache.path(Pool.BAKE, staging.location().fileId())));
            assertEquals("verified", Files.readString(retired));
        }
    }

    @Test
    void directoryRetargetBeforeCommitCannotWriteIntoSourceRoot() throws Exception {
        var source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("original"), "original");
        try (var cache = new AtomicSharedCache(temp.resolve("cache")); var staging = cache.reserve(Pool.BAKE)) {
            var parent = cache.path(Pool.BAKE, staging.location().fileId()).getParent();
            staging.file().writeBytes("verified".getBytes());
            assertThrows(java.io.IOException.class, () -> staging.publish(file -> true, move -> {
                Files.move(parent, parent.resolveSibling("retired"));
                Files.createSymbolicLink(parent, source);
                move.run();
            }));
            assertEquals("original", Files.readString(source.resolve("original")));
            try (var names = Files.list(source)) {
                assertEquals(java.util.List.of("original"), names.map(path -> path.getFileName().toString()).toList());
            }
        }
    }

    @Test
    void independentProducersReserveDifferentIntegerNames() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache")); var first = cache.reserve(Pool.BAKE);
            var second = cache.reserve(Pool.BAKE)) {
            assertNotEquals(first.location().fileId(), second.location().fileId());
            assertTrue(Files.exists(first.path()));
            assertTrue(Files.exists(second.path()));
            assertFalse(Files.exists(cache.root().resolve("locks")));
        }
    }
}
