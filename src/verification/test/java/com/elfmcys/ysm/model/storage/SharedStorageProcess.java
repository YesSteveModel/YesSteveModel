package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Separate JVM endpoint; readiness files make the competing operations independently observable. */
public final class SharedStorageProcess {
    public static void main(String[] args) throws Exception {
        var mode = args[0];
        var cacheRoot = Path.of(args[1]);
        var scope = ConvertedScope.of(Path.of(args[2]), cacheRoot, args[3]);
        var cache = new AtomicSharedCache(cacheRoot);
        var ready = Path.of(args[4]);
        if (mode.equals("consumer")) {
            try (var consumers = new ConvertedCacheCoordinator(scope, cache)) {
                consumers.register();
                Files.writeString(ready, "registered");
                System.in.read();
                if (args.length > 5)
                    Files.writeString(ready.resolveSibling(ready.getFileName() + ".read"),
                        Files.readString(Path.of(args[5])));
            }
        } else if (mode.equals("prune")) {
            try (var consumers = new ConvertedCacheCoordinator(scope, cache)) {
                consumers.register();
                Files.writeString(ready, consumers.pruneOnce(Set.of()).name());
            }
        } else if (mode.equals("index")) {
            var indexes = new ConvertedSourceIndexStore(scope, cache);
            Files.writeString(ready, "ready");
            await(Path.of(args[5]));
            var bytes = new byte[32];
            bytes[0] = (byte)Integer.parseInt(args[7]);
            var identity = new ModelFileIdentity(new Hash256(bytes), new Hash256(bytes));
            indexes.replaceScopes(Set.of(args[6]),
                List.of(new ConvertedSourceIndex(identity, args[6] + "/same.mxc", args[3])));
        } else if ((mode.equals("write") || mode.equals("derived"))) {
            var target = Path.of(args[5]);
            var payload = Files.readAllBytes(Path.of(args[6]));
            AtomicSharedCache.CacheWriter writer = temporary -> {
                temporary.writeBytes(Arrays.copyOf(payload, Math.max(1, payload.length / 2)));
                Files.writeString(ready, "staged");
                if (args[7].equals("hold"))
                    System.in.read();
                temporary.position(0).writeBytes(payload);
            };
            if (mode.equals("derived")) {
                var job = cache.derivedWork().submit(gate -> cache.replaceValidated("process-test",
                    "same-key", target, file -> Arrays.equals(file.readAllBytes(), payload), writer, gate));
                cache.derivedWork().awaitIdle();
                if (job.outcome() != DerivedStorageWork.Outcome.SUCCEEDED)
                    throw new IllegalStateException("Derived write failed", job.failure());
                cache.derivedWork().close();
            } else
                cache.materializeReplacing("process-test", "same-key", target,
                    file -> Arrays.equals(file.readAllBytes(), payload), writer);
        }
    }

    private static void await(Path path) throws Exception {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(path)) {
            if (System.nanoTime() > deadline)
                throw new IllegalStateException("Process barrier timed out");
            Thread.sleep(2);
        }
    }
}
