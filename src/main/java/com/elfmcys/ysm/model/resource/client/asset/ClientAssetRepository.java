package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.FileImageSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.proto.mixel.common.ImageProto;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Catalog-bound image access for local and active remote sessions. */
public final class ClientAssetRepository {
    private final ClientCatalogManager catalogs;
    private final ResidentImages images = new ResidentImages();
    private final Supplier<RemotePresentationFetcher> presentations;
    private final Function<ModelContent, ModelContent> exactContent;
    private final PreviewStorage previewStore;
    private final Executor workers;
    private final Function<ModelContent, CompletableFuture<ImageSource>> localPreview;
    private final Function<ModelContent, CompletableFuture<Optional<ImageSource>>> remotePreview;
    private final Supplier<ClientModelSession> sessions;
    private final java.util.function.BiFunction<ModelContent, com.elfmcys.ysm.buffer.UniBuffer,
        CompletableFuture<ImageSource>> rawPreview;

    public ClientAssetRepository(ClientCatalogManager catalogs) {
        this(catalogs, ClientSessionRuntime::presentationFetcher, Function.identity(), null, Runnable::run,
            ClientAssetRepository::missingLocalPreview, ClientAssetRepository::missingRemotePreview);
    }

    public ClientAssetRepository(ClientCatalogManager catalogs, RuntimeContentStore contentStore) {
        this(catalogs, ClientSessionRuntime::presentationFetcher,
            Objects.requireNonNull(contentStore, "contentStore")::exact, null, Runnable::run,
            ClientAssetRepository::missingLocalPreview, ClientAssetRepository::missingRemotePreview);
    }

    public ClientAssetRepository(ClientCatalogManager catalogs, RuntimeContentStore contentStore,
        PreviewStorage previewStore, Executor workers) {
        this(catalogs, ClientSessionRuntime::presentationFetcher,
            Objects.requireNonNull(contentStore, "contentStore")::exact,
            Objects.requireNonNull(previewStore, "previewStore"), Objects.requireNonNull(workers, "workers"),
            ClientAssetRepository::missingLocalPreview, ClientAssetRepository::missingRemotePreview);
    }

    public ClientAssetRepository(ClientCatalogManager catalogs, RuntimeContentStore contentStore,
        PreviewStorage previewStore, Executor workers,
        Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
        Function<ModelContent, CompletableFuture<Optional<ImageSource>>> remotePreview) {
        this(catalogs, ClientSessionRuntime::presentationFetcher,
            Objects.requireNonNull(contentStore, "contentStore")::exact,
            Objects.requireNonNull(previewStore, "previewStore"), Objects.requireNonNull(workers, "workers"),
            Objects.requireNonNull(localPreview, "localPreview"),
            Objects.requireNonNull(remotePreview, "remotePreview"));
    }

    public ClientAssetRepository(ClientCatalogManager catalogs, RuntimeContentStore contentStore,
        PreviewStorage previewStore, Executor workers,
        Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
        Function<ModelContent, CompletableFuture<Optional<ImageSource>>> remotePreview, java.util.function.BiFunction<
            ModelContent, com.elfmcys.ysm.buffer.UniBuffer, CompletableFuture<ImageSource>> rawPreview) {
        this(catalogs, ClientSessionRuntime::presentationFetcher,
            Objects.requireNonNull(contentStore, "contentStore")::exact, previewStore, workers, localPreview,
            remotePreview, () -> ClientSessionRuntime.currentModelSession().orElse(null),
            Objects.requireNonNull(rawPreview, "rawPreview"));
    }

    private static void release(ImageSource image) {
        if (image instanceof PreviewStore.EncodedPreview encoded)
            encoded.close();
    }

    private static void release(PreviewResult outcome) {
        if (outcome instanceof PreviewResult.Data data)
            release(data.image());
    }

    ClientAssetRepository(ClientCatalogManager catalogs, RemotePresentationFetcher presentations) {
        this(catalogs, () -> presentations, Function.identity(), null, Runnable::run,
            ClientAssetRepository::missingLocalPreview, ClientAssetRepository::missingRemotePreview);
    }

    ClientAssetRepository(ClientCatalogManager catalogs, RemotePresentationFetcher presentations,
        Function<ModelContent, ModelContent> exactContent) {
        this(catalogs, () -> presentations, exactContent, null, Runnable::run,
            ClientAssetRepository::missingLocalPreview, ClientAssetRepository::missingRemotePreview);
    }

    ClientAssetRepository(ClientCatalogManager catalogs, RemotePresentationFetcher presentations,
        Function<ModelContent, ModelContent> exactContent, PreviewStorage previewStore, Executor workers) {
        this(catalogs, () -> presentations, exactContent, previewStore, workers,
            ClientAssetRepository::missingLocalPreview, ClientAssetRepository::missingRemotePreview);
    }

    ClientAssetRepository(ClientCatalogManager catalogs, Supplier<RemotePresentationFetcher> presentations,
        Function<ModelContent, ModelContent> exactContent, PreviewStorage previewStore, Executor workers,
        Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
        Function<ModelContent, CompletableFuture<Optional<ImageSource>>> remotePreview) {
        this(catalogs, presentations, exactContent, previewStore, workers, localPreview, remotePreview,
            () -> ClientSessionRuntime.currentModelSession().orElse(null));
    }

    ClientAssetRepository(ClientCatalogManager catalogs, Supplier<RemotePresentationFetcher> presentations,
        Function<ModelContent, ModelContent> exactContent, PreviewStorage previewStore, Executor workers,
        Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
        Function<ModelContent, CompletableFuture<Optional<ImageSource>>> remotePreview,
        Supplier<ClientModelSession> sessions) {
        this(catalogs, presentations, exactContent, previewStore, workers, localPreview, remotePreview, sessions, null);
    }

    ClientAssetRepository(ClientCatalogManager catalogs, Supplier<RemotePresentationFetcher> presentations,
        Function<ModelContent, ModelContent> exactContent, PreviewStorage previewStore, Executor workers,
        Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
        Function<ModelContent, CompletableFuture<Optional<ImageSource>>> remotePreview,
        Supplier<ClientModelSession> sessions, java.util.function.BiFunction<ModelContent,
            com.elfmcys.ysm.buffer.UniBuffer, CompletableFuture<ImageSource>> rawPreview) {
        this.rawPreview = rawPreview;
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.presentations = Objects.requireNonNull(presentations, "presentations");
        this.exactContent = Objects.requireNonNull(exactContent, "exactContent");
        this.previewStore = previewStore;
        this.workers = Objects.requireNonNull(workers, "workers");
        this.localPreview = Objects.requireNonNull(localPreview, "localPreview");
        this.remotePreview = Objects.requireNonNull(remotePreview, "remotePreview");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public Batch openBatch() {
        return new Batch(catalogs.snapshot());
    }

    private static CompletableFuture<ImageSource> missingLocalPreview(ModelContent ignored) {
        return CompletableFuture.failedFuture(new IOException("Client preview generator is unavailable"));
    }

    private static CompletableFuture<Optional<ImageSource>> missingRemotePreview(ModelContent ignored) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private static CompletableFuture<ImageSource> contentPresentation(BooleanSupplier cancelled, ModelContent content,
        ModelAssetSelector selector) {
        try {
            return CompletableFuture
                .completedFuture(imageSource(cancelled, content.representation(), content.chunks(), selector));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static ImageSource imageSource(BooleanSupplier cancelled, ModelRepresentation representation,
        ChunkDataSource chunks, ModelAssetSelector selector) throws IOException {
        var view = representation.view();
        if (selector instanceof ModelAssetSelector.ModelPreview) {
            var source = view.thumbnailSource(cancelled, chunks);
            if (source == null) {
                throw new IOException("Model contains no preview image");
            }
            return source;
        }
        if (!(selector instanceof ModelAssetSelector.ModelPresentation presentation)) {
            throw new IOException("Selector does not identify a model image");
        }
        var info = view.getManifest().info();
        return switch (presentation.asset()) {
            case MODEL_ICON -> {
                var source = view.iconSource(cancelled, chunks);
                if (source == null) {
                    throw new IOException("Model contains no icon");
                }
                yield source;
            }
            case AUTHOR_AVATAR -> {
                var metadata = info.metadataUnsafe();
                if (metadata == null || metadata.authors().isEmpty()
                    || presentation.index() >= metadata.authors().size()
                    || !metadata.authors().get(presentation.index()).hasAvatar()) {
                    throw new IOException("Model contains no requested author avatar");
                }
                yield view.getFileView().imageBlobSource(cancelled, chunks,
                    metadata.authors().get(presentation.index()).avatarUnsafe());
            }
            case GUI_FOREGROUND -> {
                var settings = info.settings();
                if (!settings.hasGuiForeground()) {
                    throw new IOException("Model contains no GUI foreground");
                }
                yield view.getFileView().imageBlobSource(cancelled, chunks, settings.guiForegroundUnsafe());
            }
            case GUI_BACKGROUND -> {
                var settings = info.settings();
                if (!settings.hasGuiBackground()) {
                    throw new IOException("Model contains no GUI background");
                }
                yield view.getFileView().imageBlobSource(cancelled, chunks, settings.guiBackgroundUnsafe());
            }
        };
    }

    private static CompletableFuture<ImageSource> localPackCover(ModelPackDescriptor pack) {
        try {
            var location = com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(pack.hierarchy());
            var root = ModelCatalogSources.sources().stream().filter(value -> value.publicRoot() == location.root())
                .findFirst().orElseThrow(() -> new IOException("Pack root is unavailable"));
            var rootPath = root.path().toAbsolutePath().normalize();
            var file = rootPath.resolve(location.entry()).resolve("ysm-pack.png").normalize();
            if (!file.startsWith(rootPath)) {
                throw new IOException("Pack cover is unavailable");
            }
            return CompletableFuture
                .completedFuture(new FileImageSource(file, pack.coverSize(), pack.coverHash(), pack.coverFormat()));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // rootKind is endpoint-local provenance and may differ for identical published content.
    static boolean samePackCoverContent(ModelPackDescriptor candidate, ModelPackDescriptor requested) {
        return candidate.hierarchy().equals(requested.hierarchy())
            && Objects.equals(candidate.coverHash(), requested.coverHash())
            && candidate.coverSize() == requested.coverSize()
            && candidate.coverFormat().equalsIgnoreCase(requested.coverFormat());
    }

    public final class Batch implements AutoCloseable {
        private final ClientCatalogSnapshot snapshot;
        private final java.util.Map<Hash256, Object> imageMembers = new java.util.HashMap<>();
        private final java.util.Map<Hash256, Object> imageSessionMembers = new java.util.HashMap<>();
        private final RemotePresentationFetcher fetcher = presentations.get();
        private final ClientModelSession session = sessions.get();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ArrayList<PendingAsset> assets = new ArrayList<>();
        private CompletableFuture<Void> transfer;
        private boolean submitted;

        private Batch(ClientCatalogSnapshot snapshot) {
            this.snapshot = snapshot;
            snapshot.catalog().byModelId().forEach((id, record) -> {
                imageMembers.put(id, catalogs.imageMember(id));
                if (session != null && record.binding().content() instanceof RemoteModelContent content)
                    imageSessionMembers.put(id, session.imageMember(content.representation().identity()));
            });
        }

        public ResidentImages images() {
            return images;
        }

        public ImageRequest imageRequest(Hash256 modelId, ModelAssetSelector selector) {
            var content = snapshot.find(modelId).orElseThrow(() ->
                new IllegalArgumentException("Unknown model id: " + modelId)).content();
            var member = imageMembers.get(modelId);
            var connection = session;
            var remoteIdentity = content instanceof RemoteModelContent ? content.representation().identity() : null;
            var sessionMember = imageSessionMembers.get(modelId);
            BooleanSupplier allowed = () -> !closed.get() && sessions.get() == connection
                && (connection == null || connection.state() != ClientModelSession.State.CLOSED)
                && member != null && catalogs.imageMember(modelId) == member
                && catalogs.snapshot().find(modelId).map(entry -> entry.content() == content).orElse(false)
                && (connection == null || remoteIdentity == null
                    || connection.imageMemberCurrent(remoteIdentity, sessionMember));
            var preview = selector instanceof ModelAssetSelector.ModelPreview;
            Supplier<CompletableFuture<PreviewResult>> acquire = () -> preview ? preview(modelId)
                : presentation(modelId, ((ModelAssetSelector.ModelPresentation)selector).asset(),
                    ((ModelAssetSelector.ModelPresentation)selector).index()).thenApply(PreviewResult.Data::new);
            try {
                var chunk = presentationChunk(content, selector);
                if (chunk != null) {
                    Object proof = chunk.hash() == null ? content.representation().identity()
                        : java.util.HexFormat.of().formatHex(chunk.hash());
                    var image = presentationImage(content, selector);
                    var interpretation = image == null ? List.of() : List.of(image.format(), image.width(), image.height());
                    var identity = new ImageIdentity(modelId, selector, proof, interpretation,
                        chunk.encoding(), chunk.size(), chunk.decodeSize(), chunk.flags());
                    return new ImageRequest(ignored -> identity, allowed, ignored -> true, acquire);
                }
                if (!preview) {
                    var identity = new ImageIdentity(member, selector);
                    return new ImageRequest(ignored -> identity, allowed, ignored -> true, acquire);
                }
                var store = previewStore == null ? null : previewStore.forContent(content);
                var container = content.representation().containerId();
                Object version = store == null ? member : store.observe(container);
                var reload = catalogs.previewReload();
                var observation = connection == null ? null
                    : connection.observePreview(content.representation().identity()).orElse(null);
                var previewVersion = observation == null ? member : observation.contentVersion();
                Function<ImageSource, Object> imageVersion = source -> source instanceof PreviewStore.EncodedPreview data
                    && data.version() != null ? data.version() : version;
                Function<ImageSource, ImageIdentity> identity = source -> new ImageIdentity(modelId, selector,
                    container, imageVersion.apply(source), reload, previewVersion);
                return new ImageRequest(identity, allowed, source -> catalogs.previewReload() == reload
                    && (store == null || store.current(container, (PreviewStore.Version)imageVersion.apply(source)))
                    && (observation == null || connection.previewObservationCurrent(observation)), acquire);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }

        public ImageRequest coverRequest(ModelPackDescriptor pack) {
            var identity = new ImageIdentity(pack.hierarchy(), pack.coverHash() == null ? pack : pack.coverHash(),
                pack.coverFormat().toUpperCase(java.util.Locale.ROOT), pack.coverSize());
            return new ImageRequest(ignored -> identity,
                () -> !closed.get() && sessions.get() == session
                    && catalogs.snapshot().packs().stream().anyMatch(value -> samePackCoverContent(value, pack)),
                ignored -> true, () -> packCover(pack).thenApply(PreviewResult.Data::new));
        }

        public boolean previewAbsent(ModelFileIdentity identity) {
            return !closed.get() && session != null && session.previewAbsent(identity);
        }

        public synchronized CompletableFuture<PreviewResult> preview(Hash256 modelId) {
            checkRegistrationOpen();
            var entry = snapshot.find(modelId).orElse(null);
            if (entry == null) {
                return register(
                    () -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown model id: " + modelId)),
                    null, null);
            }
            return registerModel(entry.content(), ModelAssetSelector.preview(), true);
        }

        public synchronized CompletableFuture<ImageSource> packCover(ModelPackDescriptor pack) {
            checkRegistrationOpen();
            if (pack.coverHash() == null)
                return image(registerEager(
                    () -> CompletableFuture.failedFuture(new IOException("Model pack contains no cover")), null, null));
            var local = catalogs.localState().packs().stream().filter(value -> samePackCoverContent(value, pack))
                .findFirst().orElse(null);
            if (local != null)
                return image(registerEager(() -> localPackCover(local), null, null));
            var slot = assets.size();
            var member = new RemotePresentationFetcher.PackCover(slot, pack);
            return image(
                register(() -> CompletableFuture.failedFuture(new IOException("Remote pack cover produced no outcome")),
                    member, null));
        }

        public synchronized CompletableFuture<ImageSource> presentation(Hash256 modelId,
            ModelAssetSelector.PresentationAsset asset, int index) {
            checkRegistrationOpen();
            var entry = snapshot.find(modelId).orElse(null);
            if (entry == null)
                return image(register(
                    () -> CompletableFuture.failedFuture(new IllegalArgumentException("Unknown model id: " + modelId)),
                    null, null));
            return image(registerModel(entry.content(), ModelAssetSelector.presentation(asset, index), false));
        }

        private CompletableFuture<ImageSource> image(CompletableFuture<PreviewResult> result) {
            var image = new CompletableFuture<ImageSource>();
            result.whenComplete((value, failure) -> {
                // Dependent stages wrap cancellation and lose the page's cancellation terminal.
                if (result.isCancelled())
                    image.cancel(false);
                else if (failure != null)
                    image.completeExceptionally(failure);
                else if (value instanceof PreviewResult.Data data)
                    image.complete(data.image());
                else
                    image.completeExceptionally(new IllegalStateException("Presentation item returned no image"));
            });
            return image;
        }

        public synchronized void submit() {
            checkRegistrationOpen();
            submitted = true;
            var preparations = assets.stream().map(value -> value.preparation).filter(Objects::nonNull)
                .toArray(CompletableFuture[]::new);
            if (preparations.length != 0) {
                CompletableFuture.allOf(preparations).whenComplete((ignored, failure) -> beginTransfer());
                return;
            }
            beginTransfer();
        }

        private synchronized void beginTransfer() {
            if (closed.get())
                return;
            var remote = assets.stream().map(value -> value.member).filter(Objects::nonNull).filter(member -> {
                var asset = assets.get(member.slot());
                return !asset.started && !asset.result.isDone();
            }).toList();
            if (remote.isEmpty()) {
                publish();
                return;
            }
            var byMember = new IdentityHashMap<RemotePresentationFetcher.Member, PendingAsset>();
            remote.forEach(member -> byMember.put(member, assets.get(member.slot())));
            try {
                transfer = Objects.requireNonNull(fetcher.fetch(remote, (member, outcome) -> {
                    var asset = byMember.get(member);
                    if (asset == null)
                        throw new IOException("Presentation transfer returned an unknown page member");
                    if (outcome instanceof RemotePresentationFetcher.Absent absent) {
                        if (!(member instanceof RemotePresentationFetcher.Preview)) {
                            throw new IOException("Absent is only valid for a preview");
                        }
                        if (asset.previewPayload.embedded) {
                            throw new IOException("Embedded preview cannot be absent");
                        }
                        asset.prepared = new Resolved(PreviewResult.Absent.INSTANCE, null, absent.current());
                        return;
                    }
                    if (outcome instanceof RemotePresentationFetcher.Unavailable) {
                        asset.prepared =
                            new Resolved(null, new IOException("Remote presentation resource is unavailable"));
                        return;
                    }
                    var data = (RemotePresentationFetcher.Data)outcome;
                    var bytes = data.bytes();
                    if (asset.previewPayload != null) {
                        if (session == null || data.observation() == null) {
                            throw new IOException("Preview data has no session observation");
                        }
                        asset.previewPayload.accept(data);
                    } else if (asset.remoteContent != null) {
                        asset.remoteContent.publish(asset.chunk, bytes);
                    } else {
                        asset.prepared =
                            new Resolved(
                                new PreviewResult.Data(
                                    remotePackCover(((RemotePresentationFetcher.PackCover)member).pack(), bytes)),
                                null);
                    }
                }), "Presentation fetch returned no terminal owner");
            } catch (RuntimeException failure) {
                failAll(failure);
                return;
            }
            transfer.whenComplete((ignored, failure) -> {
                if (failure != null)
                    failAll(failure);
                else
                    publish();
            });
        }

        public synchronized boolean hasRemoteRequests() {
            checkRegistrationOpen();
            return assets.stream().anyMatch(asset -> asset.member != null && !asset.started && !asset.result.isDone());
        }

        private void checkRegistrationOpen() {
            if (submitted || closed.get()) {
                throw new IllegalStateException("Asset batch is already submitted or cancelled");
            }
        }

        private boolean cancelled() {
            return closed.get();
        }

        private CompletableFuture<PreviewResult> registerModel(ModelContent content, ModelAssetSelector selector,
            boolean preview) {
            var readable = exactContent.apply(content);
            if (preview) {
                if (content instanceof RemoteModelContent) {
                    return registerRemotePreview(content, readable);
                }
                return registerEager(() -> resolveLocalPreview(readable), null, null);
            }
            if (!(content instanceof RemoteModelContent remote)) {
                return registerEager(() -> contentPresentation(this::cancelled, readable, selector), null, null);
            }
            try {
                var chunk = presentationChunk(content, selector);
                if (chunk == null)
                    return registerEager(() -> CompletableFuture
                        .failedFuture(new IOException("Model contains no requested presentation")), null, null);
                if (remote.hasCachedImage(chunk))
                    return registerEager(() -> contentPresentation(this::cancelled, content, selector), null, null);
                var slot = assets.size();
                RemotePresentationFetcher.Member member =
                    new RemotePresentationFetcher.Icon(slot, remote.representation().identity(), chunk);
                return register(() -> contentPresentation(this::cancelled, content, selector), member,
                    new RemoteBacking(remote, chunk));
            } catch (IOException | RuntimeException failure) {
                return registerEager(() -> CompletableFuture.failedFuture(failure), null, null);
            }
        }

        private CompletableFuture<ImageSource> resolveLocalPreview(ModelContent content) {
            try {
                return Objects.requireNonNull(localPreview.apply(content),
                    "Local preview resolver returned no operation");
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private CompletableFuture<Optional<ImageSource>> probeRemotePreview(ModelContent content) {
            try {
                return Objects.requireNonNull(remotePreview.apply(content),
                    "Remote preview probe returned no operation");
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private CompletableFuture<PreviewResult> registerRemotePreview(ModelContent requested, ModelContent readable) {
            var identity = requested.representation().identity();
            var embedded = requested.representation().view().getThumbnailPreviewSource() != PreviewSource.UNSPECIFIED;
            var payload = new PreviewPayload(identity, embedded, readable);
            var member = new RemotePresentationFetcher.Preview(assets.size(), identity);
            var pending = new PendingAsset(null, member, null, null, payload);
            assets.add(pending);
            var probe = probeRemotePreview(readable);
            pending.preparation = probe;
            probe.whenComplete((source, failure) -> {
                synchronized (Batch.this) {
                    if (closed.get()) {
                        if (source != null)
                            source.ifPresent(ClientAssetRepository::release);
                        return;
                    }
                    if (failure != null) {
                        pending.started = true;
                        pending.result.completeExceptionally(failure);
                    } else if (source.isPresent()) {
                        pending.started = true;
                        if (!pending.result.complete(new PreviewResult.Data(source.orElseThrow())))
                            release(source.orElseThrow());
                    } else if (session != null) {
                        synchronized (session) {
                            if (session.previewAbsent(identity)) {
                                pending.started = true;
                                pending.result.complete(PreviewResult.Absent.INSTANCE);
                            }
                        }
                    }
                }
            });
            return pending.result;
        }

        private CompletableFuture<Resolved> decodeRemotePreview(PendingAsset asset) {
            var payload = asset.previewPayload;
            var data = payload.take();
            if (data == null)
                return CompletableFuture.failedFuture(new IOException("Remote preview produced no outcome"));
            CompletableFuture<ImageSource> decoded;
            var bytes = data.bytes();
            try {
                if (rawPreview != null) {
                    decoded = rawPreview.apply(payload.content, bytes);
                } else {
                    // Standalone repository fixtures have no preview operation owner.
                    var fixed = bytes.share();
                    decoded = CompletableFuture.supplyAsync(() -> {
                        try {
                            return previewStore == null ? PreviewStore.decode(fixed) : previewStore
                                .forContent(payload.content).accept(payload.identity.containerId(), fixed);
                        } catch (IOException failure) {
                            throw new CompletionException(failure);
                        }
                    }, workers);
                }
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            var ownedDecode = decoded;
            synchronized (this) {
                asset.preparation = ownedDecode;
                if (closed.get())
                    ownedDecode.cancel(false);
            }
            return decoded.handle((image, error) -> {
                var failure = error instanceof CompletionException ? error.getCause() : error;
                if (closed.get()) {
                    release(image);
                    return new Resolved(null, new java.util.concurrent.CancellationException());
                }
                if (!payload.embedded && failure instanceof AssetLoadException invalid
                    && invalid.reason() == AssetLoadException.Reason.CONTENT) {
                    session.completePreviewAbsent(data.observation());
                    return new Resolved(PreviewResult.Absent.INSTANCE, null,
                        () -> session.previewObservationCurrent(data.observation()));
                }
                return new Resolved(image == null ? null : new PreviewResult.Data(image), failure);
            });
        }

        private CompletableFuture<PreviewResult> registerEager(Supplier<CompletableFuture<ImageSource>> loader,
            RemotePresentationFetcher.Member member, RemoteBacking backing) {
            var result = register(loader, member, backing);
            var pending = assets.get(assets.size() - 1);
            pending.started = true;
            try {
                pending.preparation = loader.get();
                pending.preparation.whenComplete((value, failure) -> {
                    var source = (ImageSource)value;
                    if (closed.get()) {
                        release(source);
                        return;
                    }
                    if (failure == null) {
                        if (!pending.result
                            .complete(source == null ? PreviewResult.Absent.INSTANCE : new PreviewResult.Data(source)))
                            release(source);
                    } else {
                        pending.result.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException failure) {
                pending.result.completeExceptionally(failure);
            }
            return result;
        }

        private CompletableFuture<PreviewResult> register(Supplier<CompletableFuture<ImageSource>> loader,
            RemotePresentationFetcher.Member member, RemoteBacking backing) {
            var pending = new PendingAsset(loader, member, backing == null ? null : backing.content,
                backing == null ? null : backing.chunk, null);
            assets.add(pending);
            return pending.result;
        }

        private void publish() {
            final List<PendingAsset> current;
            synchronized (this) {
                if (closed.get())
                    return;
                current = List.copyOf(assets);
            }
            var pending = current.stream().filter(asset -> !asset.started).toList();
            var resolutions = pending.stream().map(asset -> {
                asset.started = true;
                if (asset.prepared != null) {
                    return CompletableFuture.completedFuture(asset.prepared);
                }
                try {
                    if (asset.previewPayload != null) {
                        return decodeRemotePreview(asset).exceptionally(failure -> new Resolved(null, failure));
                    }
                    return asset.loader.get().handle((source,
                        failure) -> new Resolved(source == null ? null : new PreviewResult.Data(source), failure));
                } catch (RuntimeException failure) {
                    return CompletableFuture.completedFuture(new Resolved(null, failure));
                }
            }).toList();
            for (var index = 0; index < pending.size(); index++) {
                var asset = pending.get(index);
                resolutions.get(index).whenComplete((resolved, failure) -> {
                    synchronized (Batch.this) {
                        if (closed.get()) {
                            release(resolved == null ? null : resolved.outcome);
                            return;
                        }
                        var outcome = failure == null ? resolved : new Resolved(null, failure);
                        if (asset.previewPayload != null && session != null) {
                            synchronized (session) {
                                publish(asset, outcome);
                            }
                        } else {
                            publish(asset, outcome);
                        }
                    }
                });
            }
        }

        private void publish(PendingAsset asset, Resolved resolved) {
            if (!resolved.current.getAsBoolean()) {
                release(resolved.outcome);
                asset.result.cancel(false);
            } else if (resolved.failure == null) {
                if (!asset.result.complete(resolved.outcome))
                    release(resolved.outcome);
            } else {
                asset.result.completeExceptionally(resolved.failure);
            }
        }

        private synchronized void failAll(Throwable failure) {
            if (closed.get())
                return;
            assets.stream().map(asset -> asset.previewPayload).filter(Objects::nonNull).forEach(PreviewPayload::close);
            assets.forEach(asset -> asset.result.completeExceptionally(failure));
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Throwable failure = null;
            if (transfer != null)
                failure = cleanup(failure, () -> transfer.cancel(false));
            for (var asset : assets) {
                if (asset.previewPayload != null)
                    failure = cleanup(failure, asset.previewPayload::close);
                if (asset.preparation != null)
                    failure = cleanup(failure, () -> asset.preparation.cancel(false));
                if (!asset.result.isCompletedExceptionally())
                    failure = cleanup(failure, () -> release(asset.result.getNow(null)));
                if (asset.prepared != null)
                    failure = cleanup(failure, () -> release(asset.prepared.outcome));
                failure = cleanup(failure, () -> asset.result.cancel(false));
            }
            assets.clear();
            if (failure instanceof Error error)
                throw error;
            if (failure instanceof RuntimeException error)
                throw error;
        }

        private final class PendingAsset {
            private final Supplier<CompletableFuture<ImageSource>> loader;
            private final RemotePresentationFetcher.Member member;
            private final RemoteModelContent remoteContent;
            private final AssetContainerView.ChunkInfo chunk;
            private final PreviewPayload previewPayload;
            private final CompletableFuture<PreviewResult> result = new CompletableFuture<>();
            private CompletableFuture<?> preparation;
            private volatile Resolved prepared;
            private volatile boolean started;

            private PendingAsset(Supplier<CompletableFuture<ImageSource>> loader,
                RemotePresentationFetcher.Member member, RemoteModelContent remoteContent,
                AssetContainerView.ChunkInfo chunk, PreviewPayload previewPayload) {
                this.loader = loader;
                this.member = member;
                this.remoteContent = remoteContent;
                this.chunk = chunk;
                this.previewPayload = previewPayload;
            }
        }
    }

    private static final class PreviewPayload {
        private final ModelFileIdentity identity;
        private final boolean embedded;
        private final ModelContent content;
        private RemotePresentationFetcher.Data data;
        private boolean closed;

        private PreviewPayload(ModelFileIdentity identity, boolean embedded, ModelContent content) {
            this.identity = identity;
            this.embedded = embedded;
            this.content = content;
        }

        private synchronized void accept(RemotePresentationFetcher.Data source) throws IOException {
            if (closed)
                return;
            if (data != null) {
                throw new IOException("Remote preview produced more than one payload");
            }
            data = new RemotePresentationFetcher.Data(source.bytes().share(), source.observation());
        }

        private synchronized RemotePresentationFetcher.Data take() {
            var result = data;
            data = null;
            return result;
        }

        private synchronized void close() {
            closed = true;
            if (data != null) {
                data = null;
            }
        }
    }

    private static Throwable cleanup(Throwable primary, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (primary == null)
                return failure;
            if (primary != failure)
                primary.addSuppressed(failure);
        }
        return primary;
    }

    private static AssetContainerView.ChunkInfo presentationChunk(ModelContent content, ModelAssetSelector selector)
        throws IOException {
        var view = content.representation().view();
        if (selector instanceof ModelAssetSelector.ModelPreview) {
            return view.getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        }
        var presentation = (ModelAssetSelector.ModelPresentation)selector;
        if (presentation.asset() == ModelAssetSelector.PresentationAsset.MODEL_ICON) {
            return view.getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_ICON_CHUNK_NAME);
        }
        var image = presentationImage(content, selector);
        return image == null ? null
            : view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + image.blobId());
    }

    private static ImageProto presentationImage(ModelContent content, ModelAssetSelector selector) {
        if (!(selector instanceof ModelAssetSelector.ModelPresentation presentation)
            || presentation.asset() == ModelAssetSelector.PresentationAsset.MODEL_ICON)
            return null;
        var info = content.representation().view().getManifest().info();
        ImageProto image;
        if (presentation.asset() == ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR) {
            var metadata = info.metadataUnsafe();
            if (metadata == null || metadata.authors().isEmpty() || presentation.index() >= metadata.authors().size()
                || !metadata.authors().get(presentation.index()).hasAvatar()) {
                return null;
            }
            image = metadata.authors().get(presentation.index()).avatarUnsafe();
        } else if (presentation.asset() == ModelAssetSelector.PresentationAsset.GUI_FOREGROUND) {
            var settings = info.settings();
            if (!settings.hasGuiForeground())
                return null;
            image = settings.guiForegroundUnsafe();
        } else {
            var settings = info.settings();
            if (!settings.hasGuiBackground())
                return null;
            image = settings.guiBackgroundUnsafe();
        }
        return image;
    }

    private static ImageSource remotePackCover(ModelPackDescriptor pack, UniBuffer bytes) throws IOException {
        if (bytes.size() != pack.coverSize() || !ModelHashing.blake3(bytes).equals(pack.coverHash())) {
            throw new IOException("Remote pack cover content mismatch");
        }
        var stored = bytes.share();
        try (var image = com.elfmcys.ysm.natives.image.Image.probe(stored)) {
            if (!image.format().name().equalsIgnoreCase(pack.coverFormat())) {
                throw new IOException("Remote pack cover format mismatch");
            }
        }
        return () -> com.elfmcys.ysm.natives.image.Image.probe(stored);
    }

    private record RemoteBacking(RemoteModelContent content, AssetContainerView.ChunkInfo chunk) {
    }

    private record Resolved(PreviewResult outcome, Throwable failure, BooleanSupplier current) {
        private Resolved(PreviewResult outcome, Throwable failure) {
            this(outcome, failure, () -> true);
        }
    }
}
