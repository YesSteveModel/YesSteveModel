package com.elfmcys.ysm.model.catalog.client;

import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.client.entry.ClientFailedCatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Client presentation adapter; content lookup remains in the wrapped Catalog snapshot. */
public final class ClientCatalogSnapshot {
    private final CatalogSnapshot catalog;
    private final List<ClientFailedCatalogEntry> failed;
    private final RemotePublicationSnapshot publication;

    public ClientCatalogSnapshot(CatalogSnapshot catalog) {
        this(catalog, List.of());
    }

    public ClientCatalogSnapshot(CatalogSnapshot catalog, List<ClientFailedCatalogEntry> failed) {
        this(catalog, failed, null);
    }

    public ClientCatalogSnapshot(CatalogSnapshot catalog, List<ClientFailedCatalogEntry> failed,
        RemotePublicationSnapshot publication) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.failed = List.copyOf(failed);
        this.publication = publication;
    }

    public boolean permitsRuntimeContent(Hash256 modelId) {
        var record = catalog.byModelId().get(modelId);
        if (record == null)
            return false;
        if (publication == null)
            return true;
        var entry = publication.entries().get(modelId);
        if (entry != null)
            return entry.runtimeContentAllowed();
        return record.location().rootKind() == CatalogRootKind.BUILTIN
            && record.location().path().value().equals("default");
    }

    public static ClientCatalogSnapshot empty() {
        return new ClientCatalogSnapshot(CatalogSnapshot.empty());
    }

    public Map<Hash256, ClientCatalogEntry> models() {
        var result = new LinkedHashMap<Hash256, ClientCatalogEntry>();
        catalog.byModelId().forEach((modelId, record) -> {
            var content = record.binding().content();
            result.put(modelId, new ClientCatalogEntry(record.entry(), content, origin(record, content)));
        });
        return Map.copyOf(result);
    }

    public List<ModelPackDescriptor> packs() {
        return catalog.packs();
    }

    public List<ClientFailedCatalogEntry> failed() {
        return failed;
    }

    public Optional<ClientCatalogEntry> find(Hash256 modelId) {
        var record = catalog.byModelId().get(modelId);
        return record == null ? Optional.empty() : Optional.of(new ClientCatalogEntry(record.entry(),
            record.binding().content(), origin(record, record.binding().content())));
    }

    public CatalogSnapshot catalog() {
        return catalog;
    }

    private static ClientCatalogEntry.Origin origin(CatalogRecord record, ModelContent content) {
        var binding = content.storageBinding();
        if (binding != null) {
            return switch (binding.kind()) {
                case REMOTE -> ClientCatalogEntry.Origin.SERVER;
                case BUILTIN -> ClientCatalogEntry.Origin.BUILTIN;
                case LOCAL -> localOrigin(binding.localSource().orElseThrow().key().root().rootKind());
            };
        }
        if (content instanceof RemoteModelContent)
            return ClientCatalogEntry.Origin.SERVER;
        if (content instanceof com.elfmcys.ysm.model.storage.ManagedContainer local)
            return localOrigin(local.location().rootKind());
        return localOrigin(record.location().rootKind());
    }

    private static ClientCatalogEntry.Origin localOrigin(CatalogRootKind kind) {
        return switch (kind) {
            case BUILTIN -> ClientCatalogEntry.Origin.BUILTIN;
            case CUSTOM -> ClientCatalogEntry.Origin.CUSTOM;
            case AUTH -> ClientCatalogEntry.Origin.AUTH;
        };
    }
}
