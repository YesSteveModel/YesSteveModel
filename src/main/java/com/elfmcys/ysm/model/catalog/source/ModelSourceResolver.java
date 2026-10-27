package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.RawModelDiagnostic;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
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
        var indexes = storage == null ? this.indexes : storage.scope(observation.key().root()).indexes();
        var objects = storage == null ? this.objects : storage.scope(observation.key().root()).objects();
        var location = observation.key().root().location(observation.key().relativePath());
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            return validateDirect(observation, location);
        }
        if (observation.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE) {
            return resolveLegacy(observation, location);
        }
        if (observation.key().sourceKind() == ModelSourceKind.UNSUPPORTED_YSM) {
            throw new ModelSourceException("Unsupported .ysm container");
        }

        var rawRelativePath =
            observation.key().root().rootKind().namespace() + "/" + observation.key().relativePath().value();
        // The native archive adapter selects its decoder from the logical entry's extension.
        var input = observation.key().sourceKind() == ModelSourceKind.CURRENT_RAW_ARCHIVE ? observation.logicalPath()
            : observation.absolutePath();
        try {
            if (!ModelSourceDiscovery.observe(observation.key(), observation.logicalPath()).equals(observation)) {
                throw new ModelSourceException("Model source changed before capture: " + observation.logicalPath());
            }
        } catch (IOException failure) {
            throw new CatalogInfrastructureException("Failed to observe model source before capture", failure);
        }
        try (var captured = importer.capture(input)) {
            var indexed = indexes.find(rawRelativePath).filter(entry -> entry.modelId().equals(captured.modelId()));
            if (indexed.isPresent()) {
                var existing = objects.findIdentity(indexed.get(), captured.originVer());
                if (existing.isPresent()) {
                    var identity = existing.get();
                    var entry = new CatalogIndexEntry(identity, location,
                        objects.objectPath(identity.modelId(), identity.containerId()), binding(observation));
                    return new Resolution(entry, indexed, captured.diagnostics());
                }
            }

            try (var temporary = objects.createTemporaryDirectory("convert-")) {
                var compiled = importer.convert(captured, temporary.directory());
                var object = objects.commit(compiled, location);
                var entry = new ConvertedSourceIndex(object, rawRelativePath, indexes.fullModVersion());
                var catalogEntry = new CatalogIndexEntry(object, location,
                    objects.objectPath(object.modelId(), object.containerId()), binding(observation));
                return new Resolution(catalogEntry, Optional.of(entry), compiled.diagnostics());
            }
        } catch (ModelSourceException | CatalogInfrastructureException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new CatalogInfrastructureException("Failed to access converted model storage", failure);
        } catch (RuntimeException failure) {
            throw new ModelSourceException("Failed to capture or convert raw model", failure);
        }
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
                var opened = ManagedContainer.openDirect(observation.absolutePath(), location, reusable);
                var content = storage == null ? opened : opened.withStorageBinding(binding(observation));
                try {
                    DirectContainerAdmission.requireEmbeddedPreview(content);
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
            } catch (RuntimeException failure) {
                throw new ModelSourceException("Direct model container is invalid", failure);
            }
        }
        var resolved = resolve(observation);
        var content = ManagedContainer.openIndexed(resolved.entry(), reusable);
        try {
            return new MaterializedResolution(resolved.entry(), new ResolvedContent(content, reusable),
                resolved.convertedIndexEntry(), resolved.diagnostics());
        } catch (IOException | RuntimeException | Error failure) {
            content.representation().close();
            throw failure;
        }
    }

    private Resolution validateDirect(SourceObservation observation, CatalogLocation location)
        throws CatalogBuildException {
        try {
            var content = ManagedContainer.openDirect(observation.absolutePath(), location);
            try {
                DirectContainerAdmission.requireEmbeddedPreview(content);
                return new Resolution(new CatalogIndexEntry(content.representation().identity(), location,
                    observation.absolutePath(), binding(observation)), Optional.empty(), List.of());
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
        } catch (RuntimeException failure) {
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
                if (converted != null && converted.identity().equals(entry.identity())) {
                    selected.add(converted);
                }
            }
            indexes.replaceScopes(rootNamespaces, selected);
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
                    if (converted != null && converted.identity().equals(entry.identity()))
                        selected.add(converted);
                }
                scope.indexes().replaceScopes(group.getValue(), selected);
            }
        } catch (IOException failure) {
            throw new CatalogInfrastructureException("Failed to replace converted-source scopes", failure);
        }
    }

    public record Resolution(CatalogIndexEntry entry, Optional<ConvertedSourceIndex> convertedIndexEntry,
        List<RawModelDiagnostic> diagnostics) {
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

    private Resolution resolveLegacy(SourceObservation observation, CatalogLocation location)
        throws CatalogBuildException {
        var indexes = storage == null ? this.indexes : storage.scope(observation.key().root()).indexes();
        var objects = storage == null ? this.objects : storage.scope(observation.key().root()).objects();
        var sourcePath =
            observation.key().root().rootKind().namespace() + "/" + observation.key().relativePath().value();
        ConvertedObjectStore.TemporaryDirectory temporary = null;
        try {
            temporary = objects.createTemporaryDirectory("legacy-convert-");
            var staged = legacyImporter.stage(observation.absolutePath(), temporary.directory());
            final ModelFileIdentity object;
            try {
                object = objects.commit(staged, location);
            } catch (IOException | RuntimeException failure) {
                throw LegacyModelImportException.publicationFailure("Failed to commit converted legacy model", failure);
            }
            var sourceIndex = new ConvertedSourceIndex(object, sourcePath, indexes.fullModVersion());
            var catalogEntry = new CatalogIndexEntry(object, location,
                objects.objectPath(object.modelId(), object.containerId()), binding(observation));
            return new Resolution(catalogEntry, Optional.of(sourceIndex), List.of());
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
