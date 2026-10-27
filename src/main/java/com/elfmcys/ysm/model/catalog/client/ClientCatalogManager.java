package com.elfmcys.ysm.model.catalog.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.catalog.client.entry.ClientFailedCatalogEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.util.ModelIdUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Client-side presentation adapter over its main-thread Catalog owner. */
public final class ClientCatalogManager implements AutoCloseable {
    private final ReloadableModelCatalog owner;
    private final ReloadableModelCatalog.Subscription subscription;

    private volatile ClientCatalogSnapshot catalog;
    private volatile LocalCatalogState local;
    private boolean sessionPublished;
    private volatile Consumer<CatalogChange> listener = ignored -> {
    };

    public ClientCatalogManager(ReloadableModelCatalog owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        var initial = owner.currentCandidate();
        catalog = new ClientCatalogSnapshot(initial.snapshot());
        local = new LocalCatalogState(initial.index(), initial.snapshot());
        subscription = owner.subscribe(this::apply);
    }

    public void setListener(Consumer<CatalogChange> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public CompletableFuture<LocalCatalogState> initialize() {
        return CompletableFuture.completedFuture(local);
    }

    public ClientCatalogSnapshot snapshot() {
        return catalog;
    }

    public com.elfmcys.ysm.model.catalog.CatalogScanProgress scanProgress() {
        return owner.scanProgress();
    }

    public LocalCatalogState localState() {
        return local;
    }

    public boolean contains(Hash256 modelId) {
        return catalog.catalog().byModelId().containsKey(modelId);
    }

    public Optional<String> findRenderTarget(Hash256 modelId, RenderTargetKind kind, ResourceLocation entityType) {
        var entry = catalog.find(modelId).orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        for (var target : entry.displayRepresentation().view().getRenderTargets()) {
            if (target.kind() == kind && !target.getTextureNames().isEmpty()
                && ModelIdUtil.getEntityIdMatch(target.matches().toArray(String[]::new)).contains(entityType)) {
                return Optional.of(target.id());
            }
        }
        return Optional.empty();
    }

    public Optional<Hash256> resolvePath(String path) {
        return com.elfmcys.ysm.model.catalog.source.CatalogAddressResolver.resolve(path, catalog.catalog().byLocation())
            .found();
    }

    public String displayPath(Hash256 modelId) {
        return catalog.find(modelId).map(ClientCatalogEntry::displayPath).orElse(modelId.toString());
    }

    public synchronized CatalogSnapshot beginRemote() {
        sessionPublished = true;
        var active = local.snapshot();
        var change = remoteStartTransition(catalog, local.snapshot());
        catalog = change.current();
        notifyListener(change);
        return active;
    }

    public static CatalogChange remoteStartTransition(ClientCatalogSnapshot previous, CatalogSnapshot localSnapshot) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(localSnapshot, "localSnapshot");
        return new CatalogChange(previous, new ClientCatalogSnapshot(localSnapshot.intrinsicDefaultOnly()));
    }

    public CatalogIndexSnapshot localIndex() {
        return local.index();
    }

    public synchronized void publishSession(ActivationSnapshot activation) {
        var previous = catalog;
        var failed =
            activation.failures().stream().map(value -> new ClientFailedCatalogEntry(value.publication().modelId(),
                value.publication().path(), value.publication().access(), value.failure().message())).toList();
        catalog = new ClientCatalogSnapshot(activation.readyCatalog().withIntrinsicDefaultFrom(local.snapshot()),
            failed, activation.publication());
        sessionPublished = true;
        notifyListener(new CatalogChange(previous, catalog));
    }

    public synchronized void failRemote() {
        var previous = catalog;
        catalog = new ClientCatalogSnapshot(local.snapshot().intrinsicDefaultOnly());
        sessionPublished = true;
        notifyListener(new CatalogChange(previous, catalog));
    }

    public synchronized void endRemote() {
        if (!sessionPublished) {
            return;
        }
        var previous = catalog;
        sessionPublished = false;
        catalog = new ClientCatalogSnapshot(local.snapshot());
        notifyListener(new CatalogChange(previous, catalog));
    }

    private synchronized void apply(CatalogTransition transition) {
        local = new LocalCatalogState(transition.currentIndex(), transition.current());
        if (!sessionPublished) {
            var previous = catalog;
            catalog = new ClientCatalogSnapshot(transition.current());
            notifyListener(new CatalogChange(previous, catalog));
        }
    }

    private void notifyListener(CatalogChange change) {
        try {
            listener.accept(change);
        } catch (Throwable failure) {
            YesSteveModel.LOGGER.error("Client catalog observer failed after publication", failure);
        }
    }

    @Override
    public void close() {
        subscription.close();
    }

    public record LocalCatalogState(CatalogIndexSnapshot index, CatalogSnapshot snapshot) {
        public LocalCatalogState {
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(snapshot, "snapshot");
        }

        public Map<Hash256, ManagedContainer> models() {
            var result = new LinkedHashMap<Hash256, ManagedContainer>();
            snapshot.byModelId()
                .forEach((modelId, record) -> result.put(modelId, (ManagedContainer)record.binding().content()));
            return Map.copyOf(result);
        }

        public List<ModelPackDescriptor> packs() {
            return snapshot.packs();
        }
    }

    public record CatalogChange(ClientCatalogSnapshot previous, ClientCatalogSnapshot current) {
    }
}
