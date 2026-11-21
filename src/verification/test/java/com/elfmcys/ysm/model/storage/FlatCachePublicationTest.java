package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FlatCachePublicationTest {
    @TempDir
    Path temp;

    @Test
    void rebuiltAllocatorSkipsBothOccupiedNamesAndPreservesUnknownBytes() throws Exception {
        var root = temp.resolve("cache");
        var old = new byte[] {1, 2, 3};
        try (var cache = new AtomicSharedCache(root); var staged = cache.reserve(Pool.CONVERTED)) {
            assertEquals(1, staged.location().fileId());
            staged.file().writeBytes(old);
            staged.publish(file -> Arrays.equals(old, file.readAllBytes()), AtomicSharedCache.AtomicReplacement::run);
        }
        Files.delete(root.resolve("f/ids.sqlite"));
        var occupiedStaging = root.resolve("f/c/s/0000000000000002");
        Files.writeString(occupiedStaging, "unknown-staging");
        try (var cache = new AtomicSharedCache(root); var staged = cache.reserve(Pool.CONVERTED)) {
            assertEquals(3, staged.location().fileId());
            assertEquals("0000000000000003", staged.path().getFileName().toString());
            assertEquals(root.resolve("f/c/s"), staged.path().getParent());
            staged.file().writeBytes(new byte[] {4, 5});
            var output = staged.publish(file -> Arrays.equals(new byte[] {4, 5}, file.readAllBytes()),
                AtomicSharedCache.AtomicReplacement::run);
            assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(output));
        }
        assertArrayEquals(old, Files.readAllBytes(root.resolve("f/c/0000000000000001")));
        assertEquals("unknown-staging", Files.readString(occupiedStaging));
    }

    @Test
    void indexFailureLeavesCompleteNewFileAndOldSourceQualification() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var scope = ConvertedScope.of(temp.resolve("data"), "schema");
            var sources = new ConvertedSourceIndexStore(scope, cache);
            var old = sources.publish(StorageContainerFixture.produce(scope, cache, "custom/model"));
            var oldBytes = Files.readAllBytes(old.file());
            var fresh = StorageContainerFixture.produce(scope, cache, "custom/model");
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + cache.layoutRoot().resolve("catalog.sqlite"));
                var statement = db.createStatement()) {
                statement.execute(
                    "CREATE TRIGGER fail_source BEFORE INSERT ON sources BEGIN SELECT RAISE(ABORT,'injected'); END");
                assertThrows(IllegalStateException.class, () -> sources.publish(fresh));
            }
            assertEquals(old, sources.find("custom/model").orElseThrow());
            assertArrayEquals(oldBytes, Files.readAllBytes(old.file()));
            assertArrayEquals(StorageContainerFixture.bytes(), Files.readAllBytes(fresh.file()));
            try (var entries = Files.list(cache.path(Pool.CONVERTED, 0).getParent())) {
                assertTrue(entries.filter(Files::isRegularFile)
                    .allMatch(path -> path.getFileName().toString().matches("[0-9a-f]{16}")));
            }
        }
    }

    @Test
    void staleDeletionCannotDeleteReusedNumberOrUnknownFiles() throws Exception {
        try (var cache = new AtomicSharedCache(temp.resolve("cache"))) {
            var scope = ConvertedScope.of(temp.resolve("data"), "schema");
            var sources = new ConvertedSourceIndexStore(scope, cache);
            var identity = identity();
            var old = new Converted(new Location(8, UUID.randomUUID()), sources.key(), "schema", identity, true);
            var current = new Converted(new Location(8, UUID.randomUUID()), sources.key(), "schema", identity, true);
            var file = cache.path(Pool.CONVERTED, 8);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "old");
            var empty = com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[0]).shareArray();
            StorageIndex.require(cache.catalog().execute((db, token) -> db.publishSource(sources.key(),
                new Source("custom/model", identity, Optional.of(old), empty, empty), token)));
            var objects = new ConvertedObjectStore(cache);
            assertTrue(cache.coordinator().cleanup(() -> objects.delete(old), false));
            Files.writeString(file, "new-instance");
            StorageIndex.require(cache.catalog().execute((db, token) -> db.publishSource(sources.key(),
                new Source("custom/model", identity, Optional.of(current), empty, empty), token)));
            assertFalse(cache.coordinator().cleanup(() -> objects.delete(old), true));
            sources.revoke(old.location());
            assertEquals("new-instance", Files.readString(file));
            var unknown = new Converted(new Location(9, UUID.randomUUID()), sources.key(), "schema", identity, true);
            var orphan = Files.writeString(cache.path(Pool.CONVERTED, 9), "unowned-bytes");
            assertFalse(cache.coordinator().cleanup(() -> objects.delete(unknown), true));
            assertEquals("unowned-bytes", Files.readString(orphan));
            assertThrows(IllegalStateException.class, () -> objects.delete(current));
        }
    }

    private static com.elfmcys.ysm.model.domain.ModelFileIdentity identity() throws Exception {
        var file = Files.createTempFile("ysm-identity-", ".mxc");
        try {
            Files.write(file, StorageContainerFixture.bytes());
            try (var verified = ManagedContainer.verifyFile(file)) {
                return verified.identity();
            }
        } finally {
            Files.delete(file);
        }
    }
}
