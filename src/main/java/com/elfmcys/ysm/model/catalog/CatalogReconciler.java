package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.RawModelDiagnostic;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogBuildException;
import com.elfmcys.ysm.model.catalog.source.CatalogInfrastructureException;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelPackScanner;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.catalog.source.ResolvedContent;
import com.elfmcys.ysm.model.catalog.source.PackObservation;
import com.elfmcys.ysm.model.catalog.source.RootInventoryState;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanError;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndex;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashMismatchException;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds one complete immutable identity/content pair without publishing it. */
public final class CatalogReconciler {
    private final List<ModelCatalogSource> roots;
    private final ModelSourceResolver resolver;
    private final ModelPackScanner packScanner = new ModelPackScanner();
    private final Set<Hash256> builtinReservedIds;
    private final Map<Hash256, CatalogRecord> fixedRecords;
    private final CatalogIndexSnapshot builtinIndex;
    private final BuiltinModelIndex builtinContract;

    public CatalogReconciler(List<ModelCatalogSource> roots, ModelSourceResolver resolver,
        Set<Hash256> builtinReservedIds, ManagedContainer intrinsicDefault, CatalogIndexSnapshot builtinIndex) {
        this(roots, resolver, builtinReservedIds, intrinsicDefault, builtinIndex, null);
    }

    public CatalogReconciler(List<ModelCatalogSource> roots, ModelSourceResolver resolver,
        BuiltinModelIndex builtinContract, ManagedContainer intrinsicDefault) {
        this(roots, resolver, builtinContract.entries().stream().map(BuiltinModelIndex.Entry::modelHash)
            .collect(Collectors.toUnmodifiableSet()), intrinsicDefault, CatalogIndexSnapshot.empty(), builtinContract);
    }

    private CatalogReconciler(List<ModelCatalogSource> roots, ModelSourceResolver resolver,
        Set<Hash256> builtinReservedIds, ManagedContainer intrinsicDefault, CatalogIndexSnapshot builtinIndex,
        BuiltinModelIndex builtinContract) {
        this.roots = List.copyOf(roots);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.builtinReservedIds = Set.copyOf(builtinReservedIds);
        Objects.requireNonNull(intrinsicDefault, "intrinsicDefault");
        this.builtinIndex = Objects.requireNonNull(builtinIndex, "builtinIndex");
        this.builtinContract = builtinContract;
        fixedRecords = Map.of(intrinsicDefault.modelId(), new CatalogRecord(intrinsicDefault.location(),
            new CatalogContentBinding(intrinsicDefault.modelId(), intrinsicDefault)));
    }

    ScanDiscovery discoverIncremental() {
        var startedAt = Instant.now();
        var complete = new LinkedHashMap<CatalogRoot, RootInventoryState.Complete>();
        var inventories = new ArrayList<RootInventoryState>();
        var errors = new ArrayList<ModelScanError>();
        for (var root : roots) {
            var inventory = ModelSourceDiscovery.inventory(root);
            errors.addAll(inventory.errors());
            if (root.rootKind() == CatalogRootKind.BUILTIN && builtinContract != null) {
                try {
                    builtinContract.validateCoverage(
                        inventory.sources().values().stream().map(source -> source.key().relativePath()).toList());
                } catch (IOException | RuntimeException failure) {
                    errors.add(ModelScanError.infrastructure(root.rootKind(), root.publicRoot().prefix(),
                        "BUILTIN_INVENTORY_MISMATCH", failure));
                    continue;
                }
            }
            inventories.add(inventory);
            if (inventory instanceof RootInventoryState.Complete value) {
                complete.put(root.publicRoot(), value);
            }
        }
        var observations = inventories.stream().flatMap(root -> root.sources().values().stream())
            .filter(source -> !(source.key().root().rootKind() == CatalogRootKind.BUILTIN
                && source.key().relativePath().value().equals("default")))
            .sorted(Comparator
                .comparing((SourceObservation source) -> source.key().root().location(source.key().relativePath())))
            .toList();
        var packs = inventories.stream().flatMap(root -> root.packs().values().stream())
            .sorted(Comparator.comparing((PackObservation pack) -> pack.key().location())).toList();
        return new ScanDiscovery(startedAt, complete, observations, packs, errors, complete.size() == roots.size());
    }

    ResolvedSource resolveIncremental(SourceObservation observation, ManagedContainer reusable) {
        ModelSourceResolver.MaterializedResolution result = null;
        try {
            result = resolver.resolveMaterialized(observation, reusable);
            if (builtinContract != null && observation.key().root().rootKind() == CatalogRootKind.BUILTIN) {
                var expected = builtinContract.require(observation.key().relativePath());
                if (!expected.equals(result.entry().modelId())) {
                    throw new ModelHashMismatchException(result.entry().location(), expected, result.entry().modelId());
                }
            }
            var after = ModelSourceDiscovery.observe(observation.key(), observation.logicalPath());
            if (!after.equals(observation)) {
                throw new IOException("Model source changed while loading: " + observation.absolutePath());
            }
            var warnings = scanWarnings(observation, result.diagnostics());
            return new ResolvedSource(observation, result.entry(), result.resolvedContent(),
                result.convertedIndexEntry(), warnings, null);
        } catch (Throwable failure) {
            if (result != null) {
                result.resolvedContent().close();
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            return new ResolvedSource(observation, null, null, Optional.empty(), List.of(),
                ModelScanError.from(observation.key().root().rootKind(),
                    observation.key().root().location(observation.key().relativePath()).value(), failure));
        }
    }

    ResolvedPack resolveIncremental(PackObservation observation) {
        try {
            return new ResolvedPack(observation, packScanner.scan(observation), null);
        } catch (Throwable failure) {
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            return new ResolvedPack(observation, Optional.empty(), ModelScanError
                .from(observation.key().root().rootKind(), observation.key().location().value(), failure));
        }
    }

    void verifyDiscovery(ScanDiscovery discovery) throws CatalogInfrastructureException {
        if (!discovery.allRootsComplete()) {
            throw new CatalogInfrastructureException("Catalog inventory is incomplete");
        }
        for (var root : roots) {
            var expected = discovery.completeRoots().get(root.publicRoot());
            if (expected == null) {
                continue;
            }
            if (!expected.equals(ModelSourceDiscovery.inventory(root))) {
                throw new CatalogInfrastructureException("Model root changed while scanning: " + root.path());
            }
        }
    }

    void replaceIncrementalIndex(java.util.List<CatalogRootIdentity> rootNamespaces,
        Collection<ConvertedSourceIndex> entries, Collection<CatalogIndexEntry> retained)
        throws CatalogInfrastructureException {
        resolver.replaceIndex(rootNamespaces, entries, retained);
    }

    Map<Hash256, CatalogRecord> fixedRecords() {
        return fixedRecords;
    }

    boolean conflictsWithBuiltin(CatalogIndexEntry entry) {
        if (entry.location().rootKind() == CatalogRootKind.BUILTIN) {
            return false;
        }
        return builtinReservedIds.contains(entry.modelId());
    }

    record ScanDiscovery(Instant startedAt, Map<CatalogRoot, RootInventoryState.Complete> completeRoots,
        List<SourceObservation> sources, List<PackObservation> packs, List<ModelScanError> errors,
        boolean allRootsComplete) {
        ScanDiscovery {
            completeRoots = Map.copyOf(completeRoots);
            sources = List.copyOf(sources);
            packs = List.copyOf(packs);
            errors = List.copyOf(errors);
        }
    }

    record ResolvedSource(SourceObservation observation, CatalogIndexEntry entry, ResolvedContent content,
        Optional<ConvertedSourceIndex> convertedIndex, List<ModelScanWarning> warnings, ModelScanError error) {
        ResolvedSource {
            Objects.requireNonNull(observation, "observation");
            convertedIndex = Objects.requireNonNull(convertedIndex, "convertedIndex");
            warnings = List.copyOf(warnings);
            if ((entry == null) == (error == null) || (content == null) != (entry == null)) {
                throw new IllegalArgumentException("Resolved source must contain exactly one outcome");
            }
        }
    }

    record ResolvedPack(PackObservation observation, Optional<ModelPackDescriptor> pack, ModelScanError error) {
        ResolvedPack {
            Objects.requireNonNull(observation, "observation");
            pack = Objects.requireNonNull(pack, "pack");
        }
    }

    /** Builds the side-local startup state without retaining optional content process-wide. */
    public CatalogCandidate materializeBuiltins() throws CatalogBuildException {
        return materialize(List.copyOf(builtinIndex.entries()), builtinIndex.packs(),
            new ArrayList<>(builtinIndex.report().errors()), new ArrayList<>(builtinIndex.report().warnings()),
            Instant.now());
    }

    private CatalogCandidate materialize(List<CatalogIndexEntry> entries, List<ModelPackDescriptor> packs,
        List<ModelScanError> errors, List<ModelScanWarning> warnings, Instant startedAt)
        throws CatalogInfrastructureException {
        var content = new ArrayList<ManagedContainer>();
        for (var entry : entries) {
            try {
                content.add(ManagedContainer.openIndexed(entry));
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw new CatalogInfrastructureException(
                        "Failed to access indexed model container: " + entry.backingFile(), failure);
                }
                errors.add(ModelScanError.from(entry.location().rootKind(), entry.location().value(), failure));
            } catch (FileSystemException | SecurityException failure) {
                throw new CatalogInfrastructureException(
                    "Failed to access indexed model container: " + entry.backingFile(), failure);
            } catch (IOException | RuntimeException failure) {
                errors.add(ModelScanError.from(entry.location().rootKind(), entry.location().value(), failure));
            }
        }

        var records = selectFirstValid(content, errors);
        var report = new ModelScanReport(startedAt, Instant.now(), errors, warnings);
        var index = new CatalogIndexSnapshot(entries, packs, report);
        var snapshot = new CatalogSnapshot(records, packs, report);
        return new CatalogCandidate(index, snapshot);
    }

    private static List<ModelScanWarning> scanWarnings(SourceObservation observation,
        List<RawModelDiagnostic> diagnostics) {
        var warnings = new ArrayList<ModelScanWarning>();
        for (var kind : RawModelDiagnostic.Kind.values()) {
            int occurrences = (int)diagnostics.stream().filter(diagnostic -> diagnostic.kind() == kind).count();
            if (occurrences == 0) {
                continue;
            }
            var warningKind = switch (kind) {
                case UNKNOWN_AUDIO -> ModelScanWarning.Kind.UNKNOWN_AUDIO;
                case INVALID_AUDIO -> ModelScanWarning.Kind.INVALID_AUDIO;
            };
            warnings.add(new ModelScanWarning(observation.key().root().rootKind(),
                observation.key().relativePath().value(), warningKind, occurrences));
        }
        return List.copyOf(warnings);
    }

    private Map<Hash256, CatalogRecord> selectFirstValid(List<ManagedContainer> candidates,
        List<ModelScanError> errors) {
        var result = new LinkedHashMap<>(fixedRecords);
        for (var candidate : candidates) {
            if (candidate.location().rootKind() != CatalogRootKind.BUILTIN
                && builtinReservedIds.contains(candidate.modelId())) {
                errors.add(ModelScanError.from(candidate.location().rootKind(), candidate.location().value(),
                    new IllegalArgumentException("Model identity is reserved by a builtin model")));
                continue;
            }
            if (result.putIfAbsent(candidate.modelId(), new CatalogRecord(candidate.location(),
                new CatalogContentBinding(candidate.modelId(), candidate))) != null)
                errors.add(ModelScanError.from(candidate.location().rootKind(), candidate.location().value(),
                    new IllegalArgumentException("Duplicate model identity")));
        }
        return result;
    }
}
