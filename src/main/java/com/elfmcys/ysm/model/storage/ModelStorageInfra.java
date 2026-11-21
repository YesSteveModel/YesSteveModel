package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Process ownership of fixed cache regions and bounded conversion scopes. */
public final class ModelStorageInfra implements AutoCloseable {
    private final AssetPaths paths;
    private final CacheRegion local;
    private final CacheRegion remote;
    private final Scope localScope;
    private final Scope globalScope;
    private final DerivedStorageWork derivedWork = new DerivedStorageWork();
    private final DatabaseWork databaseWork = new DatabaseWork();
    private final PlayerPreferences preferences;
    private final StorageWriteBuffer writes;
    private final Map<CacheRegion, CacheMaintenance> maintenance = new LinkedHashMap<>();
    private volatile boolean closed;

    private ModelStorageInfra(AssetPaths paths, Clock clock) {
        this.paths = Objects.requireNonNull(paths);
        local = new CacheRegion(paths.gameCacheRoot(), null, derivedWork, databaseWork);
        admit(local);
        localScope = new Scope(
            ConvertedScope.of(paths.gameModelsRoot(), com.elfmcys.ysm.compatibility.AbiVersion.MODEL_SCHEMA), local);
        if (paths.global().isPresent()) {
            var global = paths.global().orElseThrow();
            remote = global.cacheGuard().root().equals(local.cacheRootIdentity()) ? local
                : new CacheRegion(global.cacheRoot(), global.cacheGuard(), derivedWork, databaseWork);
            try {
                admit(remote);
            } catch (RuntimeException | Error failure) {
                local.close();
                throw failure;
            }
            globalScope = new Scope(
                ConvertedScope.of(global.dataRoot(), com.elfmcys.ysm.compatibility.AbiVersion.MODEL_SCHEMA), remote);
        } else {
            remote = local;
            globalScope = null;
        }
        try {
            for (var region : regions()) {
                var owner = new CacheMaintenance(region, region == local, clock);
                maintenance.put(region, owner);
                try {
                    owner.initialize();
                } catch (java.io.InterruptedIOException | java.nio.channels.ClosedChannelException failure) {
                    throw new java.io.UncheckedIOException(failure);
                } catch (IOException failure) {
                    com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Cache startup maintenance unavailable: {}",
                        region.root(), failure);
                }
            }
        } catch (RuntimeException | Error failure) {
            regions().forEach(region -> region.consumers().seal());
            for (Runnable rollback : List.<Runnable>of(derivedWork::close, databaseWork::close,
                () -> regions().forEach(CacheRegion::close))) {
                try {
                    rollback.run();
                } catch (RuntimeException | Error next) {
                    if (next != failure)
                        failure.addSuppressed(next);
                }
            }
            throw failure;
        }
        var data = paths.global().map(AssetPaths.GlobalDirectories::dataRoot).orElse(paths.gameModelsRoot());
        preferences = new PlayerPreferences(data, databaseWork);
        writes = preferences.writes();
        remote.usageObserver((container, utcSeconds) -> {
            var admission = writes.offerUsage(remote.cache().remoteIndex(), container, utcSeconds);
            if (admission != StorageWriteBuffer.Admission.QUEUED)
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Remote usage was not buffered: {} {}", container, admission);
        });
    }

    private static void admit(CacheRegion region) {
        try {
            region.consumers().register();
        } catch (IOException failure) {
            region.close();
            throw new java.io.UncheckedIOException(failure);
        }
    }

    public static ModelStorageInfra open(AssetPaths paths) {
        return open(paths, Clock.systemUTC());
    }

    static ModelStorageInfra open(AssetPaths paths, Clock clock) {
        return new ModelStorageInfra(paths, clock);
    }

    public AssetPaths paths() {
        return paths;
    }

    public DatabaseWork databaseWork() {
        return databaseWork;
    }

    public PlayerPreferences preferences() {
        return preferences;
    }

    public StorageWriteBuffer writes() {
        return writes;
    }

    public void tick(long elapsedNanos) {
        if (!closed)
            writes.tick(elapsedNanos).ifPresent(batch -> batch.whenComplete(ModelStorageInfra::reportWrites));
    }

    private static void reportWrites(java.util.List<StorageWriteBuffer.WriteResult> results, Throwable failure) {
        if (failure != null)
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Storage buffer failed", failure);
        else
            for (var result : results)
                if (!result.succeeded())
                    com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Storage write failed: {} {}", result.key(),
                        result.deliveryFailure() != null ? result.deliveryFailure() : result.storage());
    }

    public CacheRegion local() {
        return local;
    }

    public CacheRegion remote() {
        return remote;
    }

    public List<CacheRegion> regions() {
        return remote == local ? List.of(local) : List.of(local, remote);
    }

    public List<Scope> scopes() {
        return globalScope == null ? List.of(localScope) : List.of(localScope, globalScope);
    }

    public Scope scope(CatalogRootIdentity root) {
        if (root.rootKind() == CatalogRootKind.BUILTIN)
            return localScope;
        var configured = root.configuredAbsoluteRoot();
        if (globalScope != null) {
            var global = paths.global().orElseThrow();
            if (configured.equals(global.modelsRoot()))
                return globalScope;
        }
        if (configured.equals(paths.modelsRoot()))
            return localScope;
        throw new IllegalArgumentException("Source root is outside this process layout: " + configured);
    }

    public Scope scope(ContentStorageBinding binding) {
        return binding.kind() == ContentStorageBinding.Kind.BUILTIN ? localScope
            : scope(binding.localSource().orElseThrow().key().root());
    }

    public ContentStorageBinding binding(SourceObservation source) {
        return source.key().root().rootKind() == CatalogRootKind.BUILTIN ? ContentStorageBinding.builtin(local)
            : ContentStorageBinding.local(scope(source.key().root()).region(), source);
    }

    public void registerCaches() throws IOException {
        for (var region : regions())
            region.consumers().register();
    }

    /**
     * Consumes the first startup scan's per-root qualification. Ordinary reloads must not call this method. Complete
     * roots have passed final observation and index publication; incomplete roots never authorize deletion. Runs on the
     * catalog coordinator, without holding a host lock or two cache-root windows.
     */
    public Map<CacheRegion, CacheMaintenance.Result> finishStartupScan(Collection<CatalogRootIdentity> completeRoots)
        throws IOException {
        var complete = Set.copyOf(completeRoots);
        var expected = ModelCatalogSources.sources(paths).stream().map(ModelCatalogSource::identity).toList();
        var results = new LinkedHashMap<CacheRegion, CacheMaintenance.Result>();
        for (var region : regions()) {
            var required = expected.stream().filter(root -> scope(root).region() == region).toList();
            var scopes = complete.containsAll(required)
                ? required.stream().map(root -> scope(root).indexes().key()).collect(Collectors.toUnmodifiableSet())
                : Set.<String>of();
            var result = maintenance.get(region).attempt(scopes);
            results.put(region, result);
            if (result.status() == CacheMaintenance.Status.FAILED
                || result.status() == CacheMaintenance.Status.PRUNE_FAILED)
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Cache maintenance failed: {} {}", region.root(), result);
        }
        return Map.copyOf(results);
    }

    /**
     * Atomically resets each enabled physical root on DatabaseWork. Completion reports each committed result; admission
     * alone never means success. Global all requests survive ordinary resets. No file gate, reload or cleanup is
     * entered. Shutdown drains or explicitly fails accepted operations before releasing root protection.
     */
    public CompletionStage<List<PruneReset>> pruneNext(boolean all) {
        var pending = new java.util.ArrayList<CompletableFuture<PruneReset>>();
        for (var region : regions()) {
            var global = globalScope != null && region == remote;
            final CompletionStage<StorageResult<Void>> operation;
            try {
                operation = region.cache().catalog().submit((db, token) -> db.resetMaintenance(all && global, token));
            } catch (IOException failure) {
                pending.add(CompletableFuture
                    .completedFuture(new PruneReset(region.root(), region == local, global, null, failure)));
                continue;
            }
            pending.add(operation.handle((result, failure) -> {
                if (failure instanceof Error error)
                    throw error;
                return new PruneReset(region.root(), region == local, global, result, failure);
            }).toCompletableFuture());
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> pending.stream().map(CompletableFuture::join).toList());
    }

    public record PruneReset(Path root, boolean local, boolean global, StorageResult<Void> storage, Throwable failure) {
        public boolean succeeded() {
            return failure == null && storage != null && storage.succeeded() && storage.committed();
        }
    }

    public Map<Scope, Set<String>> scopes(Collection<CatalogRootIdentity> roots) {
        var result = new LinkedHashMap<Scope, Set<String>>();
        for (var root : roots)
            result.computeIfAbsent(scope(root), ignored -> new java.util.LinkedHashSet<>())
                .add(root.rootKind().namespace());
        return result;
    }

    @Override
    public void close() {
        closed = true;
        regions().forEach(region -> region.consumers().seal());
        Throwable failure = null;
        for (Runnable close : List.<Runnable>of(derivedWork::close, () -> reportWrites(writes.closeAndDrain(), null),
            databaseWork::close, () -> regions().forEach(CacheRegion::clearMemory))) {
            try {
                close.run();
            } catch (RuntimeException | Error next) {
                if (failure == null)
                    failure = next;
                else if (failure != next)
                    failure.addSuppressed(next);
            }
        }
        for (var region : regions()) {
            try {
                region.close();
            } catch (RuntimeException | Error next) {
                if (failure == null)
                    failure = next;
                else if (failure != next)
                    failure.addSuppressed(next);
            }
        }
        if (failure instanceof RuntimeException runtime)
            throw runtime;
        if (failure instanceof Error error)
            throw error;
    }

    public record Scope(ConvertedScope identity, CacheRegion region, ConvertedObjectStore objects,
        ConvertedSourceIndexStore indexes, CacheRootCoordinator consumers) {
        private Scope(ConvertedScope identity, CacheRegion region) {
            this(identity, region, new ConvertedObjectStore(region.cache()),
                new ConvertedSourceIndexStore(identity, region.cache()), region.consumers());
        }
    }
}
