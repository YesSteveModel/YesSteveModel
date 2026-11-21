package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelId;
import com.elfmcys.ysm.natives.NativeCancellation;
import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.natives.storage.NativeBakeIndex;
import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.google.gson.GsonBuilder;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.elfmcys.ysm.natives.storage.CacheStorageTypes.formatFileId;

/** Reports a finite two-pool cleanup window, including physical deletion and the date commit. */
public final class CacheMaintenanceObservation {
    public static void main(String[] args) throws Exception {
        NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
        var count = Integer.parseInt(args[1]);
        var root = Files.createTempDirectory("ysm-maintenance-cost-");
        var empty = ArrayBuffer.move(new byte[0]).shareArray();
        try (var cache = new AtomicSharedCache(root)) {
            var owner = new CacheMaintenance(cache, new CachePruner(cache), true,
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC), (phase, state) -> {
                });
            owner.initialize();
            Files.createDirectories(root.resolve("f/c"));
            Files.createDirectories(root.resolve("f/b"));
            var seeded = System.nanoTime();
            try (var token = NativeCancellation.create();
                var catalog = NativeCacheCatalog.open(root.resolve("f"), token);
                var bake = NativeBakeIndex.open(root.resolve("f"), token)) {
                for (var i = 1; i <= count; i++) {
                    var hash = new Hash256(ByteBuffer.allocate(32).putInt(i).array());
                    var identity = new ModelFileIdentity(ModelId.fromHash(hash), hash);
                    var converted =
                        new Converted(new Location(i * 2L, UUID.randomUUID()), "cost", "old-schema", identity, true);
                    Files.write(root.resolve("f/c/" + formatFileId(i * 2L)), new byte[] {1});
                    if (!catalog
                        .publishSource("cost",
                            new Source("models/" + i, identity, Optional.of(converted), empty, empty), token)
                        .committed())
                        throw new IllegalStateException("Source fixture was not committed");
                    var location = new Location(i * 2L + 1, UUID.randomUUID());
                    Files.write(root.resolve("f/b/" + formatFileId(location.fileId())), new byte[] {1});
                    var key = new BakeKey(new Partition(BakeKind.GEOMETRY, "old-schema", hash, "", ""), "input", "", "",
                        "", "", "");
                    if (!bake.publish(new BakeObject(new File(location, empty), key), token).committed())
                        throw new IllegalStateException("Bake fixture was not committed");
                }
            }
            var start = System.nanoTime();
            var result = owner.attempt(Set.of("cost"));
            var finished = System.nanoTime();
            if (result.status() != CacheMaintenance.Status.RECORDED
                || result.reclaimed().orElseThrow().convertedReclaimed() != count
                || result.reclaimed().orElseThrow().bakeReclaimed() != count)
                throw new IllegalStateException(result.toString());
            var report = Map.of("objectsPerPool", count, "sourceRows", count, "seedMilliseconds",
                (start - seeded) / 1_000_000.0, "windowMilliseconds", (finished - start) / 1_000_000.0, "result",
                result.toString(), "root", root.toString());
            Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } finally {
            var expected = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (!root.toAbsolutePath().normalize().startsWith(expected))
                throw new IllegalStateException("Observation cleanup escaped its temporary directory");
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
