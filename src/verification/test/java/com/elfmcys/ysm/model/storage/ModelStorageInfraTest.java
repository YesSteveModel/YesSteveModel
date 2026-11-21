package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.compatibility.AbiVersion;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelStorageInfraTest {
    @TempDir
    Path temp;

    @Test
    void rootsAreAdmittedBeforePublicationAndScopesUseModelSchema() throws Exception {
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", temp.resolve("home").toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        try (var storage = ModelStorageInfra.open(paths)) {
            assertEquals(2, storage.regions().size());
            for (var region : storage.regions())
                assertTrue(region.consumers().registered());
            var local = storage.scope(new CatalogRootIdentity(CatalogRootKind.MODELS, paths.modelsRoot()));
            var builtin =
                storage.scope(new CatalogRootIdentity(CatalogRootKind.BUILTIN, paths.builtinModelsResource()));
            assertSame(local, builtin);
            for (var scope : storage.scopes()) {
                assertEquals(AbiVersion.MODEL_SCHEMA, scope.identity().version());
                assertSame(scope.region().consumers(), scope.consumers());
            }
        }
    }

    @Test
    void closingOneSystemDoesNotReleaseAnotherSystemsRoot() throws Exception {
        var paths = AssetPaths.localOnly(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"));
        try (var first = ModelStorageInfra.open(paths); var second = ModelStorageInfra.open(paths)) {
            first.close();
            assertTrue(second.local().consumers().registered());
            assertTrue(second.local().consumers().cleanup(() -> true, false));
        }
    }
}
