package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Test-only composition of the package-local production catalog seam. */
public final class MockClientCatalog implements AutoCloseable {
    private final Path sourceRoot;
    private final ReloadableModelCatalog catalog;
    private final ClientCatalogManager manager;

    public static MockClientCatalog open(Path root, Path intrinsicDefault, List<Path> initialSources) throws Exception {
        return new MockClientCatalog(root, intrinsicDefault, initialSources);
    }

    private MockClientCatalog(Path root, Path intrinsicDefaultFile, List<Path> initialSources) throws Exception {
        sourceRoot = Files.createDirectories(root.resolve("sources"));
        for (var index = 0; index < initialSources.size(); index++) {
            Files.copy(initialSources.get(index), sourceRoot.resolve("initial-" + index + ".mxc"),
                StandardCopyOption.REPLACE_EXISTING);
        }
        var cacheRoot = root.resolve("cache");
        var resolver = new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
            new ConvertedSourceIndexStore(cacheRoot, "classpath-system"), new ConvertedObjectStore(cacheRoot));
        var defaultIdentity = identity(intrinsicDefaultFile);
        var defaultLocation = new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default"));
        var intrinsicDefault =
            ManagedContainer.openIndexed(new CatalogIndexEntry(defaultIdentity, defaultLocation, intrinsicDefaultFile));
        var reconciler =
            new CatalogReconciler(List.of(new ModelCatalogSource(CatalogRootKind.CUSTOM, sourceRoot, false)), resolver,
                Set.of(defaultIdentity.modelId()), intrinsicDefault, CatalogIndexSnapshot.empty());
        catalog = new ReloadableModelCatalog(reconciler, reconciler.materializeBuiltins());
        manager = new ClientCatalogManager(catalog);
        catalog.activate();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (catalog.busy() && System.nanoTime() < deadline) {
            catalog.tick();
            Thread.sleep(1);
        }
        if (catalog.busy()) {
            throw new IllegalStateException("Catalog activation did not finish");
        }
    }

    public ClientCatalogManager manager() {
        return manager;
    }

    public void addSource(Path source, String name) throws Exception {
        Files.copy(source, sourceRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    public CompletableFuture<ReloadResult> reload() throws Exception {
        var result = catalog.reload();
        finish(result);
        return result;
    }

    public void endRemote() {
        manager.endRemote();
    }

    private void finish(CompletableFuture<ReloadResult> result) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!result.isDone() && System.nanoTime() < deadline) {
            catalog.tick();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        result.get(1, TimeUnit.SECONDS);
    }

    private static ModelFileIdentity identity(Path file) throws Exception {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }

    @Override
    public void close() {
        try {
            manager.close();
        } finally {
            catalog.close();
        }
    }
}
