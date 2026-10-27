package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetDirectoryPlatform;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ModelStorageInfraTest {
    private static final byte[] CONTENT = {3, 1, 4, 1, 5};

    @TempDir
    Path temp;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void combinedRetentionUsesEachObjectOwner(boolean retainLocal, boolean retainGlobal) throws Exception {
        try (var layout = layout()) {
            var retained = new HashSet<Path>();
            if (retainLocal)
                retained.add(layout.local());
            if (retainGlobal)
                retained.add(layout.global());

            assertEquals(ConvertedCacheCoordinator.PruneResult.COMPLETED,
                layout.storage().prune(retained, layout.roots()));
            assertEquals(retainLocal, Files.exists(layout.local()));
            assertEquals(retainGlobal, Files.exists(layout.global()));
            for (var path : retained)
                assertArrayEquals(layout.bytes(), Files.readAllBytes(path));
            assertFalse(Files.exists(orphan(layout.local())));
            assertFalse(Files.exists(orphan(layout.global())));
            assertArrayEquals(layout.bytes(), Files.readAllBytes(layout.source()));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void anotherScopeCannotGrantPrunePermission(boolean guardGlobal, boolean activePeer) throws Exception {
        try (var layout = layout()) {
            var guardedRoot = layout.roots().get(guardGlobal ? 3 : 0);
            var guardedScope = layout.storage().scope(guardedRoot);
            try (var peer = activePeer
                ? new ConvertedCacheCoordinator(guardedScope.identity(), guardedScope.region().cache()) : null) {
                if (peer != null)
                    peer.register();
                var complete = activePeer ? layout.roots()
                    : layout.roots().stream().filter(root -> !root.equals(guardedRoot)).toList();
                assertEquals(
                    activePeer ? ConvertedCacheCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER
                        : ConvertedCacheCoordinator.PruneResult.SKIPPED_INCOMPLETE_SCOPE,
                    layout.storage().prune(java.util.Set.of(layout.local(), layout.global()), complete));

                assertTrue(Files.exists(orphan(guardGlobal ? layout.global() : layout.local())));
                assertFalse(Files.exists(orphan(guardGlobal ? layout.local() : layout.global())));
                for (var path : List.of(layout.local(), layout.global(), layout.source()))
                    assertArrayEquals(layout.bytes(), Files.readAllBytes(path));
            }
        }
    }

    @Test
    void scopeFailureIsNotHiddenByAnotherScopeCompleting() throws Exception {
        try (var layout = layout()) {
            var objects = layout.storage().scope(layout.roots().get(0)).identity().root().resolve("objects");
            var retired = objects.resolveSibling("retired-objects");
            Files.move(objects, retired);
            Files.writeString(objects, "invalid object directory");

            assertEquals(ConvertedCacheCoordinator.PruneResult.FAILED,
                layout.storage().prune(java.util.Set.of(layout.local(), layout.global()), layout.roots()));
            assertFalse(Files.exists(orphan(layout.global())));
            assertArrayEquals(CONTENT,
                Files.readAllBytes(retired.resolve(objects.relativize(layout.local()))));
            assertArrayEquals(CONTENT, Files.readAllBytes(layout.global()));
            assertArrayEquals(CONTENT, Files.readAllBytes(layout.source()));
        }
    }

    private Layout layout() throws Exception {
        var paths = AssetPaths.discover(temp.resolve("game"), temp.resolve("config"), temp.resolve("builtin"),
            Map.of("YSM_HOME", temp.resolve("global-home").toString()), AssetDirectoryPlatform.Kind.WINDOWS, null);
        assertTrue(paths.globalEnabled(), paths.diagnostics().toString());
        var global = paths.global().orElseThrow();
        var roots = List.of(new CatalogRootIdentity(CatalogRootKind.CUSTOM, paths.customModelsRoot()),
            new CatalogRootIdentity(CatalogRootKind.AUTH, paths.authModelsRoot()),
            new CatalogRootIdentity(CatalogRootKind.BUILTIN, paths.builtinModelsResource()),
            new CatalogRootIdentity(CatalogRootKind.CUSTOM, global.customRoot()),
            new CatalogRootIdentity(CatalogRootKind.AUTH, global.authRoot()));
        var source = paths.customModelsRoot().resolve("source.mxc");
        Files.createDirectories(source.getParent());
        Files.write(source, CONTENT);
        var storage = ModelStorageInfra.open(paths, "regional-prune");
        try {
            // Prune routes and preserves files; container validation belongs to the object store tests.
            var id = new Hash256(new byte[Hash256.SIZE]);
            var local = storage.scope(roots.get(0)).objects().objectPath(id, id);
            var shared = storage.scope(roots.get(3)).objects().objectPath(id, id);
            for (var path : List.of(local, shared)) {
                Files.createDirectories(path.getParent());
                Files.copy(source, path);
                Files.copy(source, orphan(path));
            }
            storage.registerConverted();
            return new Layout(storage, roots, source, local, shared, Files.readAllBytes(source));
        } catch (Exception | Error failure) {
            storage.close();
            throw failure;
        }
    }

    private static Path orphan(Path object) {
        return object.resolveSibling("orphan.mxc");
    }

    private record Layout(ModelStorageInfra storage, List<CatalogRootIdentity> roots, Path source, Path local,
        Path global, byte[] bytes) implements AutoCloseable {
        @Override
        public void close() {
            storage.close();
        }
    }
}
