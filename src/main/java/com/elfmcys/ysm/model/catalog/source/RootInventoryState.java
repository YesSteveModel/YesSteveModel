package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.model.domain.ModelScanError;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;

public sealed interface RootInventoryState permits RootInventoryState.Complete, RootInventoryState.Incomplete {
    CatalogRootIdentity root();
    Map<ModelSourceKey, SourceObservation> sources();
    Map<ModelPackSourceKey, PackObservation> packs();

    default List<ModelScanError> errors() {
        return List.of();
    }

    record Complete(CatalogRootIdentity root, Map<ModelSourceKey, SourceObservation> sources,
        Map<ModelPackSourceKey, PackObservation> packs) implements RootInventoryState {
        public Complete {
            Objects.requireNonNull(root, "root");
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            packs = Collections.unmodifiableMap(new LinkedHashMap<>(packs));
        }
    }

    record Incomplete(CatalogRootIdentity root, Map<ModelSourceKey, SourceObservation> sources,
        Map<ModelPackSourceKey, PackObservation> packs, List<ModelScanError> errors) implements RootInventoryState {
        public Incomplete {
            Objects.requireNonNull(root, "root");
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            packs = Collections.unmodifiableMap(new LinkedHashMap<>(packs));
            errors = List.copyOf(errors);
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("Incomplete inventory requires a diagnostic");
            }
        }
    }
}
