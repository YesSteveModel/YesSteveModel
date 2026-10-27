package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetPaths;
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

    private ModelStorageInfra(AssetPaths paths, String version) {
        this.paths = Objects.requireNonNull(paths);
        local = new CacheRegion(paths.gameCacheRoot(), null, derivedWork);
        localScope = new Scope(ConvertedScope.of(paths.gameModelsRoot(), local.root(), version), local);
        if (paths.global().isPresent()) {
            var global = paths.global().orElseThrow();
            remote = global.cacheRoot().equals(local.root()) ? local
                : new CacheRegion(global.cacheRoot(), global.cacheGuard(), derivedWork);
            globalScope = new Scope(ConvertedScope.of(global.dataRoot(), remote.root(), version), remote);
        } else {
            remote = local;
            globalScope = null;
        }
    }

    public static ModelStorageInfra open(AssetPaths paths, String version) {
        return new ModelStorageInfra(paths, version);
    }

    public AssetPaths paths() {
        return paths;
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
            if (configured.equals(global.customRoot()) || configured.equals(global.authRoot()))
                return globalScope;
        }
        if (configured.equals(paths.customModelsRoot()) || configured.equals(paths.authModelsRoot()))
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

    public void registerConverted() throws IOException {
        for (var scope : scopes())
            scope.consumers().register();
    }

    public Map<Scope, Set<String>> scopes(Collection<CatalogRootIdentity> roots) {
        var result = new LinkedHashMap<Scope, Set<String>>();
        for (var root : roots)
            result.computeIfAbsent(scope(root), ignored -> new java.util.LinkedHashSet<>())
                .add(root.rootKind().namespace());
        return result;
    }

    public ConvertedCacheCoordinator.PruneResult prune(Set<Path> retained, Collection<CatalogRootIdentity> complete) {
        var observed = scopes(complete);
        var result = ConvertedCacheCoordinator.PruneResult.COMPLETED;
        for (var scope : scopes()) {
            var required = scope == localScope ? Set.of("custom", "auth", "builtin") : Set.of("custom", "auth");
            if (!observed.getOrDefault(scope, Set.of()).containsAll(required)) {
                if (result == ConvertedCacheCoordinator.PruneResult.COMPLETED)
                    result = ConvertedCacheCoordinator.PruneResult.SKIPPED_INCOMPLETE_SCOPE;
                continue;
            }
            // Catalog retention spans regions; each coordinator owns only its converted scope.
            var objectsRoot = scope.identity().root().resolve("objects");
            var owned = retained.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(path -> path.startsWith(objectsRoot)).collect(Collectors.toUnmodifiableSet());
            var next = scope.consumers().pruneOnce(owned);
            if (next != ConvertedCacheCoordinator.PruneResult.COMPLETED
                && result != ConvertedCacheCoordinator.PruneResult.FAILED)
                result = next;
        }
        return result;
    }

    @Override
    public void close() {
        derivedWork.close();
        regions().forEach(region -> region.expressions().clearMemory());
        Throwable failure = null;
        for (var scope : scopes()) {
            try {
                scope.consumers().close();
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
        ConvertedSourceIndexStore indexes, ConvertedCacheCoordinator consumers) {
        private Scope(ConvertedScope identity, CacheRegion region) {
            this(identity, region, new ConvertedObjectStore(identity, region.cache()),
                new ConvertedSourceIndexStore(identity, region.cache()),
                new ConvertedCacheCoordinator(identity, region.cache()));
        }
    }
}
