package com.elfmcys.ysm.model.session.client.state;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable Pending/Ready/Failed state for one publication owner. */
public final class ActivationSnapshot {
    private final RemotePublicationSnapshot publication;
    private final Map<Hash256, State> entries;

    public ActivationSnapshot(RemotePublicationSnapshot publication, Map<Hash256, State> entries) {
        this.publication = Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(entries, "entries");
        if (!publication.entries().keySet().equals(entries.keySet())) {
            throw new IllegalArgumentException("Activation entries do not match publication authority");
        }
        var copy = new LinkedHashMap<Hash256, State>();
        publication.entries().forEach((modelId, expected) -> {
            var state = Objects.requireNonNull(entries.get(modelId), "state");
            if (!expected.equals(state.publication())) {
                throw new IllegalArgumentException("Activation state does not match publication entry");
            }
            if (state instanceof Ready ready) {
                var record = ready.record();
                if (!modelId.equals(record.entry().modelId()) || !expected.path().equals(record.entry().path())
                    || expected.access() != record.entry().access()) {
                    throw new IllegalArgumentException("Ready record does not match publication authority");
                }
            }
            copy.put(modelId, state);
        });
        this.entries = Collections.unmodifiableMap(copy);
    }

    public static ActivationSnapshot pending(RemotePublicationSnapshot publication) {
        var states = new LinkedHashMap<Hash256, State>();
        publication.entries().forEach((modelId, entry) -> states.put(modelId, new Pending(entry)));
        return new ActivationSnapshot(publication, states);
    }

    public RemotePublicationSnapshot publication() {
        return publication;
    }

    public Map<Hash256, State> entries() {
        return entries;
    }

    public boolean terminal() {
        return entries.values().stream().noneMatch(Pending.class::isInstance);
    }

    public CatalogSnapshot readyCatalog() {
        var records = new LinkedHashMap<Hash256, CatalogRecord>();
        entries.forEach((modelId, state) -> {
            if (state instanceof Ready ready) {
                records.put(modelId, ready.record());
            }
        });
        return new CatalogSnapshot(records, publication.packs(), ModelScanReport.empty());
    }

    public List<Failed> failures() {
        return entries.values().stream().filter(Failed.class::isInstance).map(Failed.class::cast).toList();
    }

    public sealed interface State permits Pending, Ready, Failed {
        PublicationEntry publication();
    }

    public record Pending(PublicationEntry publication) implements State {
        public Pending {
            Objects.requireNonNull(publication, "publication");
        }
    }

    public record Ready(PublicationEntry publication, CatalogRecord record) implements State {
        public Ready {
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(record, "record");
        }
    }

    public record Failed(PublicationEntry publication, ActivationFailure failure) implements State {
        public Failed {
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(failure, "failure");
        }
    }
}
