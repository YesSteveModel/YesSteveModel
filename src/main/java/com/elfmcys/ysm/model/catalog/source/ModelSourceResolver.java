package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.RawModelDiagnostic;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndex;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.legacy.LegacyModelImportException;
import com.elfmcys.ysm.natives.legacy.LegacyModelImporter;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ModelSourceResolver {
    private final ModelStorageInfra storage;
    private final RawModelImporter importer;
    private final LegacyModelImporter legacyImporter;
    private final ConvertedSourceIndexStore indexes;
    private final ConvertedObjectStore objects;

    public ModelSourceResolver(RawModelImporter importer, ConvertedSourceIndexStore indexes,
        ConvertedObjectStore objects) {
        this(importer, new LegacyModelImporter(), indexes, objects);
    }

    public ModelSourceResolver(RawModelImporter importer, LegacyModelImporter legacyImporter,
        ConvertedSourceIndexStore indexes, ConvertedObjectStore objects) {
        this.storage = null;
        this.importer = Objects.requireNonNull(importer, "importer");
        this.legacyImporter = Objects.requireNonNull(legacyImporter, "legacyImporter");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public ModelSourceResolver(RawModelImporter importer, ModelStorageInfra storage) {
        this.importer = Objects.requireNonNull(importer);
        this.legacyImporter = new LegacyModelImporter();
        this.storage = Objects.requireNonNull(storage);
        this.indexes = null;
        this.objects = null;
    }

    private ContentStorageBinding binding(SourceObservation source) {
        return storage == null ? null : storage.binding(source);
    }

    public Resolution resolve(SourceObservation observation) throws CatalogBuildException {
        return resolve(observation, true);
    }

    private Resolution resolve(SourceObservation observation, boolean allowReuse) throws CatalogBuildException {
        var indexes = storage == null ? this.indexes : storage.scope(observation.key().root()).indexes();
        var objects = storage == null ? this.objects : storage.scope(observation.key().root()).objects();
        var location = observation.key().root().location(observation.key().relativePath());
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            return validateDirect(observation, location);
        }
        if (observation.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE) {
            return resolveLegacy(observation, location, allowReuse);
        }
        if (observation.key().sourceKind() == ModelSourceKind.UNSUPPORTED_YSM) {
            throw new ModelSourceException("Unsupported .ysm container");
        }

        var rawRelativePath =
            observation.key().root().rootKind().namespace() + "/" + observation.key().relativePath().value();
        try {
            var indexed = allowReuse ? candidate(indexes, rawRelativePath) : Optional.<ConvertedSourceIndex>empty();
            if (SourceReuseObservation.standardArchive(observation)) {
                // Keep the directory table and the parse under the same archive owner on a miss.
                try (var archive = com.elfmcys.ysm.natives.NativeArchive.open(observation.logicalPath().toString())) {
                    var reuse = SourceReuseObservation.capture(observation, archive);
                    var hit = reusable(indexes, observation, indexed, reuse);
                    if (hit.isPresent())
                        return hit.get();
                    return convertRaw(observation, indexes, objects, archive, rawRelativePath, reuse);
                }
            }
            var reuse = SourceReuseObservation.capture(observation);
            var hit = reusable(indexes, observation, indexed, reuse);
            if (hit.isPresent())
                return hit.get();
            if (observation.key().sourceKind() == ModelSourceKind.CURRENT_RAW_DIRECTORY) {
                try (var directory = new com.elfmcys.ysm.format.vfs.Directory(observation.absolutePath())) {
                    return convertRaw(observation, indexes, objects, directory, rawRelativePath, reuse);
                }
            }
            // v1/v2 open their validated bundle only after the header hint has failed to select a candidate.
            try (var archive = com.elfmcys.ysm.natives.NativeArchive.open(observation.logicalPath().toString())) {
                return convertRaw(observation, indexes, objects, archive, rawRelativePath, reuse);
            }
        } catch (ModelSourceException | CatalogInfrastructureException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new CatalogInfrastructureException("Failed to access converted model storage", failure);
        } catch (java.util.concurrent.CancellationException | java.io.UncheckedIOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ModelSourceException("Failed to parse raw model", failure);
        }
    }

    private Resolution convertRaw(SourceObservation observation, ConvertedSourceIndexStore indexes,
        ConvertedObjectStore objects, com.elfmcys.ysm.format.vfs.VirtualFileSystem input, String source,
        Optional<com.elfmcys.ysm.buffer.SharedArrayBuffer> reuse) throws IOException {
        try (var temporary = objects.stage()) {
            var compiled = importer.convert(input, temporary);
            var stored = objects.commit(compiled, observation.key().root().location(observation.key().relativePath()),
                temporary);
            var entry = new ConvertedSourceIndex(stored.identity(), source, indexes.cacheVersion(), stored.location(),
                stored.file(), reuse.orElseGet(() -> com.elfmcys.ysm.buffer.ArrayBuffer.move(new byte[0]).shareArray()),
                stored.metadata());
            var published = publish(indexes, entry);
            return new Resolution(catalogEntry(observation, published.entry(), published.owner()),
                Optional.of(published.entry()), compiled.diagnostics(), false);
        }
    }

    private Optional<ConvertedSourceIndex> candidate(ConvertedSourceIndexStore indexes, String source) {
        try {
            return indexes.find(source);
        } catch (IOException failure) {
            if (!com.elfmcys.ysm.model.storage.CacheReadFailure.recoverable(failure))
                throw new java.io.UncheckedIOException(failure);
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Converted reuse failed for {}", source, failure);
            return Optional.empty();
        }
    }

    private Optional<Resolution> reusable(ConvertedSourceIndexStore indexes, SourceObservation observation,
        Optional<ConvertedSourceIndex> indexed, Optional<com.elfmcys.ysm.buffer.SharedArrayBuffer> observed) {
        if (indexed.isEmpty() || observed.isEmpty() || !indexed.get().observation().nio().equals(observed.get().nio()))
            return Optional.empty();
        return Optional.of(new Resolution(catalogEntry(observation, indexed.get(), indexes), indexed, List.of(), true));
    }

    private CatalogIndexEntry catalogEntry(SourceObservation source, ConvertedSourceIndex entry,
        @Nullable ConvertedSourceIndexStore owner) {
        var binding = binding(source);
        if (binding != null)
            binding = binding.withConverted(entry, owner);
        return new CatalogIndexEntry(entry.identity(), source.key().root().location(source.key().relativePath()),
            entry.file(), binding, entry);
    }

    private static Publication publish(ConvertedSourceIndexStore owner, ConvertedSourceIndex entry) throws IOException {
        try {
            return new Publication(owner.publish(entry), owner);
        } catch (IOException failure) {
            com.elfmcys.ysm.model.storage.CacheReadFailure.miss(entry.file(), failure);
            com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Converted file is usable but its index was not committed: {}",
                entry.file(), failure);
            return new Publication(entry, null);
        }
    }

    private record Publication(ConvertedSourceIndex entry, @Nullable ConvertedSourceIndexStore owner) {
    }

    public MaterializedResolution resolveMaterialized(SourceObservation observation)
        throws CatalogBuildException, IOException {
        return resolveMaterialized(observation, null);
    }

    public MaterializedResolution resolveMaterialized(SourceObservation observation,
        @Nullable ManagedContainer reusable) throws CatalogBuildException, IOException {
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            var location = observation.key().root().location(observation.key().relativePath());
            try {
                var opened = openDirect(observation, location, reusable);
                var content = storage == null ? opened : opened.withStorageBinding(binding(observation));
                try {
                    return new MaterializedResolution(new CatalogIndexEntry(content.representation().identity(),
                        location, observation.absolutePath(), binding(observation)),
                        new ResolvedContent(content, reusable), Optional.empty(), List.of());
                } catch (IOException | RuntimeException | Error failure) {
                    content.representation().close();
                    throw failure;
                }
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw new CatalogInfrastructureException("Failed to access direct model container", failure);
                }
                throw new ModelSourceException("Direct model container is invalid", failure);
            } catch (FileSystemException | SecurityException failure) {
                throw new CatalogInfrastructureException("Failed to access direct model container", failure);
            } catch (IOException failure) {
                throw new ModelSourceException("Direct model container is invalid", failure);
            }
        }
        var resolved = resolve(observation);
        ManagedContainer content;
        try {
            content = ManagedContainer.openIndexed(resolved.entry(), reusable);
        } catch (IOException failure) {
            if (!resolved.cacheCandidate())
                throw failure;
            com.elfmcys.ysm.model.storage.CacheReadFailure.miss(resolved.entry().backingFile(), failure);
            resolved = resolve(observation, false);
            content = ManagedContainer.openIndexed(resolved.entry(), reusable);
        }
        try {
            return new MaterializedResolution(resolved.entry(), new ResolvedContent(content, reusable),
                resolved.convertedIndexEntry(), resolved.diagnostics());
        } catch (IOException | RuntimeException | Error failure) {
            content.representation().close();
            throw failure;
        }
    }

    private ManagedContainer openDirect(SourceObservation observation, CatalogLocation location,
        @Nullable ManagedContainer reusable) throws IOException {
        if (storage != null)
            return storage.scope(observation.key().root()).region().directories().open(observation.absolutePath(),
                location, reusable);
        var content = ManagedContainer.openDirect(observation.absolutePath(), location, reusable);
        try {
            DirectContainerAdmission.requireEmbeddedPreview(content);
            return content;
        } catch (IOException | RuntimeException | Error failure) {
            content.representation().close();
            throw failure;
        }
    }

    private Resolution validateDirect(SourceObservation observation, CatalogLocation location)
        throws CatalogBuildException {
        try {
            var content = openDirect(observation, location, null);
            try {
                return new Resolution(new CatalogIndexEntry(content.representation().identity(), location,
                    observation.absolutePath(), binding(observation)), Optional.empty(), List.of(), false);
            } finally {
                content.representation().close();
            }
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                throw new CatalogInfrastructureException("Failed to access direct model container", failure);
            }
            throw new ModelSourceException("Direct model container is invalid", failure);
        } catch (FileSystemException | SecurityException failure) {
            throw new CatalogInfrastructureException("Failed to access direct model container", failure);
        } catch (IOException failure) {
            throw new ModelSourceException("Direct model container is invalid", failure);
        }
    }

    public void replaceIndex(Set<String> rootNamespaces, Collection<ConvertedSourceIndex> entries,
        Collection<CatalogIndexEntry> retained) throws CatalogInfrastructureException {
        try {
            var available = new LinkedHashMap<>(indexes.read());
            entries.forEach(entry -> available.put(entry.rawRelativePath(), entry));
            var selected = new java.util.ArrayList<ConvertedSourceIndex>();
            for (var entry : retained) {
                var key = entry.location().rootKind().namespace() + "/" + entry.location().path().value();
                var converted = available.get(key);
                if (converted != null && converted.identity().equals(entry.identity())
                    && converted.file().equals(entry.backingFile())) {
                    selected.add(converted);
                }
            }
            var references = new LinkedHashMap<String, com.elfmcys.ysm.model.domain.ModelFileIdentity>();
            for (var entry : retained) {
                var source = entry.location().rootKind().namespace() + "/" + entry.location().path().value();
                if (rootNamespaces.contains(entry.location().rootKind().namespace()))
                    references.put(source, entry.identity());
            }
            indexes.replaceScopes(rootNamespaces, selected, references);
        } catch (IOException failure) {
            throw new CatalogInfrastructureException("Failed to replace converted-source index", failure);
        }
    }

    public void replaceIndex(java.util.List<CatalogRootIdentity> completeRoots,
        Collection<ConvertedSourceIndex> entries, Collection<CatalogIndexEntry> retained)
        throws CatalogInfrastructureException {
        if (storage == null) {
            replaceIndex(completeRoots.stream().map(root -> root.rootKind().namespace())
                .collect(java.util.stream.Collectors.toSet()), entries, retained);
            return;
        }
        try {
            for (var group : storage.scopes(completeRoots).entrySet()) {
                var scope = group.getKey();
                var available = new LinkedHashMap<>(scope.indexes().read());
                for (var entry : entries) {
                    if (retained.stream().anyMatch(value -> value.storageBinding() != null
                        && storage.scope(value.storageBinding()) == scope && value.identity().equals(entry.identity())
                        && (value.location().rootKind().namespace() + "/" + value.location().path().value())
                            .equals(entry.rawRelativePath())))
                        available.put(entry.rawRelativePath(), entry);
                }
                var selected = new java.util.ArrayList<ConvertedSourceIndex>();
                for (var entry : retained) {
                    if (entry.storageBinding() == null || storage.scope(entry.storageBinding()) != scope)
                        continue;
                    var key = entry.location().rootKind().namespace() + "/" + entry.location().path().value();
                    var converted = available.get(key);
                    if (converted != null && converted.identity().equals(entry.identity())
                        && converted.file().equals(entry.backingFile()))
                        selected.add(converted);
                }
                var references = new LinkedHashMap<String, com.elfmcys.ysm.model.domain.ModelFileIdentity>();
                for (var entry : retained) {
                    if (entry.storageBinding() != null && storage.scope(entry.storageBinding()) == scope
                        && group.getValue().contains(entry.location().rootKind().namespace()))
                        references.put(entry.location().rootKind().namespace() + "/" + entry.location().path().value(),
                            entry.identity());
                }
                scope.indexes().replaceScopes(group.getValue(), selected, references);
            }
        } catch (IOException failure) {
            throw new CatalogInfrastructureException("Failed to replace converted-source scopes", failure);
        }
    }

    public record Resolution(CatalogIndexEntry entry, Optional<ConvertedSourceIndex> convertedIndexEntry,
        List<RawModelDiagnostic> diagnostics, boolean cacheCandidate) {
        public Resolution {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(convertedIndexEntry, "convertedIndexEntry");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record MaterializedResolution(CatalogIndexEntry entry, ResolvedContent resolvedContent,
        Optional<ConvertedSourceIndex> convertedIndexEntry, List<RawModelDiagnostic> diagnostics) {
        public MaterializedResolution {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(resolvedContent, "resolvedContent");
            Objects.requireNonNull(convertedIndexEntry, "convertedIndexEntry");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private Resolution resolveLegacy(SourceObservation observation, CatalogLocation location, boolean allowReuse)
        throws CatalogBuildException {
        var indexes = storage == null ? this.indexes : storage.scope(observation.key().root()).indexes();
        var objects = storage == null ? this.objects : storage.scope(observation.key().root()).objects();
        var sourcePath =
            observation.key().root().rootKind().namespace() + "/" + observation.key().relativePath().value();
        com.elfmcys.ysm.model.storage.AtomicSharedCache.Staging temporary = null;
        try {
            var indexed = allowReuse ? candidate(indexes, sourcePath) : Optional.<ConvertedSourceIndex>empty();
            var reuse = SourceReuseObservation.capture(observation).orElseThrow();
            var hit = reusable(indexes, observation, indexed, Optional.of(reuse));
            if (hit.isPresent())
                return hit.get();
            temporary = objects.stage();
            var staged = legacyImporter.stage(observation.absolutePath(), temporary);
            var stored = objects.commit(staged, location, temporary);
            var sourceIndex = new ConvertedSourceIndex(stored.identity(), sourcePath, indexes.cacheVersion(),
                stored.location(), stored.file(), reuse, stored.metadata());
            var published = publish(indexes, sourceIndex);
            return new Resolution(catalogEntry(observation, published.entry(), published.owner()),
                Optional.of(published.entry()), List.of(), false);
        } catch (LegacyModelImportException failure) {
            throw new ModelSourceException("Legacy model import failed with status " + failure.statusCode(), failure);
        } catch (FileSystemException | SecurityException failure) {
            throw new CatalogInfrastructureException("Failed to access legacy conversion storage", failure);
        } catch (IOException failure) {
            throw new CatalogInfrastructureException("Failed to publish converted legacy model", failure);
        } finally {
            if (temporary != null) {
                try {
                    temporary.close();
                } catch (IOException cleanupFailure) {
                    // A failed best-effort cleanup must not change publication authority.
                }
            }
        }
    }
}
