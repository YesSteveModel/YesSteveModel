package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.command.sub.CacheCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheCommandTest {
    @TempDir
    Path temp;

    @Test
    void feedbackRunsOnOriginalHostOnlyAfterCommitAndReportsDisabledGlobal() throws Exception {
        var paths = AssetPaths.localOnly(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"));
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(1_800_000_000L))) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            storage.local().cache().catalog().submit((db, token) -> {
                entered.countDown();
                CacheMaintenanceTest.await(release);
                return db.readMaintenance(token);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var host = new LinkedBlockingQueue<Runnable>();
            var success = new ArrayList<Component>();
            var failures = new ArrayList<Component>();
            try {
                assertEquals(1, CacheCommand.schedule(storage, true, host::add, success::add, failures::add));
                assertNull(host.poll(50, TimeUnit.MILLISECONDS));
                assertTrue(success.isEmpty());
            } finally {
                release.countDown();
            }
            var feedback = host.poll(5, TimeUnit.SECONDS);
            assertNotNull(feedback);
            assertTrue(success.isEmpty());
            assertEquals(1, CacheMaintenanceTest.state(storage.local().cache()).resetRevision());
            feedback.run();
            assertTrue(failures.isEmpty());
            assertEquals(List.of("commands.ysm.cache.scheduled", "commands.ysm.cache.global_disabled"),
                success.stream().map(message -> ((TranslatableContents)message.getContents()).getKey()).toList());
            assertTrue(CacheMaintenanceTest.state(storage.local().cache()).lastSuccessUtc().isEmpty());
        }
    }
}
