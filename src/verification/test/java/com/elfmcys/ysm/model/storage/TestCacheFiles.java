package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.CacheStorageTypes.Pool;
import com.elfmcys.ysm.natives.storage.NativeArtifactIndex.ExactKind;
import java.io.IOException;
import java.nio.file.Path;

/** Creates index-only fixtures so tests can inject missing or damaged files. */
public final class TestCacheFiles {
    private TestCacheFiles() {}

    public static Path path(Path root, ExactKind kind, String key, boolean remote) throws IOException {
        try (var cache = new AtomicSharedCache(root)) {
            var index = remote ? cache.remoteIndex() : cache.catalogIndex();
            var pool = kind == ExactKind.PREVIEW ? Pool.PREVIEW : Pool.REMOTE;
            var found = index.find(kind, key);
            if (found.isPresent())
                return cache.path(pool, found.orElseThrow().location().fileId());
            try (var staging = cache.reserve(pool)) {
                index.publish(kind, key, staging.location());
                return cache.path(pool, staging.location().fileId());
            }
        }
    }

    public static Path write(AtomicSharedCache cache, AtomicSharedCache.CacheValidator validator,
        AtomicSharedCache.CacheWriter writer, AtomicSharedCache.CommitGate gate) throws IOException {
        try (var staging = cache.reserve(Pool.BAKE)) {
            writer.write(staging.file());
            return staging.publish(validator, gate);
        }
    }

    public static long bakeCount(Path root, com.elfmcys.ysm.natives.storage.CacheStorageTypes.BakeKind kind)
        throws IOException {
        try (var token = com.elfmcys.ysm.natives.NativeCancellation.create()) {
            var opened = com.elfmcys.ysm.natives.storage.NativeBakeIndex.openReadOnly(root.resolve("f"), token);
            if (opened.isEmpty())
                return 0;
            try (var database = opened.orElseThrow()) {
                long count = 0;
                var after = java.util.OptionalLong.empty();
                while (true) {
                    var rows = StorageIndex.require(database.pageObjects(after, token)).rows();
                    if (rows.isEmpty())
                        return count;
                    count += rows.stream().filter(row -> row.key().partition().kind() == kind).count();
                    after = java.util.OptionalLong.of(rows.get(rows.size() - 1).file().location().fileId());
                }
            }
        }
    }

}
