package com.elfmcys.ysm.model.session.client;

import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.catalog.source.CatalogRootIdentity;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKey;
import com.elfmcys.ysm.model.storage.CacheRegion;
import com.elfmcys.ysm.model.storage.ContentStorageBinding;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogAccess;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.HierarchyPath;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.session.client.state.ActivationFailure;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.session.client.state.PublicationEntry;
import com.elfmcys.ysm.model.session.client.state.RemotePublicationSnapshot;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.natives.Blake3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RemoteCatalogActivationTest {
    @Test
    void accessOnlyPublicationPreservesAcceptedMetadataAction() throws Exception {
        var beforeEntry = new PublicationEntry(identity, new HierarchyPath("local:custom/restricted"),
            CatalogAccess.AUTHORIZED, true);
        var afterEntry = new PublicationEntry(identity, beforeEntry.path(), beforeEntry.access(), false);
        var before = new RemotePublicationSnapshot(List.of(beforeEntry), Set.of(), List.of(), Map.of());
        var after = new RemotePublicationSnapshot(List.of(afterEntry), Set.of(), List.of(), Map.of());
        var action = new CompletableFuture<Void>();
        var receives = new AtomicReference<RemoteMetadataFetcher.MetadataReceiver>();
        var attempts = new AtomicInteger();
        RemoteMetadataFetcher fetcher = (identities, receiver) -> {
            attempts.incrementAndGet();
            receives.set(receiver);
            return action;
        };
        try (var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("access-only")), fetcher, Runnable::run)) {
            var first = owner.activate(before);
            var second = owner.activate(after);
            assertFalse(action.isCancelled());
            assertEquals(1, attempts.get());
            try (var bytes = prefix()) {
                receives.get().accept(identity, bytes);
            }
            action.complete(null);
            assertTrue(first.join().failures().isEmpty());
            var ready =
                assertInstanceOf(ActivationSnapshot.Ready.class, second.join().entries().get(identity.modelId()));
            assertFalse(ready.publication().runtimeContentAllowed());
            assertEquals(com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry.Origin.SERVER,
                new com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot(
                    new CatalogSnapshot(Map.of(identity.modelId(), ready.record()), List.of(), ModelScanReport.empty()))
                    .find(identity.modelId()).orElseThrow().origin());
            assertEquals(identity, ready.record().binding().content().representation().identity());
        }
    }

    @TempDir
    static Path fixtureRoot;

    @TempDir
    Path temp;

    private static Path fixture;
    private static ModelFileIdentity identity;
    private static ManagedContainer content;

    @BeforeAll
    static void createFixture() throws Exception {
        var manifest = RemoteCatalogActivationTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var vfs = new Directory(source)) {
            fixture = ModelParser.parse(vfs, Files.createDirectories(fixtureRoot.resolve("model")),
                DefaultAnimationFilter.keepAll());
        }
        try (var channel = FileChannel.open(fixture, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        content = ManagedContainer.openIndexed(indexEntry(fixture));
    }

    @Test
    void remoteStartTransitionKeepsIntrinsicDefaultVisible() {
        var local = new CatalogSnapshot(Map.of(identity.modelId(),
            new CatalogRecord(new CatalogLocation(CatalogRootKind.BUILTIN, new ModelPath("default")),
                new CatalogContentBinding(identity.modelId(), content))),
            List.of(), ModelScanReport.empty());
        var previous = ClientCatalogSnapshot.empty();

        var change = ClientCatalogManager.remoteStartTransition(previous, local);

        assertSame(previous, change.previous());
        assertEquals(Set.of(identity.modelId()), change.current().catalog().byModelId().keySet());
        var retained = change.current().catalog().byModelId().get(identity.modelId());
        assertEquals(CatalogRootKind.BUILTIN, retained.location().rootKind());
        assertEquals(new ModelPath("default"), retained.location().path());
        assertSame(content, retained.binding().content());
    }

    @Test
    void activeAndLocalExactHitsSkipNetwork() throws Exception {
        var publication = publication(identity.modelId(), identity.containerId(), "remote");
        var fetches = new AtomicInteger();
        RemoteMetadataFetcher fetcher = metadata(requestedIdentity -> {
            fetches.incrementAndGet();
            return CompletableFuture.failedFuture(AssetLoadException.access("unexpected network fetch"));
        });
        var store = new RemoteModelStore(temp.resolve("active"));
        var activeRecord = record(content, "local");
        var active = new CatalogSnapshot(Map.of(identity.modelId(), activeRecord), List.of(), ModelScanReport.empty());
        var activeResult =
            new RemoteCatalogActivation(CatalogIndexSnapshot::empty, active, store, fetcher, Runnable::run)
                .activate(publication).join();
        assertEquals(Set.of(identity.modelId()), activeResult.readyCatalog().byModelId().keySet());

        var index = new CatalogIndexSnapshot(List.of(indexEntry(fixture)), List.of(), ModelScanReport.empty());
        var localResult = new RemoteCatalogActivation(() -> index, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("local")), fetcher, Runnable::run).activate(publication).join();
        assertEquals(Set.of(identity.modelId()), localResult.readyCatalog().byModelId().keySet());
        assertEquals(0, fetches.get());
    }

    @Test
    void sameModelLocalContainerSkipsNetworkAndPreservesBothIdentities() throws Exception {
        var localFile = repacked("same-model-local.mxc", (byte)'L');
        var sourceRoot = new CatalogRootIdentity(CatalogRootKind.CUSTOM, temp);
        var sourceKey =
            new ModelSourceKey(sourceRoot, new ModelPath("same-model-local.mxc"), ModelSourceKind.DIRECT_CONTAINER);
        var region = new CacheRegion(temp.resolve("local-cache"));
        var binding = ContentStorageBinding.local(region, ModelSourceDiscovery.observe(sourceKey, localFile));
        var rawEntry = indexEntry(localFile);
        var localEntry =
            new CatalogIndexEntry(rawEntry.identity(), rawEntry.location(), rawEntry.backingFile(), binding);
        assertEquals(identity.modelId(), localEntry.modelId());
        assertNotEquals(identity.containerId(), localEntry.containerId());
        var fetches = new AtomicInteger();
        var result = new RemoteCatalogActivation(
            () -> new CatalogIndexSnapshot(List.of(localEntry), List.of(), ModelScanReport.empty()),
            CatalogSnapshot.empty(), new RemoteModelStore(temp.resolve("same-model-local")),
            metadata(requestedIdentity -> {
                fetches.incrementAndGet();
                return CompletableFuture.failedFuture(AssetLoadException.access("local reuse must skip network"));
            }), Runnable::run).activate(new RemotePublicationSnapshot(List.of(new PublicationEntry(identity,
                new HierarchyPath("global:auth/published/path.mxc"), CatalogAccess.AUTHORIZED, false)), Set.of(),
                List.of(), Map.of()))
            .join();

        var ready = assertInstanceOf(ActivationSnapshot.Ready.class, result.entries().get(identity.modelId()));
        assertEquals(identity, ready.publication().identity());
        assertEquals(localEntry.identity(), ready.record().binding().content().representation().identity());
        assertSame(binding, ready.record().binding().content().storageBinding());
        assertEquals("global:auth/published/path.mxc", ready.record().location().value());
        assertEquals("global:auth/published/path.mxc", ready.record().entry().path().value());
        assertEquals(CatalogAccess.AUTHORIZED, ready.record().entry().access());
        assertEquals(com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry.Origin.CUSTOM,
            new ClientCatalogSnapshot(result.readyCatalog()).find(identity.modelId()).orElseThrow().origin());
        assertFalse(ready.publication().runtimeContentAllowed());
        assertEquals(0, fetches.get());
    }

    @Test
    void exactLocalPrecedesActiveLocalWithAnotherContainer() throws Exception {
        var repacked = ManagedContainer.openIndexed(indexEntry(repacked("active-local.mxc", (byte)'A')));
        var active = new CatalogSnapshot(Map.of(identity.modelId(), record(repacked, "active")), List.of(),
            ModelScanReport.empty());
        var exact = indexEntry(fixture);
        var result = new RemoteCatalogActivation(
            () -> new CatalogIndexSnapshot(List.of(exact), List.of(), ModelScanReport.empty()), active,
            new RemoteModelStore(temp.resolve("exact-before-active")), metadata(requestedIdentity -> {
                return CompletableFuture.failedFuture(AssetLoadException.access("exact local hit must skip network"));
            }), Runnable::run).activate(publication(identity.modelId(), identity.containerId(), "remote")).join();

        var ready = assertInstanceOf(ActivationSnapshot.Ready.class, result.entries().get(identity.modelId()));
        assertEquals(identity, ready.record().binding().content().representation().identity());
    }

    @Test
    void activeLocalPrecedesOtherNonExactIndexCandidates() throws Exception {
        var activeContent = ManagedContainer.openIndexed(indexEntry(repacked("active-first.mxc", (byte)'A')));
        var indexed = indexEntry(repacked("indexed-second.mxc", (byte)'B'));
        var active = new CatalogSnapshot(Map.of(identity.modelId(), record(activeContent, "active")), List.of(),
            ModelScanReport.empty());
        var result = new RemoteCatalogActivation(
            () -> new CatalogIndexSnapshot(List.of(indexed), List.of(), ModelScanReport.empty()), active,
            new RemoteModelStore(temp.resolve("active-before-index")), metadata(requestedIdentity -> {
                return CompletableFuture.failedFuture(AssetLoadException.access("active local hit must skip network"));
            }), Runnable::run).activate(publication(identity.modelId(), identity.containerId(), "remote")).join();

        var ready = assertInstanceOf(ActivationSnapshot.Ready.class, result.entries().get(identity.modelId()));
        assertSame(activeContent, ready.record().binding().content());
    }

    @Test
    void cacheHitAndColdServerMissBothBecomeReady() throws Exception {
        var publication = publication(identity.modelId(), identity.containerId(), "remote");
        var cachedStore = new RemoteModelStore(temp.resolve("cached"));
        try (var prefix = prefix()) {
            cachedStore.commitMetadataPrefix(identity, prefix);
        }
        var cached = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(), cachedStore,
            metadata(requestedIdentity -> {
                return CompletableFuture.failedFuture(AssetLoadException.access("cache hit must skip network"));
            }), Runnable::run).activate(publication).join();
        assertTrue(cached.failures().isEmpty());

        var fetches = new AtomicInteger();
        var coldStore = new RemoteModelStore(temp.resolve("cold"));
        var cold = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(), coldStore,
            metadata(requestedIdentity -> {
                fetches.incrementAndGet();
                return CompletableFuture.completedFuture(prefix());
            }), Runnable::run).activate(publication).join();
        assertTrue(cold.failures().isEmpty());
        assertEquals(1, fetches.get());
        assertTrue(coldStore.probeMetadata(identity).isPresent());
    }

    @Test
    void nonExactRemoteCacheIsNeverReusedByModelId() throws Exception {
        var repackedContent = ManagedContainer.openIndexed(indexEntry(repacked("server-exact.mxc", (byte)'S')));
        var requested = repackedContent.representation().identity();
        var store = new RemoteModelStore(temp.resolve("remote-non-exact"));
        try (var cachedPrefix = prefix()) {
            store.commitMetadataPrefix(identity, cachedPrefix);
        }
        var fetches = new AtomicInteger();
        var result = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(), store,
            metadata(ignored -> {
                fetches.incrementAndGet();
                return CompletableFuture.completedFuture(prefix(repackedContent));
            }), Runnable::run).activate(publication(requested.modelId(), requested.containerId(), "remote")).join();

        var ready = assertInstanceOf(ActivationSnapshot.Ready.class, result.entries().get(requested.modelId()));
        assertEquals(requested, ready.record().binding().content().representation().identity());
        assertEquals(1, fetches.get());
    }

    @Test
    void invalidLocalCandidateFallsThroughToTheNextStableCandidate() throws Exception {
        var broken = temp.resolve("broken.mxc");
        Files.copy(fixture, broken);
        var bytes = Files.readAllBytes(broken);
        bytes[bytes.length - 1] ^= 1;
        Files.write(broken, bytes);
        var valid = Files.copy(fixture, temp.resolve("valid-after-broken.mxc"));
        var index = new CatalogIndexSnapshot(List.of(indexEntry(broken), indexEntry(valid)), List.of(),
            ModelScanReport.empty());
        var fetches = new AtomicInteger();
        var result = new RemoteCatalogActivation(() -> index, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("fallback")), metadata(requestedIdentity -> {
                fetches.incrementAndGet();
                return CompletableFuture
                    .failedFuture(AssetLoadException.access("second local candidate must skip network"));
            }), Runnable::run).activate(publication(identity.modelId(), identity.containerId(), "remote")).join();

        assertTrue(result.failures().isEmpty());
        assertEquals(0, fetches.get());
    }

    @Test
    void localProbeReadsTheLatestIndexWhenActivationStarts() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var index = new AtomicReference<>(CatalogIndexSnapshot.empty());
        var fetches = new AtomicInteger();
        var owner = new RemoteCatalogActivation(index::get, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("latest-index")), metadata(requestedIdentity -> {
                fetches.incrementAndGet();
                return CompletableFuture.failedFuture(AssetLoadException.access("local hit must skip network"));
            }), queue::add);
        var result = owner.activate(publication(identity.modelId(), identity.containerId(), "remote"));

        index.set(new CatalogIndexSnapshot(List.of(indexEntry(fixture)), List.of(), ModelScanReport.empty()));
        queue.remove().run();

        assertTrue(result.join().failures().isEmpty());
        assertEquals(0, fetches.get());
        owner.close();
    }

    @Test
    void closeRejectsPendingCompletionAndFreshOwnerStartsClean() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var publication = publication(identity.modelId(), identity.containerId(), "remote");
        var staleOwner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("stale-owner")),
            metadata(requestedIdentity -> CompletableFuture.completedFuture(prefix())), queue::add);
        var stale = staleOwner.activate(publication);

        staleOwner.close();
        queue.remove().run();

        assertThrows(CompletionException.class, stale::join);
        var fresh = new RemoteCatalogActivation(
            () -> new CatalogIndexSnapshot(List.of(indexEntry(fixture)), List.of(), ModelScanReport.empty()),
            CatalogSnapshot.empty(), new RemoteModelStore(temp.resolve("fresh-owner")), metadata(requestedIdentity -> {
                return CompletableFuture.failedFuture(AssetLoadException.access("fresh local hit must skip network"));
            }), Runnable::run);
        assertTrue(fresh.activate(publication).join().failures().isEmpty());
        fresh.close();
    }

    @Test
    void closeDuringMetadataFetchCannotPublishIntoThePersistentStore() throws Exception {
        var root = temp.resolve("close-during-fetch");
        var store = new RemoteModelStore(root);
        var fetched = new CompletableFuture<BufferStorage>();
        var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(), store,
            metadata(requestedIdentity -> fetched), Runnable::run);
        var result = owner.activate(publication(identity.modelId(), identity.containerId(), "stale"));
        try {
            assertFalse(result.isDone());
            store.close();
            owner.close();
            assertTrue(fetched.isCancelled());
            assertThrows(CompletionException.class, result::join);
            try (var reopened = new RemoteModelStore(root)) {
                assertTrue(reopened.probeMetadata(identity).isEmpty());
            }
        } finally {
            owner.close();
        }
    }

    @Test
    void networkTerminalPrecedesTheSingleDependentWorkerAdmission() throws Exception {
        var workers = new ArrayDeque<Runnable>();
        var metadata = new CompletableFuture<BufferStorage>();
        var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("terminal-before-admission")), metadata(ignored -> metadata),
            workers::add);

        var result = owner.activate(publication(identity.modelId(), identity.containerId(), "remote"));
        assertEquals(1, workers.size());
        workers.remove().run();
        assertTrue(workers.isEmpty(), "pending network input must not consume model-worker admission");
        assertFalse(result.isDone());

        metadata.complete(prefix());
        assertEquals(1, workers.size(), "terminal metadata admits exactly one dependent worker task");
        workers.remove().run();

        assertTrue(result.join().failures().isEmpty());
        assertTrue(workers.isEmpty());
        owner.close();
    }

    @Test
    void metadataActionFailureStopsUnstartedChildrenAndFailsTheWholeParent() throws Exception {
        var first = new PublicationEntry(new ModelFileIdentity(hash(40), hash(50)),
            new HierarchyPath("local:custom/first"), CatalogAccess.PUBLIC, true);
        var second = new PublicationEntry(new ModelFileIdentity(hash(41), hash(51)),
            new HierarchyPath("local:custom/second"), CatalogAccess.PUBLIC, true);
        var publication = new RemotePublicationSnapshot(List.of(first, second), Set.of(), List.of(), Map.of());
        var entered = new CountDownLatch(1);
        var transfers = new ConcurrentHashMap<ModelFileIdentity, CompletableFuture<BufferStorage>>();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var result = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(),
                new RemoteModelStore(temp.resolve("failed")), metadata(requestedIdentity -> {
                    var transfer = new CompletableFuture<BufferStorage>();
                    transfers.put(requestedIdentity, transfer);
                    entered.countDown();
                    return transfer;
                }), workers).activate(publication);

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, transfers.size(), "framing children start sequentially under one metadata action");
            transfers.values()
                .forEach(transfer -> transfer.completeExceptionally(AssetLoadException.access("offline")));
            var snapshot = result.join();

            assertEquals(2, snapshot.failures().size());
            assertTrue(snapshot.readyCatalog().byModelId().isEmpty());
            assertTrue(snapshot.failures().stream()
                .allMatch(value -> value.failure().kind() == ActivationFailure.Kind.TRANSIENT_ACCESS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void replacementClosesOldTupleOwnerAndRejectsItsCompletion() throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var activeRecord = record(content, "active");
        var active = new CatalogSnapshot(Map.of(identity.modelId(), activeRecord), List.of(), ModelScanReport.empty());
        var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, active,
            new RemoteModelStore(temp.resolve("replace")), metadata(requestedIdentity -> {
                return CompletableFuture.failedFuture(AssetLoadException.access("active hit must skip network"));
            }), queue::add);
        var before = publication(identity.modelId(), identity.containerId(), "before");
        var after = publication(identity.modelId(), identity.containerId(), "after");

        var stale = owner.activate(before);
        var current = owner.activate(after);
        while (!queue.isEmpty()) {
            queue.remove().run();
        }

        assertThrows(CompletionException.class, stale::join);
        var ready = current.join();
        assertEquals(new HierarchyPath("local:custom/after"),
            ready.entries().get(identity.modelId()).publication().path());
        assertTrue(ready.failures().isEmpty());
        owner.close();
    }

    @Test
    void publicationSupersessionRestartsUnchangedPendingMetadataOwners() throws Exception {
        var unchanged =
            new PublicationEntry(identity, new HierarchyPath("local:custom/unchanged"), CatalogAccess.PUBLIC, true);
        var removed = new PublicationEntry(new ModelFileIdentity(hash(70), hash(71)),
            new HierarchyPath("local:custom/removed"), CatalogAccess.PUBLIC, true);
        var before = new RemotePublicationSnapshot(List.of(unchanged, removed), Set.of(), List.of(), Map.of());
        var after = new RemotePublicationSnapshot(List.of(unchanged), Set.of(), List.of(), Map.of());
        var firstAction = new CompletableFuture<Void>();
        var attempts = new AtomicInteger();
        RemoteMetadataFetcher fetcher = (identities, receiver) -> {
            if (attempts.incrementAndGet() == 1) {
                return firstAction;
            }
            assertEquals(List.of(identity), identities);
            try (var bytes = prefix()) {
                receiver.accept(identity, bytes);
                return CompletableFuture.completedFuture(null);
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        };
        var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("superseded")), fetcher, Runnable::run);

        var stale = owner.activate(before);
        var current = owner.activate(ActivationSnapshot.pending(after));

        assertTrue(firstAction.isCancelled());
        assertEquals(2, attempts.get());
        assertThrows(CompletionException.class, stale::join);
        assertTrue(current.join().failures().isEmpty());
        owner.close();
    }

    @Test
    void explicitTransientRetryCreatesANewTupleOwner() throws Exception {
        var attempts = new AtomicInteger();
        var publication = publication(identity.modelId(), identity.containerId(), "retry");
        var owner = new RemoteCatalogActivation(CatalogIndexSnapshot::empty, CatalogSnapshot.empty(),
            new RemoteModelStore(temp.resolve("retry")), metadata(requestedIdentity -> {
                if (attempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(AssetLoadException.access("offline"));
                }
                return CompletableFuture.completedFuture(prefix());
            }), Runnable::run);

        var failed = owner.activate(publication).join();
        assertEquals(1, failed.failures().size());
        owner.restart(Set.of(identity.modelId()));
        var ready = owner.activate(ActivationSnapshot.pending(publication)).join();

        assertTrue(ready.failures().isEmpty());
        assertEquals(2, attempts.get());
        owner.close();
    }

    private static BufferStorage prefix() {
        return prefix(content);
    }

    private static BufferStorage prefix(ManagedContainer source) {
        return source.representation().metadataPrefix().orElseThrow().copy();
    }

    private static RemoteMetadataFetcher
        metadata(Function<ModelFileIdentity, CompletableFuture<BufferStorage>> fetcher) {
        return (identities, receiver) -> {
            var result = new CompletableFuture<Void>();
            var current = new AtomicReference<CompletableFuture<BufferStorage>>();
            class Sequence {
                private int next;

                private void start() {
                    if (result.isDone())
                        return;
                    if (next == identities.size()) {
                        result.complete(null);
                        return;
                    }
                    var identity = identities.get(next++);
                    var transfer = fetcher.apply(identity);
                    current.set(transfer);
                    transfer.whenComplete((bytes, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                            return;
                        }
                        try (bytes) {
                            receiver.accept(identity, bytes);
                            start();
                        } catch (Exception rejected) {
                            result.completeExceptionally(rejected);
                        }
                    });
                }
            }
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    var transfer = current.get();
                    if (transfer != null)
                        transfer.cancel(false);
                }
            });
            new Sequence().start();
            return result;
        };
    }

    private static RemoteChunkFetcher chunks(ManagedContainer source, AtomicInteger fetches) {
        return (requestedIdentity, chunks, receiver) -> {
            fetches.incrementAndGet();
            try {
                for (var chunk : chunks) {
                    {
                        var bytes = source.chunks().readStoredVerified(chunk, BufferType.ARRAY);
                        receiver.accept(chunk, bytes);
                    }
                }
                return CompletableFuture.completedFuture(null);
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
        };
    }

    private static CatalogIndexEntry indexEntry(Path file) {
        final ModelFileIdentity fileIdentity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            fileIdentity = ModelFileIdentityReader.read(channel);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new CatalogIndexEntry(fileIdentity, new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath("local")),
            file);
    }

    private Path repacked(String name, byte summaryMarker) throws Exception {
        var target = Files.copy(fixture, temp.resolve(name));
        var bytes = Files.readAllBytes(target);
        var summaryOffset = AssetContainerConstant.HEAD.length;
        if (bytes[summaryOffset] == 0) {
            throw new AssertionError("Fixture must contain a non-empty container summary");
        }
        bytes[summaryOffset] = bytes[summaryOffset] == summaryMarker ? (byte)(summaryMarker ^ 1) : summaryMarker;
        final AssetContainerView view;
        try (var channel = FileChannel.open(fixture, StandardOpenOption.READ)) {
            view = AssetContainerReader.read(channel);
        }
        var verification =
            view.getChunkInfo(com.elfmcys.ysm.format.container.AssetContainerConstant.VERIFICATION_CHUNK_TYPE);
        var containerHash = Blake3.computeHash(ArrayBuffer.borrow(bytes, 0, verification.offset()));
        System.arraycopy(containerHash, 0, bytes, verification.offset(), containerHash.length);
        Files.write(target, bytes);
        return target;
    }

    private static CatalogRecord record(ManagedContainer content, String path) {
        return new CatalogRecord(new CatalogLocation(CatalogRootKind.CUSTOM, new ModelPath(path)),
            new CatalogContentBinding(content.modelId(), content));
    }

    private static RemotePublicationSnapshot publication(Hash256 modelId, Hash256 containerId, String path) {
        return new RemotePublicationSnapshot(List.of(new PublicationEntry(new ModelFileIdentity(modelId, containerId),
            new HierarchyPath("local:custom/" + path), CatalogAccess.PUBLIC, true)), Set.of(), List.of(), Map.of());
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte)marker;
        return new Hash256(bytes);
    }
}
