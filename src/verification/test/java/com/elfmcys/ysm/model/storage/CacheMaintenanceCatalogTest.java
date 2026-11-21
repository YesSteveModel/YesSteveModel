package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheMaintenanceCatalogTest {
    @TempDir
    Path temp;

    @Test
    void realStartupScanQualifiesLocalDespiteGlobalObservationFailureAndReloadDoesNotRetry() throws Exception {
        var builtin = builtinRoot();
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), builtin,
            Map.of("YSM_HOME", temp.resolve("home").toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        var globalModels = paths.global().orElseThrow().modelsRoot();
        Files.createDirectories(globalModels.getParent());
        Files.deleteIfExists(globalModels);
        Files.writeString(globalModels, "invalid source root");
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(1_800_000_000L));
            var builtins =
                BuiltinModelCatalog.open(contract(), builtin, ContentStorageBinding.builtin(storage.local()));
            var catalog = new ReloadableModelCatalog(storage, ModelCatalogSources.sources(paths), builtins)) {
            catalog.activate();
            finish(catalog);
            assertEquals(1_800_000_000L,
                CacheMaintenanceTest.state(storage.local().cache()).lastSuccessUtc().orElseThrow());
            assertTrue(CacheMaintenanceTest.state(storage.remote().cache()).lastSuccessUtc().isEmpty());
            Files.delete(globalModels);
            Files.createDirectory(globalModels);
            var reload = catalog.reload();
            finish(catalog);
            assertEquals(com.elfmcys.ysm.model.catalog.ReloadStatus.COMMITTED,
                reload.get(5, TimeUnit.SECONDS).status());
            assertTrue(CacheMaintenanceTest.state(storage.remote().cache()).lastSuccessUtc().isEmpty());
        }
    }

    @Test
    void commandBeforeActivationDefersBothTheStartupScanAndLaterReload() throws Exception {
        var builtin = builtinRoot();
        var paths = AssetPaths.localOnly(temp.resolve("game"), temp.resolve("config"), builtin);
        try (var storage = ModelStorageInfra.open(paths, CacheMaintenanceTest.clock(1_800_000_000L));
            var builtins =
                BuiltinModelCatalog.open(contract(), builtin, ContentStorageBinding.builtin(storage.local()));
            var catalog = new ReloadableModelCatalog(storage, ModelCatalogSources.sources(paths), builtins)) {
            assertTrue(storage.pruneNext(false).toCompletableFuture().get(5, TimeUnit.SECONDS).get(0).succeeded());
            catalog.activate();
            finish(catalog);
            assertTrue(CacheMaintenanceTest.state(storage.local().cache()).lastSuccessUtc().isEmpty());
            catalog.reload();
            finish(catalog);
            assertTrue(CacheMaintenanceTest.state(storage.local().cache()).lastSuccessUtc().isEmpty());
        }
    }

    private static Path builtinRoot() throws Exception {
        return Path.of(Objects
            .requireNonNull(CacheMaintenanceCatalogTest.class.getResource("/assets/ysm/builtin/default/ysm.json"))
            .toURI()).getParent().getParent();
    }

    private static BuiltinModelIndex contract() throws Exception {
        return BuiltinModelIndex.read(Path.of(Objects
            .requireNonNull(CacheMaintenanceCatalogTest.class.getResource("/assets/ysm/builtin-index.json")).toURI()));
    }

    private static void finish(ReloadableModelCatalog catalog) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (catalog.busy() && System.nanoTime() < deadline) {
            catalog.tick();
            Thread.sleep(5);
        }
        assertFalse(catalog.busy(), catalog.current().report().toString());
    }
}
