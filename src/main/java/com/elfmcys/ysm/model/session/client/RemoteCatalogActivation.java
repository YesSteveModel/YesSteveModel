package com.elfmcys.ysm.model.session.client;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.session.client.state.ActivationFailure;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One connection's active/local/cache/server activation pipeline. */
public final class RemoteCatalogActivation implements AutoCloseable {
    private final Supplier<CatalogIndexSnapshot> localIndex;
    private final RemoteModelStore store;
    private final RemoteMetadataFetcher fetcher;
    private final Executor workers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<Hash256, EntryOwner> owners = new LinkedHashMap<>();
    private final Set<CompletableFuture<?>> metadataActions = new HashSet<>();
    private volatile CatalogSnapshot active;
    private RemotePublicationSnapshot publication;
    private boolean activeReleased;

    public RemoteCatalogActivation(Supplier<CatalogIndexSnapshot> localIndex, CatalogSnapshot active,
        RemoteModelStore store, RemoteMetadataFetcher fetcher, Executor workers) {
        this.localIndex = Objects.requireNonNull(localIndex, "localIndex");
        this.active = Objects.requireNonNull(active, "active");
        this.store = Objects.requireNonNull(store, "store");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    public CompletableFuture<ActivationSnapshot> activate(RemotePublicationSnapshot publication) {
        return activate(ActivationSnapshot.pending(Objects.requireNonNull(publication, "publication")));
    }

    public CompletableFuture<ActivationSnapshot> activate(ActivationSnapshot base) {
        Objects.requireNonNull(base, "base");
        var starts = new ArrayList<EntryOwner>();
        var retired = new ArrayList<EntryOwner>();
        var cancelledActions = new ArrayList<CompletableFuture<?>>();
        var results = new LinkedHashMap<Hash256, CompletableFuture<ActivationSnapshot.State>>();
        synchronized (this) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Activation owner is closed"));
            }
            if (publication != null && !sameContent(publication, base.publication())) {
                retired.addAll(owners.values());
                owners.clear();
                cancelledActions.addAll(metadataActions);
                metadataActions.clear();
            }
            publication = base.publication();
            owners.entrySet().removeIf(current -> {
                var expected = publication.entries().get(current.getKey());
                var state = base.entries().get(current.getKey());
                if (expected != null && expected.sameContent(current.getValue().entry)
                    && state instanceof ActivationSnapshot.Pending) {
                    return false;
                }
                retired.add(current.getValue());
                return true;
            });
            base.entries().forEach((modelId, state) -> {
                if (!(state instanceof ActivationSnapshot.Pending pending)) {
                    results.put(modelId, CompletableFuture.completedFuture(state));
                    return;
                }
                var owner = owners.get(modelId);
                if (owner == null) {
                    owner = new EntryOwner(pending.publication());
                    owners.put(modelId, owner);
                    starts.add(owner);
                }
                results.put(modelId, owner.result.thenApply(result -> rebase(result, state.publication())));
            });
        }
        retired.forEach(EntryOwner::close);
        cancelledActions.forEach(action -> action.cancel(false));
        var all = CompletableFuture.allOf(results.values().toArray(CompletableFuture[]::new));
        var batch = all.thenApply(ignored -> {
            var states = new LinkedHashMap<Hash256, ActivationSnapshot.State>();
            results.forEach((modelId, result) -> states.put(modelId, result.join()));
            return new ActivationSnapshot(base.publication(), states);
        }).whenComplete((ignored, failure) -> {
            if (failure == null) {
                releaseActive();
            }
        });
        start(starts);
        return batch;
    }

    private static boolean sameContent(RemotePublicationSnapshot previous, RemotePublicationSnapshot current) {
        return previous.entries().keySet().equals(current.entries().keySet()) && previous.entries().entrySet().stream()
            .allMatch(entry -> entry.getValue().sameContent(current.entries().get(entry.getKey())));
    }

    private static ActivationSnapshot.State rebase(ActivationSnapshot.State state, PublicationEntry entry) {
        if (state instanceof ActivationSnapshot.Ready ready) {
            return new ActivationSnapshot.Ready(entry, ready.record());
        }
        if (state instanceof ActivationSnapshot.Failed failed) {
            return new ActivationSnapshot.Failed(entry, failed.failure());
        }
        return new ActivationSnapshot.Pending(entry);
    }

    public synchronized void restart(Set<Hash256> modelIds) {
        Objects.requireNonNull(modelIds, "modelIds");
        modelIds.forEach(modelId -> {
            var owner = owners.remove(modelId);
            if (owner != null) {
                owner.close();
            }
        });
    }

    private void start(List<EntryOwner> starts) {
        if (starts.isEmpty()) {
            return;
        }
        var local = new LinkedHashMap<EntryOwner, CompletableFuture<ActivationSnapshot.State>>();
        starts.forEach(owner -> {
            var lookup = CompletableFuture.supplyAsync(() -> activateLocal(owner, owner.entry), workers);
            owner.bind(lookup);
            local.put(owner, lookup);
        });
        var settled = local.values().stream().map(lookup -> lookup.handle((state, failure) -> null))
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(settled).whenComplete((ignored, impossible) -> {
            var missing = new ArrayList<EntryOwner>();
            local.forEach((owner, lookup) -> {
                try {
                    var state = lookup.join();
                    if (state == null) {
                        missing.add(owner);
                    } else {
                        finish(owner, state);
                    }
                } catch (CompletionException | CancellationException failure) {
                    finishFailure(owner, failure);
                }
            });
            fetchMetadata(missing);
        });
    }

    private ActivationSnapshot.State activateLocal(EntryOwner owner, PublicationEntry entry) {
        try {
            requireOpen(owner);
            var current = active.byModelId().get(entry.modelId());
            if (current != null && entry.identity().equals(current.binding().content().representation().identity())) {
                return new ActivationSnapshot.Ready(entry, record(entry, current.binding().content()));
            }
            var currentIndex = Objects.requireNonNull(localIndex.get(), "local index supplier returned null");
            var candidates = currentIndex.findCandidates(entry.identity());
            for (var candidate : candidates) {
                if (!candidate.identity().equals(entry.identity())) {
                    continue;
                }
                var content = openCandidate(owner, candidate);
                if (content != null) {
                    return new ActivationSnapshot.Ready(entry, record(entry, content));
                }
            }
            if (current != null && current.binding().content() instanceof ManagedContainer) {
                return new ActivationSnapshot.Ready(entry, record(entry, current.binding().content()));
            }
            for (var candidate : candidates) {
                if (candidate.identity().equals(entry.identity())) {
                    continue;
                }
                var content = openCandidate(owner, candidate);
                if (content != null) {
                    return new ActivationSnapshot.Ready(entry, record(entry, content));
                }
            }
            var cached = store.probeMetadata(entry.identity());
            if (cached.isPresent()) {
                return new ActivationSnapshot.Ready(entry, record(entry, cached.orElseThrow()));
            }
            return null;
        } catch (AssetLoadException failure) {
            return failed(entry, failure.reason(), failure);
        } catch (IOException | RuntimeException failure) {
            return failed(entry, failure instanceof FileSystemException || failure instanceof SecurityException
                ? AssetLoadException.Reason.ACCESS : AssetLoadException.Reason.CONTENT, failure);
        }
    }

    private static ManagedContainer openCandidate(EntryOwner owner, CatalogIndexEntry candidate)
        throws AssetLoadException {
        try {
            if (owner.cancelled()) {
                throw AssetLoadException.access("Remote catalog activation is closed");
            }
            return ManagedContainer.openIndexed(candidate);
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                throw failure;
            }
            return null;
        } catch (FileSystemException | SecurityException access) {
            throw AssetLoadException.access("Failed to access local model candidate: " + candidate.backingFile(),
                access);
        } catch (IOException | RuntimeException invalidContent) {
            return null;
        }
    }

    private void fetchMetadata(List<EntryOwner> owners) {
        if (owners.isEmpty()) {
            return;
        }
        var byContainer = new LinkedHashMap<Hash256, List<EntryOwner>>();
        owners.forEach(owner -> byContainer
            .computeIfAbsent(owner.entry.identity().containerId(), ignored -> new ArrayList<>()).add(owner));
        var identities = byContainer.values().stream().map(entries -> entries.get(0).entry.identity())
            .sorted(Comparator.comparing(ModelFileIdentity::containerId)).toList();
        var prefixes = new LinkedHashMap<Hash256, byte[]>();
        var transfer = fetcher.fetchMetadata(identities, (identity, metadataPrefix) -> {
            var bytes = new byte[metadataPrefix.size()];
            metadataPrefix.nio().get(bytes);
            prefixes.put(identity.containerId(), bytes);
        });
        trackMetadataAction(transfer);
        transfer.whenComplete((ignored, failure) -> {
            if (failure != null) {
                owners.forEach(owner -> finishFailure(owner, failure));
                return;
            }
            var commit = CompletableFuture.supplyAsync(() -> {
                var candidates = new LinkedHashMap<EntryOwner, RemoteModelContent>();
                try {
                    for (var owner : owners) {
                        requireOpen(owner);
                        var prefix = prefixes.get(owner.entry.identity().containerId());
                        if (prefix == null)
                            throw new IOException("Metadata action completed without an exact prefix");
                        candidates.put(owner,
                            store.commitMetadataPrefix(owner.entry.identity(), ArrayBuffer.borrow(prefix)));
                    }
                    return candidates;
                } catch (IOException failureCause) {
                    candidates.values().forEach(content -> content.representation().close());
                    throw new CompletionException(failureCause);
                }
            }, workers);
            trackMetadataAction(commit);
            commit.whenComplete((candidates, commitFailure) -> {
                if (commitFailure != null) {
                    owners.forEach(owner -> finishFailure(owner, commitFailure));
                    return;
                }
                owners.forEach(owner -> finish(owner,
                    new ActivationSnapshot.Ready(owner.entry, record(owner.entry, candidates.get(owner)))));
            });
        });
    }

    private synchronized void trackMetadataAction(CompletableFuture<?> action) {
        if (closed.get()) {
            action.cancel(false);
            return;
        }
        metadataActions.add(action);
        action.whenComplete((ignored, failure) -> {
            synchronized (RemoteCatalogActivation.this) {
                metadataActions.remove(action);
            }
        });
    }

    private static CatalogRecord record(PublicationEntry entry, ModelContent content) {
        var location = CatalogLocation.parse(entry.path().value());
        var binding = new CatalogContentBinding(entry.modelId(), content);
        var presentation = new CatalogRecord(location, binding).entry().presentation();
        return new CatalogRecord(new com.elfmcys.ysm.model.catalog.snapshot.CatalogEntry(entry.modelId(), entry.path(),
            entry.access(), presentation), location, binding);
    }

    private static ActivationSnapshot.Failed failed(PublicationEntry entry, AssetLoadException.Reason reason,
        Throwable failure) {
        com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Remote catalog activation failed location={}", entry.path().value(),
            failure);
        var kind = reason == AssetLoadException.Reason.ACCESS ? ActivationFailure.Kind.TRANSIENT_ACCESS
            : ActivationFailure.Kind.DETERMINISTIC_CONTENT;
        return new ActivationSnapshot.Failed(entry, new ActivationFailure(kind,
            reason == AssetLoadException.Reason.ACCESS ? "Source is inaccessible" : "Content validation failed"));
    }

    private void requireOpen(EntryOwner owner) throws AssetLoadException {
        if (closed.get() || owner.cancelled()) {
            throw AssetLoadException.access("Remote catalog activation is closed");
        }
    }

    private void finishFailure(EntryOwner owner, Throwable error) {
        if (owner.cancelled()) {
            return;
        }
        var cause = unwrap(error);
        if (cause instanceof AssetLoadException asset) {
            finish(owner, failed(owner.entry, asset.reason(), asset));
        } else if (cause instanceof IOException io) {
            finish(owner, failed(owner.entry, io instanceof FileSystemException ? AssetLoadException.Reason.ACCESS
                : AssetLoadException.Reason.CONTENT, io));
        } else if (cause instanceof CancellationException) {
            finish(owner, failed(owner.entry, AssetLoadException.Reason.ACCESS, cause));
        } else if (cause instanceof Error fatal) {
            throw fatal;
        } else {
            finish(owner, failed(owner.entry, cause instanceof SecurityException ? AssetLoadException.Reason.ACCESS
                : AssetLoadException.Reason.CONTENT, cause));
        }
    }

    private static Throwable unwrap(Throwable error) {
        var current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void finish(EntryOwner owner, ActivationSnapshot.State state) {
        synchronized (this) {
            var expected = publication == null ? null : publication.entries().get(owner.entry.modelId());
            if (closed.get() || owners.get(owner.entry.modelId()) != owner || !owner.entry.sameContent(expected)) {
                owner.result.completeExceptionally(new CancellationException("Activation owner was superseded"));
            } else {
                owner.result.complete(state);
            }
        }
        owner.terminal();
    }

    private synchronized void releaseActive() {
        if (!activeReleased) {
            activeReleased = true;
            active = CatalogSnapshot.empty();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            metadataActions.forEach(action -> action.cancel(false));
            metadataActions.clear();
            owners.values().forEach(EntryOwner::close);
            owners.clear();
            publication = null;
            active = CatalogSnapshot.empty();
        }
    }

    private static final class EntryOwner implements AutoCloseable {
        private final PublicationEntry entry;
        private final CompletableFuture<ActivationSnapshot.State> result = new CompletableFuture<>();
        private CompletableFuture<?> active;
        private boolean cancelled;

        private EntryOwner(PublicationEntry entry) {
            this.entry = entry;
        }

        private synchronized boolean cancelled() {
            return cancelled;
        }

        private void bind(CompletableFuture<?> next) {
            synchronized (this) {
                if (!cancelled) {
                    active = next;
                    return;
                }
            }
            next.cancel(false);
        }

        private synchronized void terminal() {
            active = null;
        }

        @Override
        public void close() {
            final CompletableFuture<?> cancellation;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                cancellation = active;
                active = null;
            }
            if (cancellation != null) {
                cancellation.cancel(false);
            }
            result.completeExceptionally(new CancellationException("Activation owner was superseded"));
        }
    }
}
