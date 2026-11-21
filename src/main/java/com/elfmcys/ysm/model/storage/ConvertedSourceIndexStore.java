package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.natives.storage.NativeCacheCatalog;
import com.elfmcys.ysm.natives.storage.CacheStorageTypes.*;
import com.elfmcys.ysm.natives.storage.StorageResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Scope source facts and converted ownership. The root gate orders mutations with retention and deletion. */
public final class ConvertedSourceIndexStore {
    private final ConvertedScope scope;
    private final AtomicSharedCache cache;
    private final CacheDatabase<NativeCacheCatalog> index;
    private final String key;

    public ConvertedSourceIndexStore(ConvertedScope scope, AtomicSharedCache cache) {
        this.scope = Objects.requireNonNull(scope);
        this.cache = Objects.requireNonNull(cache);
        index = cache.catalog();
        key = java.util.Base64.getEncoder().encodeToString(
            scope.dataIdentity().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ":" + scope.version()
            + ":directory-inline-1:" + com.elfmcys.ysm.format.schema.model.ModelFileConstant.CURRENT_VERSION;
    }

    public ConvertedScope scope() {
        return scope;
    }

    public Path root() {
        return cache.root();
    }

    public String cacheVersion() {
        return scope.version();
    }

    public String key() {
        return key;
    }

    public Optional<ConvertedSourceIndex> find(String source) throws IOException {
        var canonical = ConvertedSourceIndex.normalizeRawRelativePath(source);
        return cache.coordinator().acquire(() -> {
            var result = StorageIndex.require(index.read((db, token) -> db.lookupSource(key, canonical, token)));
            for (var value : result.rows()) {
                if (value.converted().isEmpty())
                    continue;
                var entry = entry(value);
                cache.coordinator().retain(Pool.CONVERTED, entry.location());
                return Optional.of(entry);
            }
            return Optional.empty();
        });
    }

    private ConvertedSourceIndex entry(Source value) {
        var object = value.converted().orElseThrow();
        return new ConvertedSourceIndex(value.identity(), value.source(), object.cacheVersion(), object.location(),
            cache.path(Pool.CONVERTED, object.location().fileId()), value.observation(), value.metadata());
    }

    public Map<String, ConvertedSourceIndex> read() throws IOException {
        return cache.coordinator().acquire(() -> {
            var entries = new LinkedHashMap<String, ConvertedSourceIndex>();
            var after = "";
            while (true) {
                var cursor = after;
                var result = StorageIndex.require(index.read((db, token) -> db.pageSources(key, cursor, token)));
                if (result.rows().isEmpty())
                    return Map.copyOf(entries);
                for (var value : result.rows()) {
                    if (value.converted().isPresent())
                        entries.put(value.source(), entry(value));
                    after = value.source();
                }
            }
        });
    }

    /** Publishes only this completed production. An instance collision is an explicit invariant failure. */
    public ConvertedSourceIndex publish(ConvertedSourceIndex value) throws IOException {
        if (!value.cacheVersion().equals(scope.version())
            || !value.file().equals(cache.path(Pool.CONVERTED, value.location().fileId())))
            throw new IllegalArgumentException("Converted publication does not belong to this schema and pool");
        return cache.coordinator().acquire(() -> {
            cache.coordinator().retain(Pool.CONVERTED, value.location());
            var object = new Converted(value.location(), key, value.cacheVersion(), value.identity(), true);
            var source = new Source(value.rawRelativePath(), value.identity(), Optional.of(object), value.observation(),
                value.metadata());
            var result = StorageIndex.require(index.execute((db, token) -> db.publishSource(key, source, token)));
            if (!result.committed())
                throw new IOException("Converted publication was rejected: " + result);
            return value;
        });
    }

    public java.util.concurrent.CompletionStage<StorageResult<Void>> revokeAsync(Location location) {
        try {
            return cache.coordinator().acquire(() -> index.submit((db, token) -> db.revokeConverted(location, token)));
        } catch (IOException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    public void revoke(Location location) throws IOException {
        cache.coordinator().acquire(() -> {
            var result = index.execute((db, token) -> db.revokeConverted(location, token));
            if (result.outcome() != StorageResult.Outcome.REJECTED || result.failure() != StorageResult.Failure.NONE)
                StorageIndex.require(result);
            return null;
        });
    }

    public void replace(Collection<ConvertedSourceIndex> retained) throws IOException {
        replaceScopes(Set.of("custom", "auth", "builtin"), retained);
    }

    public void replaceScopes(Set<String> namespaces, Collection<ConvertedSourceIndex> retained) throws IOException {
        var references = new LinkedHashMap<String, com.elfmcys.ysm.model.domain.ModelFileIdentity>();
        retained.forEach(value -> references.put(value.rawRelativePath(), value.identity()));
        replaceScopes(namespaces, retained, references);
    }

    /** Reconciles only fully observed namespaces; other scopes and incomplete roots retain their source facts. */
    public void replaceScopes(Set<String> namespaces, Collection<ConvertedSourceIndex> retained,
        Map<String, com.elfmcys.ysm.model.domain.ModelFileIdentity> references) throws IOException {
        cache.coordinator().acquire(() -> {
            var converted = retained.stream().map(ConvertedSourceIndex::rawRelativePath)
                .collect(java.util.stream.Collectors.toSet());
            var batch = new ArrayList<SourceReference>(StorageResult.MAXIMUM_ROWS);
            for (var reference : references.entrySet()) {
                if (converted.contains(reference.getKey()))
                    continue;
                batch.add(new SourceReference(reference.getKey(), reference.getValue()));
                if (batch.size() == StorageResult.MAXIMUM_ROWS) {
                    StorageIndex.require(index.execute((db, token) -> db.publishSourceReferences(key, batch, token)));
                    batch.clear();
                }
            }
            if (!batch.isEmpty())
                StorageIndex.require(index.execute((db, token) -> db.publishSourceReferences(key, batch, token)));
            var obsolete = new java.util.ArrayList<String>();
            var after = "";
            while (true) {
                var cursor = after;
                var rows = StorageIndex.require(index.read((db, token) -> db.pageSources(key, cursor, token))).rows();
                if (rows.isEmpty())
                    break;
                for (var value : rows) {
                    var source = value.source();
                    if (namespaces.contains(source.substring(0, source.indexOf('/')))
                        && !references.containsKey(source))
                        obsolete.add(source);
                    after = source;
                }
            }
            for (var start = 0; start < obsolete.size(); start += 63) {
                var page = obsolete.subList(start, Math.min(start + 63, obsolete.size()));
                StorageIndex.require(index.execute((db, token) -> db.finishScan(key, page, token)));
            }
            return null;
        });
    }
}
