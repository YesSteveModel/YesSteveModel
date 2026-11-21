package com.elfmcys.ysm.natives.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.PublishedModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/** Startup directory capability probe. It never opens or replaces a business database. */
public final class StorageProbe {
    private StorageProbe() {}

    /** Runs on a storage/startup worker before the process fixes its region roots. */
    public static void check(Path directory, NativeCancellation cancelled) throws IOException {
        var probe = Files.createTempDirectory(directory, ".sqlite-probe-");
        Throwable failure = null;
        try (var writer = NativePlayerData.open(probe, cancelled); var reader = NativePlayerData.open(probe, cancelled);
            var consumer = NativeCoordination.open(probe, cancelled);
            var ids = NativeFileIdAllocator.open(probe, cancelled);
            var catalog = NativeCacheCatalog.open(probe, cancelled);
            var remote = NativeCacheRemote.open(probe, cancelled); var bake = NativeBakeIndex.open(probe, cancelled)) {
            var key = NativePlayerData.PreferenceKey.roaming(new UUID(0, 0), PublishedModelId.INTRINSIC_DEFAULT);
            var write = writer.putPreference(key, ArrayBuffer.borrow(new byte[0]), 0, cancelled);
            if (!write.committed())
                throw new StorageResult.OpenException(write);
            var read = reader.readPreference(key, cancelled);
            if (read.outcome() != StorageResult.Outcome.FOUND)
                throw new StorageResult.OpenException(read);
            var allocation = ids.allocate(cancelled);
            if (!allocation.committed())
                throw new StorageResult.OpenException(allocation);
            var maintenance = catalog.readMaintenance(cancelled);
            if (maintenance.outcome() != StorageResult.Outcome.FOUND)
                throw new StorageResult.OpenException(maintenance);
            var location = new CacheStorageTypes.Location(allocation.rows().get(0), new UUID(0, 0));
            var file = new CacheStorageTypes.File(location,
                com.elfmcys.ysm.buffer.SharedArrayBuffer.adopt(ArrayBuffer.move(new byte[0])));
            var remoteWrite = remote.publishExact(NativeArtifactIndex.ExactKind.METADATA, "probe", file, cancelled);
            if (!remoteWrite.committed())
                throw new StorageResult.OpenException(remoteWrite);
            var remoteRead = remote.lookupExact(NativeArtifactIndex.ExactKind.METADATA, "probe", cancelled);
            if (remoteRead.outcome() != StorageResult.Outcome.FOUND
                || !remoteRead.rows().get(0).location().equals(location))
                throw new StorageResult.OpenException(remoteRead);
            var partition = new CacheStorageTypes.Partition(CacheStorageTypes.BakeKind.GEOMETRY, "probe",
                new com.elfmcys.ysm.model.domain.Hash256(new byte[32]), "", "");
            var bakeKey = new CacheStorageTypes.BakeKey(partition, "probe", "probe", "probe", "", "", "");
            var bakeWrite = bake.publish(new CacheStorageTypes.BakeObject(file, bakeKey), cancelled);
            if (!bakeWrite.committed())
                throw new StorageResult.OpenException(bakeWrite);
            var bakeRead = bake.lookupExact(bakeKey, cancelled);
            if (bakeRead.outcome() != StorageResult.Outcome.FOUND
                || !bakeRead.rows().get(0).location().equals(location))
                throw new StorageResult.OpenException(bakeRead);
            var exclusive = consumer.beginCleanup(cancelled);
            if (exclusive.outcome() != StorageResult.Outcome.FOUND)
                throw new StorageResult.OpenException(exclusive);
            var restore = consumer.endCleanup();
            if (!restore.committed())
                throw new StorageResult.OpenException(restore);
        } catch (RuntimeException | Error next) {
            failure = next;
            throw next;
        } finally {
            try (var paths = Files.walk(probe)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            } catch (IOException cleanup) {
                if (failure != null)
                    failure.addSuppressed(cleanup);
                else
                    throw cleanup;
            }
        }
    }
}
