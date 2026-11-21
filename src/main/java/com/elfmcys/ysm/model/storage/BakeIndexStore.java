package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.SharedArrayBuffer;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.elfmcys.ysm.natives.storage.NativeBakeIndex;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Region-owned bake qualifications. Reads never create the bake database; candidates are retained before delivery. */
public final class BakeIndexStore {
    private final AtomicSharedCache cache;
    private final CacheDatabase<NativeBakeIndex> reads;
    private final CacheDatabase<NativeBakeIndex> writes;

    BakeIndexStore(AtomicSharedCache cache) {
        this.cache = cache;
        reads = new CacheDatabase<>(cache,
            (root, token) -> NativeBakeIndex.openReadOnly(root, token)
                .orElseThrow(() -> new StorageResult.OpenException(
                    new StorageResult<>(StorageResult.Outcome.MISSING, StorageResult.Failure.NONE, false, List.of()))));
        writes = new CacheDatabase<>(cache, NativeBakeIndex::open);
    }

    public boolean hasPartition(Partition partition) throws IOException {
        var result = StorageIndex.require(reads.read((db, token) -> db.hasPartition(partition, token)));
        return result.outcome() != StorageResult.Outcome.MISSING && result.rows().get(0);
    }

    boolean ownsFile(long fileId) throws IOException {
        var result = StorageIndex.require(reads.read((db, token) -> db.ownsFile(fileId, token)));
        return result.outcome() != StorageResult.Outcome.MISSING && result.rows().get(0);
    }

    public Optional<File> find(BakeKey key) throws IOException {
        return cache.coordinator().acquire(() -> {
            var result = StorageIndex.require(reads.read((db, token) -> db.lookupExact(key, token)));
            if (result.outcome() == StorageResult.Outcome.MISSING)
                return Optional.empty();
            var file = result.rows().get(0);
            cache.coordinator().retain(Pool.BAKE, file.location());
            return Optional.of(file);
        });
    }

    public void publish(BakeKey key, Location location) throws IOException {
        cache.coordinator().acquire(() -> {
            var file =
                new File(location, SharedArrayBuffer.copyOf(com.elfmcys.ysm.buffer.ArrayBuffer.borrow(new byte[0])));
            StorageIndex.require(writes.execute((db, token) -> db.publish(new BakeObject(file, key), token)));
            return null;
        });
    }
}
