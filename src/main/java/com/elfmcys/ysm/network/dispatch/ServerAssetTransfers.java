package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.model.storage.PreviewStorage;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.AssetRef;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.CatalogRoot;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.format.schema.model.RenderTargetChunkPlan;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.buffer.UniBufferIO;
import com.elfmcys.ysm.proto.network.MissingPreviewQueryProto;
import com.elfmcys.ysm.proto.network.MissingPreviewEntryProto;
import com.elfmcys.ysm.proto.network.PreviewUploadRequestProto;
import com.elfmcys.ysm.proto.network.PreviewUploadFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.PreviewRepairResultProto;
import com.elfmcys.ysm.proto.network.PreviewRepairStatus;
import com.elfmcys.ysm.proto.network.ModelChunkPurpose;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.server.ServerResourceRuntime;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.IconMemberProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PresentationDelivery;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewMemberProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import us.hebi.quickbuf.ProtoMessage;

/** Owns typed server admission and accepted resource transfers for one exact connection. */
public final class ServerAssetTransfers implements AutoCloseable {
    private static final int MAX_METADATA_MEMBERS = 16_384;
    private static final int MAX_ACTION_MEMBERS = 256;
    private static final long MAX_METADATA_BYTES = 128L * 1024 * 1024;
    private static final long MAX_CHUNK_STORED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_CHUNK_DECODE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_PRESENTATION_STORED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_PRESENTATION_DECODE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ACTIVE_TRANSFERS = 256;
    private static final int OWNER_EVENTS_PER_TICK = 1_024;

    private final ServerModelSession owner;
    private final ResourceDispatchWorker dispatch;
    private final TransportPort transport;
    private final BooleanSupplier restrictedAuth;
    private final Consumer<ResourceTransferFailureProto> failureSink;
    private final ServerResourceRuntime serverResources;
    private final RootResolver roots;
    private final PreviewStorage previews;
    private final Map<Long, TransferLease> active = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<OwnerEvent> ownerEvents = new ConcurrentLinkedQueue<>();
    private Management management;
    private long highWater;
    private boolean closed;

    public ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch, TransportPort transport,
        BooleanSupplier restrictedAuth, Consumer<ResourceTransferFailureProto> failureSink,
        ServerResourceRuntime serverResources) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverResources,
            ServerAssetTransfers::catalogRoot, null);
    }

    public ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch, TransportPort transport,
        BooleanSupplier restrictedAuth, Consumer<ResourceTransferFailureProto> failureSink,
        ServerResourceRuntime serverResources, PreviewStore previews) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverResources,
            ServerAssetTransfers::catalogRoot, Objects.requireNonNull(previews, "previews"));
    }

    public ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch, TransportPort transport,
        BooleanSupplier restrictedAuth, Consumer<ResourceTransferFailureProto> failureSink,
        ServerResourceRuntime serverResources, PreviewStorage previews, Management management) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverResources,
            ServerAssetTransfers::catalogRoot, Objects.requireNonNull(previews, "previews"));
        this.management = Objects.requireNonNull(management, "management");
    }

    public record Management(ServerPreviewWork work, BooleanSupplier authorized, Consumer<ProtoMessage<?>> control,
        Consumer<ModelFileIdentity> committed) {
        public Management {
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(authorized, "authorized");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(committed, "committed");
        }
    }

    ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch, TransportPort transport,
        BooleanSupplier restrictedAuth, Consumer<ResourceTransferFailureProto> failureSink,
        ServerResourceRuntime serverResources, RootResolver roots) {
        this(owner, dispatch, transport, restrictedAuth, failureSink, serverResources, roots, null);
    }

    ServerAssetTransfers(ServerModelSession owner, ResourceDispatchWorker dispatch, TransportPort transport,
        BooleanSupplier restrictedAuth, Consumer<ResourceTransferFailureProto> failureSink,
        ServerResourceRuntime serverResources, RootResolver roots, PreviewStorage previews) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.restrictedAuth = Objects.requireNonNull(restrictedAuth, "restrictedAuth");
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
        this.serverResources = Objects.requireNonNull(serverResources, "serverResources");
        this.roots = Objects.requireNonNull(roots, "roots");
        this.previews = previews;
    }

    /** Returns false only for an intrinsic request violation that must retire the session. */
    public boolean accept(MetadataPrefixRequestProto request) {
        var decoded = decodeMetadata(request);
        if (decoded == null) {
            return false;
        }
        var begin = begin(decoded.transferId);
        if (begin == Begin.BUSY) {
            fail(decoded.transferId, busy());
            return true;
        }
        if (begin != Begin.ACCEPTED) {
            return begin == Begin.CLOSED;
        }
        if (!readyForDistribution()) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }

        var plans = new ArrayList<MetadataPlan>(decoded.containerIds.size());
        long totalBytes = 0;
        try {
            for (var containerId : decoded.containerIds) {
                var record = owner.findPublished(containerId).orElse(null);
                if (record == null) {
                    fail(decoded.transferId, notFound());
                    return true;
                }
                var representation = record.binding().content().representation();
                var size = representation.metadataPrefixSize();
                if (size <= 0) {
                    fail(decoded.transferId, unavailable());
                    return true;
                }
                totalBytes = Math.addExact(totalBytes, size);
                if (totalBytes > MAX_METADATA_BYTES) {
                    fail(decoded.transferId, itemTooLarge());
                    return true;
                }
                plans.add(new MetadataPlan(containerId, representation));
            }
        } catch (ArithmeticException tooLarge) {
            fail(decoded.transferId, itemTooLarge());
            return true;
        } catch (RuntimeException unavailable) {
            fail(decoded.transferId, unavailable());
            return true;
        }

        var lease = register(decoded.transferId);
        if (lease == null) {
            if (!isClosed()) {
                fail(decoded.transferId, busy());
            }
            return true;
        }
        var sources = new ArrayList<MetadataPrefixPacket.Source>(plans.size());
        var owned = new ArrayList<UniBuffer>(plans.size());
        try {
            for (var plan : plans) {
                var prefix = plan.representation.metadataPrefix().orElseThrow();
                owned.add(prefix);
                sources.add(new MetadataPrefixPacket.Source(plan.containerId.bytes(), prefix));
            }
            var packet =
                new MetadataPrefixPacket(decoded.transferId, sources, lease::packetClosed, lease::productionFailed);
            if (submit(lease, List.of(packet)) == SubmitResult.REJECTED) {
                fail(decoded.transferId, busy());
            }
            return true;
        } catch (RuntimeException unavailable) {
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(decoded.transferId, unavailable());
            }
            return true;
        } finally {
            closeBuffers(owned);
        }
    }

    /** Returns false only for an intrinsic request violation that must retire the session. */
    public boolean accept(ModelChunkRequestProto request) {
        var decoded = decodeModel(request);
        if (decoded == null) {
            return false;
        }
        if (decoded.purpose == ModelChunkPurpose.GEN_PREVIEW_MAIN) {
            return acceptManagementChunks(decoded);
        }
        var begin = begin(decoded.transferId);
        if (begin == Begin.BUSY) {
            fail(decoded.transferId, busy());
            return true;
        }
        if (begin != Begin.ACCEPTED) {
            return begin == Begin.CLOSED;
        }
        if (!readyForDistribution()) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }

        var restricted = restrictedAuth.getAsBoolean();
        var record = owner.findModel(decoded.modelId).orElse(null);
        if (record == null) {
            fail(decoded.transferId, restricted ? unauthorized() : notFound());
            return true;
        }
        if (!owner.canRequest(decoded.modelId, AssetRef.Kind.CHUNK, restricted)) {
            fail(decoded.transferId, unauthorized());
            return true;
        }
        var content = managed(record);
        if (content == null || !content.representation().containerId().equals(decoded.containerId)) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }
        var assetView = content.modelFile().getFileView().getAssetView();
        var plans = new ArrayList<ChunkPlan>(decoded.chunks.size());
        try {
            for (var requested : decoded.chunks) {
                var chunk = assetView.getChunkInfo(requested.name);
                if (!matches(requested, chunk)) {
                    fail(decoded.transferId, invalidRequest());
                    return true;
                }
                plans.add(new ChunkPlan(content, chunk,
                    (offset, last) -> ChunkFragmentProto.newBuilder().setDataTransferId(decoded.transferId)
                        .setName(requested.name).setOffset(offset).setFinalFragment(last).build()));
            }
            var lease = register(decoded.transferId);
            if (lease == null) {
                if (!isClosed()) {
                    fail(decoded.transferId, busy());
                }
                return true;
            }
            acquireChunks(lease, plans);
            return true;
        } catch (RuntimeException failure) {
            fail(decoded.transferId, unavailable());
            return true;
        }
    }

    /** Returns false only for an intrinsic request violation that must retire the session. */
    public boolean accept(PresentationPageRequestProto request) {
        var decoded = decodePresentation(request);
        if (decoded == null) {
            return false;
        }
        var begin = begin(decoded.transferId);
        if (begin == Begin.BUSY) {
            fail(decoded.transferId, busy());
            return true;
        }
        if (begin != Begin.ACCEPTED) {
            return begin == Begin.CLOSED;
        }
        if (!readyForDistribution()) {
            fail(decoded.transferId, invalidRequest());
            return true;
        }

        var plans = new ArrayList<PresentationPlan>(decoded.members.size());
        var restricted = restrictedAuth.getAsBoolean();
        try {
            for (var member : decoded.members) {
                var plan = presentationPlan(member, restricted);
                if (plan == null) {
                    fail(decoded.transferId, member.failure);
                    return true;
                }
                plans.add(plan);
            }
        } catch (RuntimeException failure) {
            fail(decoded.transferId, unavailable());
            return true;
        }

        final long knownBytes;
        try {
            knownBytes = plans.stream().mapToLong(plan -> plan.chunk != null ? plan.chunk.size()
                : plan.coverRootKind != null ? plan.member.storedSize : 0L).reduce(0L, Math::addExact);
        } catch (ArithmeticException tooLarge) {
            fail(decoded.transferId, itemTooLarge());
            return true;
        }
        if (knownBytes > MAX_PRESENTATION_STORED_BYTES) {
            fail(decoded.transferId, itemTooLarge());
            return true;
        }

        var lease = register(decoded.transferId);
        if (lease == null) {
            if (!isClosed()) {
                fail(decoded.transferId, busy());
            }
            return true;
        }
        if (plans.stream().anyMatch(plan -> plan.chunk != null || plan.standalonePreview)) {
            acquirePresentation(lease, plans, knownBytes);
            return true;
        }

        var packets = new ArrayList<CancellablePacket>(plans.size());
        try {
            for (var plan : plans) {
                var packet = presentationPacket(decoded.transferId, plan, lease);
                if (packet == null) {
                    closePackets(packets);
                    if (retireAcquiring(lease, LeaseState.FAILED)) {
                        fail(decoded.transferId, plan.member.failure);
                    }
                    return true;
                }
                packets.add(packet);
            }
            if (submit(lease, packets) == SubmitResult.REJECTED) {
                fail(decoded.transferId, busy());
            }
            return true;
        } catch (RuntimeException failure) {
            closePackets(packets);
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(decoded.transferId, unavailable());
            }
            return true;
        }
    }

    public boolean accept(MissingPreviewQueryProto request) {
        if (request.dataTransferId() == 0 || !fitsFrame(request))
            return false;
        if (duplicateManagement(request.dataTransferId(), ManagedKind.QUERY, null, 0))
            return true;
        var lease = beginManagement(request.dataTransferId(), ManagedKind.QUERY, null, 0);
        if (lease == null)
            return true;
        final List<CatalogRecord> records;
        try {
            records = owner.authority().catalog().byModelId().values().stream()
                .sorted(java.util.Comparator.comparing(record -> record.binding().content().representation().identity(),
                    java.util.Comparator.comparing(ModelFileIdentity::modelId)
                        .thenComparing(ModelFileIdentity::containerId)))
                .toList();
            if (records.size() > MAX_METADATA_MEMBERS)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            managementFailure(lease, unavailable());
            return true;
        }
        executeManagement(lease, () -> {
            var entries = new ArrayList<MissingPreviewEntryProto>();
            for (var record : records) {
                if (!managementCurrent(lease))
                    return;
                var identity = record.binding().content().representation().identity();
                var entry =
                    MissingPreviewEntryProto.newBuilder().setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
                        .setContainerId(ByteBuffer.wrap(identity.containerId().bytes()))
                        .setHierarchyPath(record.location().value());
                try {
                    if (hasPreview(record))
                        continue;
                } catch (IOException | RuntimeException failure) {
                    entry.setProbeFailure(unavailable());
                }
                entries.add(entry.build());
            }
            enqueueOwnerEvent(new ManagementProbed(lease, List.copyOf(entries), false, null));
        });
        return true;
    }

    public boolean accept(PreviewUploadRequestProto request) {
        if (request.dataTransferId() == 0 || request.modelId().remaining() != Hash256.SIZE
            || request.containerId().remaining() != Hash256.SIZE || request.encodedSize() <= 0
            || request.encodedSize() > PreviewStore.MAX_STORED_BYTES || !fitsFrame(request))
            return false;
        var identity = new ModelFileIdentity(hash(request.modelId()), hash(request.containerId()));
        if (duplicateManagement(request.dataTransferId(), ManagedKind.UPLOAD, identity, request.encodedSize()))
            return true;
        var lease = beginManagement(request.dataTransferId(), ManagedKind.UPLOAD, identity, request.encodedSize());
        if (lease == null)
            return true;
        probeManagement(lease);
        return true;
    }

    private boolean acceptManagementChunks(ModelRequest request) {
        var identity = new ModelFileIdentity(request.modelId, request.containerId);
        if (duplicateManagement(request.transferId, ManagedKind.CHUNKS, identity, 0)) {
            synchronized (this) {
                var previous = active.get(request.transferId);
                if (previous != null && !request.equals(previous.managedRequest.chunks)) {
                    managementFailure(previous, invalidRequest());
                }
            }
            return true;
        }
        var lease = beginManagement(request.transferId, ManagedKind.CHUNKS, identity, 0);
        if (lease == null)
            return true;
        lease.managedRequest.chunks = request;
        probeManagement(lease);
        return true;
    }

    private synchronized boolean duplicateManagement(long id, ManagedKind kind, ModelFileIdentity identity, int size) {
        if (Long.compareUnsigned(id, highWater) > 0)
            return false;
        var lease = active.get(id);
        if (lease != null) {
            var managed = lease.managedRequest;
            if (managed == null || managed.kind != kind || !Objects.equals(managed.identity, identity)
                || managed.encodedSize != size) {
                managementFailure(lease, invalidRequest());
            }
        }
        return true;
    }

    private TransferLease beginManagement(long id, ManagedKind kind, ModelFileIdentity identity, int size) {
        var started = begin(id);
        if (started != Begin.ACCEPTED) {
            if (started == Begin.BUSY)
                fail(id, busy());
            return null;
        }
        if (!readyForDistribution() || management == null || !management.authorized.getAsBoolean()) {
            fail(id, unauthorized());
            return null;
        }
        CatalogRecord record = null;
        ServerModelSession.Effect effect = null;
        if (identity != null) {
            record = owner.findModel(identity.modelId()).orElse(null);
            if (record == null) {
                repairResult(id, PreviewRepairStatus.NOT_FOUND);
                return null;
            }
            if (!record.binding().content().representation().identity().equals(identity)) {
                repairResult(id, PreviewRepairStatus.STALE);
                return null;
            }
            effect = owner.effect(identity).orElse(null);
            if (effect == null) {
                repairResult(id, PreviewRepairStatus.STALE);
                return null;
            }
        }
        synchronized (this) {
            if (active.values().stream().anyMatch(value -> value.managedRequest != null)) {
                fail(id, busy());
                return null;
            }
            var budget = management.work.acquire(kind == ManagedKind.QUERY, size);
            if (budget == null) {
                fail(id, busy());
                return null;
            }
            var lease = register(id);
            if (lease == null) {
                budget.close();
                fail(id, busy());
                return null;
            }
            lease.managedRequest = new ManagedRequest(kind, identity, size, record, effect, budget);
            return lease;
        }
    }

    private void probeManagement(TransferLease lease) {
        executeManagement(lease, () -> {
            try {
                var request = lease.managedRequest;
                if (request.kind == ManagedKind.CHUNKS) {
                    var allowed = RenderTargetChunkPlan.chunks(request.record.binding().content().modelFile(),
                        RenderTargetIds.PLAYER, "");
                    for (var chunk : request.chunks.chunks) {
                        if (allowed.stream().noneMatch(candidate -> matches(chunk, candidate))) {
                            enqueueOwnerEvent(new ManagementProbed(lease, List.of(), false,
                                new IllegalArgumentException("Non-primary target chunk")));
                            return;
                        }
                    }
                }
                var available = hasPreview(request.record);
                enqueueOwnerEvent(new ManagementProbed(lease, List.of(), available, null));
            } catch (IOException | RuntimeException failure) {
                enqueueOwnerEvent(new ManagementProbed(lease, List.of(), false, failure));
            }
        });
    }

    private boolean hasPreview(CatalogRecord record) throws IOException {
        var content = record.binding().content();
        var view = content.modelFile();
        if (view.getThumbnailPreviewSource() != com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED) {
            var chunk = view.getFileView().getAssetView().getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            var source = view.thumbnailSource(() -> false, content.chunks());
            if (chunk == null || source == null)
                throw new IOException("Declared preview is unavailable");
            PreviewStore.validate(source, chunk.size());
            return true;
        }
        var cached = previews.forContent(content).probe(content.representation().containerId());
        if (cached.isEmpty())
            return false;
        cached.get().close();
        return true;
    }

    private void completeManagementProbe(ManagementProbed fact) {
        var lease = fact.lease;
        if (!managementCurrent(lease))
            return;
        var request = lease.managedRequest;
        if (fact.failure != null) {
            managementFailure(lease,
                fact.failure instanceof IllegalArgumentException ? invalidRequest() : unavailable());
            return;
        }
        if (request.kind == ManagedKind.QUERY) {
            try {
                var packet = new MissingPreviewPacket(lease.transferId, fact.entries, lease::packetClosed,
                    lease::productionFailed);
                if (submit(lease, List.of(packet)) == SubmitResult.REJECTED)
                    fail(lease.transferId, busy());
            } catch (RuntimeException failure) {
                managementFailure(lease, itemTooLarge());
            }
        } else if (!request.effect.current()) {
            finishManagement(lease, PreviewRepairStatus.STALE);
        } else if (fact.available) {
            finishManagement(lease, PreviewRepairStatus.ALREADY_AVAILABLE);
        } else if (request.kind == ManagedKind.UPLOAD) {
            request.assembly = ArrayBuffer.allocate(request.encodedSize);
            request.admitted = true;
            try {
                management.control
                    .accept(PreviewUploadAdmissionProto.newBuilder().setDataTransferId(lease.transferId).build());
            } catch (RuntimeException failure) {
                managementFailure(lease, unavailable());
            }
        } else {
            var plans = new ArrayList<ChunkPlan>();
            var content = managed(request.record);
            if (content == null) {
                managementFailure(lease, unavailable());
                return;
            }
            for (var chunk : request.chunks.chunks) {
                var descriptor = content.modelFile().getFileView().getAssetView().getChunkInfo(chunk.name);
                plans.add(new ChunkPlan(content, descriptor,
                    (offset, last) -> ChunkFragmentProto.newBuilder().setDataTransferId(lease.transferId)
                        .setName(chunk.name).setOffset(offset).setFinalFragment(last).build()));
            }
            acquireChunks(lease, plans);
        }
    }

    public synchronized boolean accept(PreviewUploadFragmentProto fragment, UniBuffer bytes) {
        if (fragment.dataTransferId() == 0)
            return false;
        var lease = active.get(fragment.dataTransferId());
        if (lease == null)
            return true;
        var upload = lease.managedRequest;
        if (upload == null || upload.kind != ManagedKind.UPLOAD || !upload.admitted) {
            managementFailure(lease, invalidRequest());
            return true;
        }
        var offset = fragment.offset();
        var end = offset + bytes.size();
        if (offset < 0 || bytes.size() <= 0 || end < offset || end > upload.encodedSize
            || fragment.finalFragment() != (end == upload.encodedSize)) {
            managementFailure(lease, invalidRequest());
            return true;
        }
        var previous = upload.fragments.get(offset);
        if (previous != null) {
            if (previous != bytes.size()
                || !UniBufferIO.equals(upload.assembly, Math.toIntExact(offset), bytes, 0, bytes.size())) {
                managementFailure(lease, invalidRequest());
            }
            return true;
        }
        // The transport is ordered; a missing range cannot become a complete upload later.
        if (offset != upload.received || upload.fragments.size() >= 16_384 || upload.started) {
            managementFailure(lease, invalidRequest());
            return true;
        }
        UniBufferIO.copy(bytes, 0, upload.assembly, Math.toIntExact(offset), bytes.size());
        upload.fragments.put(offset, bytes.size());
        upload.received = end;
        if (fragment.finalFragment()) {
            upload.started = true;
            var held = upload.assembly.acquire();
            try {
                upload.budget.execute(() -> {
                    PreviewRepairStatus status = null;
                    Throwable failure = null;
                    try (held) {
                        previews.forContent(upload.record.binding().content()).commit(upload.identity.containerId(),
                            held, upload.effect);
                        status = PreviewRepairStatus.COMMITTED;
                    } catch (ServerModelSession.StaleEffectException stale) {
                        status = PreviewRepairStatus.STALE;
                    } catch (IOException | RuntimeException invalid) {
                        failure = invalid;
                    }
                    if (status == PreviewRepairStatus.COMMITTED) {
                        try {
                            management.committed.accept(upload.identity);
                        } catch (RuntimeException notificationFailure) {
                            YesSteveModel.LOGGER.warn("Failed to publish committed preview notification",
                                notificationFailure);
                        }
                    }
                    enqueueOwnerEvent(new UploadFinished(lease, status, failure));
                });
            } catch (RuntimeException rejected) {
                held.close();
                managementFailure(lease, busy());
            }
        }
        return true;
    }

    private void completeUpload(UploadFinished fact) {
        if (!managementCurrent(fact.lease))
            return;
        if (fact.failure != null) {
            YesSteveModel.LOGGER.warn("Preview upload failed id={}", fact.lease.transferId, fact.failure);
            managementFailure(fact.lease, unavailable());
        } else
            finishManagement(fact.lease, fact.status);
    }

    private void executeManagement(TransferLease lease, Runnable task) {
        try {
            lease.managedRequest.budget.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    enqueueOwnerEvent(new ManagementProbed(lease, List.of(), false, failure));
                }
            });
        } catch (RuntimeException failure) {
            managementFailure(lease, busy());
        }
    }

    private synchronized boolean managementCurrent(TransferLease lease) {
        return !closed && active.get(lease.transferId) == lease;
    }

    private void finishManagement(TransferLease lease, PreviewRepairStatus status) {
        if (retireAcquiring(lease, LeaseState.COMPLETED))
            repairResult(lease.transferId, status);
    }

    private void managementFailure(TransferLease lease, ResourceFailureReason reason) {
        synchronized (this) {
            if (active.get(lease.transferId) != lease)
                return;
            active.remove(lease.transferId, lease);
            lease.state = LeaseState.FAILED;
            releaseManagement(lease);
        }
        releaseAcquisitions(lease.acquisitions);
        lease.packets.forEach(CancellablePacket::cancel);
        fail(lease.transferId, reason);
    }

    private void repairResult(long id, PreviewRepairStatus status) {
        try {
            management.control
                .accept(PreviewRepairResultProto.newBuilder().setDataTransferId(id).setStatus(status).build());
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to send preview repair terminal id={}", id, failure);
        }
    }

    private static void releaseManagement(TransferLease lease) {
        var request = lease.managedRequest;
        if (request == null)
            return;
        if (request.effect != null)
            request.effect.cancel();
        if (request.assembly != null) {
            request.assembly.close();
            request.assembly = null;
        }
        request.budget.close();
    }

    private enum ManagedKind {
        QUERY, CHUNKS, UPLOAD
    }

    private static final class ManagedRequest {
        private final ManagedKind kind;
        private final ModelFileIdentity identity;
        private final int encodedSize;
        private final CatalogRecord record;
        private final ServerModelSession.Effect effect;
        private final ServerPreviewWork.Lease budget;
        private ModelRequest chunks;
        private ArrayBuffer assembly;
        private final Map<Long, Integer> fragments = new LinkedHashMap<>();
        private long received;
        private boolean admitted;
        private boolean started;

        private ManagedRequest(ManagedKind kind, ModelFileIdentity identity, int encodedSize, CatalogRecord record,
            ServerModelSession.Effect effect, ServerPreviewWork.Lease budget) {
            this.kind = kind;
            this.identity = identity;
            this.encodedSize = encodedSize;
            this.record = record;
            this.effect = effect;
            this.budget = budget;
        }
    }

    private record ManagementProbed(TransferLease lease, List<MissingPreviewEntryProto> entries, boolean available,
        Throwable failure) implements OwnerEvent {
    }

    private record UploadFinished(TransferLease lease, PreviewRepairStatus status,
        Throwable failure) implements OwnerEvent {
    }

    /** Returns false only when the cancel itself is structurally invalid. */
    public boolean cancel(long transferId) {
        if (transferId == 0) {
            return false;
        }
        final TransferLease lease;
        synchronized (this) {
            if (closed) {
                return true;
            }
            lease = active.remove(transferId);
            if (lease == null || lease.state != LeaseState.ACTIVE && lease.state != LeaseState.ACQUIRING) {
                return true;
            }
            lease.state = LeaseState.CANCELLED;
        }
        releaseManagement(lease);
        releaseAcquisitions(lease);
        lease.packets.forEach(CancellablePacket::cancel);
        return true;
    }

    /** Commits runtime and dispatch completion facts on the server-session owner tick. */
    public void tick() {
        for (var disposed = 0; disposed < OWNER_EVENTS_PER_TICK; disposed++) {
            var event = ownerEvents.poll();
            if (event == null) {
                return;
            }
            Runnable release = () -> {
            };
            if (event instanceof RetainedManagementFact retained) {
                event = retained.event;
                release = retained.release;
            }
            try {
                if (event instanceof ChunkAcquisitionComplete completed) {
                    completeChunkAcquisition(completed.lease, completed.plans, completed.failure);
                } else if (event instanceof PresentationAcquisitionComplete completed) {
                    completePresentationAcquisition(completed.lease, completed.plans, completed.failure);
                } else if (event instanceof PreviewAcquisitionComplete completed) {
                    completePreviewAcquisition(completed);
                } else if (event instanceof ManagementProbed probed) {
                    completeManagementProbe(probed);
                } else if (event instanceof UploadFinished finished) {
                    completeUpload(finished);
                } else if (event instanceof PacketClosed closed) {
                    packetClosed(closed.lease);
                } else if (event instanceof ProductionFailed failed) {
                    productionFailed(failed.lease, failed.failure);
                }
            } finally {
                release.run();
            }
        }
    }

    @Override
    public void close() {
        final List<TransferLease> abandoned;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            abandoned = List.copyOf(active.values());
            active.clear();
            abandoned.forEach(lease -> lease.state = LeaseState.CANCELLED);
        }
        abandoned.forEach(lease -> {
            releaseManagement(lease);
            releaseAcquisitions(lease);
            lease.packets.forEach(CancellablePacket::cancel);
        });
        OwnerEvent event;
        while ((event = ownerEvents.poll()) != null) {
            if (event instanceof RetainedManagementFact retained)
                retained.release.run();
        }
    }

    synchronized int activeCount() {
        return active.size();
    }

    private PresentationPlan presentationPlan(PresentationMember member, boolean restricted) {
        if (member.kind == PresentationKind.PACK_COVER) {
            return packCoverPlan(member);
        }
        var record = owner.findModel(member.modelId).orElse(null);
        if (record == null) {
            member.failure = restricted ? unauthorized() : notFound();
            return null;
        }
        var assetKind = member.kind == PresentationKind.PREVIEW ? AssetRef.Kind.PREVIEW : AssetRef.Kind.ICON;
        if (!owner.canRequest(member.modelId, assetKind, restricted)) {
            member.failure = unauthorized();
            return null;
        }
        var content = managed(record);
        if (content == null || !content.representation().containerId().equals(member.containerId)) {
            member.failure = invalidRequest();
            return null;
        }
        if (member.kind == PresentationKind.PREVIEW) {
            var chunk = content.modelFile().getFileView().getAssetView()
                .getChunkInfo(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
            var declared = content.modelFile()
                .getThumbnailPreviewSource() != com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.UNSPECIFIED;
            if (declared) {
                if (chunk == null) {
                    member.failure = unavailable();
                    return null;
                }
                if (chunk.size() <= 0 || chunk.size() > PreviewStore.MAX_STORED_BYTES) {
                    member.failure = itemTooLarge();
                    return null;
                }
                return PresentationPlan.model(member, content, chunk);
            }
            return PresentationPlan.preview(member, content);
        }
        var expected = record.entry().presentation().icon().orElse(null);
        if (!matches(member, expected) && !declaredPresentationImage(content.modelFile(), member.name)) {
            member.failure = invalidRequest();
            return null;
        }
        var chunk = content.modelFile().getFileView().getAssetView().getChunkInfo(member.name);
        if (!matches(member, chunk)) {
            member.failure = invalidRequest();
            return null;
        }
        return PresentationPlan.model(member, content, chunk);
    }

    private static boolean declaredPresentationImage(ModelFileView model, String name) {
        // Restricted presentation access must not expose arbitrary image blobs or runtime textures.
        var info = model.getManifest().info();
        var settings = info.settings();
        if (settings.hasGuiForeground()
            && name.equals(AssetFileConstant.BLOB_CHUNK_PREFIX + settings.guiForegroundUnsafe().blobId()))
            return true;
        if (settings.hasGuiBackground()
            && name.equals(AssetFileConstant.BLOB_CHUNK_PREFIX + settings.guiBackgroundUnsafe().blobId()))
            return true;
        var metadata = info.metadataUnsafe();
        return metadata != null && metadata.authors().stream().anyMatch(author -> author.hasAvatar()
            && name.equals(AssetFileConstant.BLOB_CHUNK_PREFIX + author.avatarUnsafe().blobId()));
    }

    private PresentationPlan packCoverPlan(PresentationMember member) {
        var pack = owner.findPack(member.hierarchy).orElse(null);
        if (pack == null) {
            member.failure = notFound();
            return null;
        }
        if (!member.hash.equals(pack.coverHash()) || member.storedSize != pack.coverSize()
            || member.decodedSize != pack.coverSize() || !member.encoding.equals(pack.coverFormat())
            || pack.coverSize() <= 0) {
            member.failure = invalidRequest();
            return null;
        }
        return PresentationPlan.cover(member, CatalogLocation.directory(pack.hierarchy()).root());
    }

    private CancellablePacket presentationPacket(long transferId, PresentationPlan plan, TransferLease lease) {
        if (plan.coverRootKind != null) {
            return packCoverPacket(transferId, plan, lease);
        }
        if (plan.member.kind == PresentationKind.PREVIEW) {
            return emptyPresentationPacket(transferId, plan.member, lease);
        }
        throw new IllegalArgumentException("Model presentation requires a runtime byte lease");
    }

    private CancellablePacket presentationPacket(long transferId, PresentationPlan plan,
        ServerResourceRuntime.BytesLease source, TransferLease lease) {
        return RangePacket
            .lease(source,
                (offset, last) -> presentationFragment(transferId, plan.member, offset, last,
                    com.elfmcys.ysm.proto.network.PresentationDelivery.DATA),
                lease::packetClosed, lease::productionFailed);
    }

    private CancellablePacket packCoverPacket(long transferId, PresentationPlan plan, TransferLease lease) {
        var member = plan.member;
        final Path path;
        try {
            path = packCoverPath(plan.coverRootKind, member.hierarchy);
            if (path == null) {
                return emptyPresentationPacket(transferId, member, lease);
            }
            if (Files.size(path) != member.storedSize || !ModelHashing.blake3(path).equals(member.hash)) {
                member.failure = unavailable();
                return null;
            }
        } catch (IOException | SecurityException failure) {
            return emptyPresentationPacket(transferId, member, lease);
        }
        return filePacket(path, path, 0, member.storedSize, (offset, last) -> presentationFragment(transferId, member,
            offset, last, com.elfmcys.ysm.proto.network.PresentationDelivery.DATA), lease);
    }

    private CancellablePacket emptyPresentationPacket(long transferId, PresentationMember member, TransferLease lease) {
        return new TypedMessagePacket(
            presentationFragment(transferId, member, 0, true, member.kind == PresentationKind.PREVIEW
                ? PresentationDelivery.ABSENT : PresentationDelivery.UNAVAILABLE),
            lease::packetClosed, lease::productionFailed);
    }

    private static ProtoMessage<?> presentationFragment(long transferId, PresentationMember member, long offset,
        boolean last, PresentationDelivery delivery) {
        return switch (member.kind) {
            case PREVIEW -> PreviewFragmentProto.newBuilder().setDataTransferId(transferId).setSlot(member.slot)
                .setOffset(offset).setFinalFragment(last).setDelivery(delivery).build();
            case ICON -> IconFragmentProto.newBuilder().setDataTransferId(transferId).setSlot(member.slot)
                .setOffset(offset).setFinalFragment(last).setDelivery(delivery).build();
            case PACK_COVER -> PackCoverFragmentProto.newBuilder().setDataTransferId(transferId).setSlot(member.slot)
                .setOffset(offset).setFinalFragment(last).setDelivery(delivery).build();
        };
    }

    private void acquireChunks(TransferLease lease, List<ChunkPlan> plans) {
        var acquisitions = new ArrayList<CompletableFuture<ServerResourceRuntime.BytesLease>>(plans.size());
        var failed = false;
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING) {
                return;
            }
            try {
                for (var plan : plans) {
                    acquisitions.add(serverResources.acquireChunk(plan.content, plan.chunk));
                }
                lease.acquisitions = List.copyOf(acquisitions);
            } catch (RuntimeException failure) {
                active.remove(lease.transferId, lease);
                releaseManagement(lease);
                lease.state = LeaseState.FAILED;
                failed = true;
            }
        }
        if (failed) {
            releaseAcquisitions(acquisitions);
            fail(lease.transferId, unavailable());
            return;
        }
        CompletableFuture.allOf(lease.acquisitions.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) -> enqueueOwnerEvent(new ChunkAcquisitionComplete(lease, plans, failure)));
    }

    private void acquirePresentation(TransferLease lease, List<PresentationPlan> plans, long knownBytes) {
        var acquisitions = new ArrayList<CompletableFuture<ServerResourceRuntime.BytesLease>>();
        RuntimeException failure = null;
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING)
                return;
            try {
                for (var plan : plans) {
                    if (plan.chunk != null)
                        acquisitions.add(serverResources.acquireChunk(plan.content, plan.chunk));
                }
            } catch (RuntimeException rejected) {
                failure = rejected;
            }
            lease.acquisitions = List.copyOf(acquisitions);
        }
        if (failure != null) {
            enqueueOwnerEvent(new PresentationAcquisitionComplete(lease, plans, acquisitionFailure(failure)));
            return;
        }
        acquireNextPreview(lease, plans, 0, knownBytes);
    }

    private void acquireNextPreview(TransferLease lease, List<PresentationPlan> plans, int start, long storedBytes) {
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING)
                return;
            for (var index = start; index < plans.size(); index++) {
                var plan = plans.get(index);
                if (!plan.standalonePreview)
                    continue;
                final CompletableFuture<Optional<ServerResourceRuntime.BytesLease>> acquisition;
                try {
                    acquisition = serverResources.acquirePreview(plan.content);
                } catch (RuntimeException rejected) {
                    enqueueOwnerEvent(new PresentationAcquisitionComplete(lease, plans, acquisitionFailure(rejected)));
                    return;
                }
                var acquisitions = new ArrayList<>(lease.previewAcquisitions);
                acquisitions.add(acquisition);
                lease.previewAcquisitions = List.copyOf(acquisitions);
                var next = index + 1;
                acquisition.whenComplete((ignored, failure) -> enqueueOwnerEvent(
                    new PreviewAcquisitionComplete(lease, plans, next, storedBytes, acquisition, failure)));
                return;
            }
            CompletableFuture.allOf(lease.acquisitions.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> enqueueOwnerEvent(new PresentationAcquisitionComplete(lease, plans,
                    failure == null ? null : acquisitionFailure(failure))));
        }
    }

    private void completePreviewAcquisition(PreviewAcquisitionComplete completed) {
        var lease = completed.lease;
        long storedBytes = completed.storedBytes;
        ResourceFailureReason failure = null;
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING)
                return;
            if (completed.failure != null) {
                failure = acquisitionFailure(completed.failure);
            } else {
                var source = completed.acquisition.join().orElse(null);
                if (source != null)
                    storedBytes = Math.addExact(storedBytes, source.size());
                if (storedBytes > MAX_PRESENTATION_STORED_BYTES)
                    failure = itemTooLarge();
            }
        }
        if (failure != null)
            completePresentationAcquisition(lease, completed.plans, failure);
        else
            acquireNextPreview(lease, completed.plans, completed.next, storedBytes);
    }

    private static ResourceFailureReason acquisitionFailure(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null)
            failure = failure.getCause();
        return failure instanceof ServerResourceRuntime.BusyException ? busy() : unavailable();
    }

    private void completePresentationAcquisition(TransferLease lease, List<PresentationPlan> plans,
        ResourceFailureReason failure) {
        List<CompletableFuture<ServerResourceRuntime.BytesLease>> acquisitionsToRelease = List.of();
        List<CompletableFuture<Optional<ServerResourceRuntime.BytesLease>>> previewsToRelease = List.of();
        List<ServerResourceRuntime.BytesLease> sources = null;
        List<Optional<ServerResourceRuntime.BytesLease>> previewSources = null;
        ResourceFailureReason notifyFailure = null;
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING) {
                acquisitionsToRelease = lease.acquisitions;
                previewsToRelease = lease.previewAcquisitions;
            } else if (failure != null) {
                active.remove(lease.transferId, lease);
                releaseManagement(lease);
                lease.state = LeaseState.FAILED;
                acquisitionsToRelease = lease.acquisitions;
                previewsToRelease = lease.previewAcquisitions;
                notifyFailure = failure;
            } else {
                var acquired = new ArrayList<ServerResourceRuntime.BytesLease>(lease.acquisitions.size());
                var acquiredPreviews = new ArrayList<Optional<ServerResourceRuntime.BytesLease>>();
                try {
                    for (var acquisition : lease.acquisitions)
                        acquired
                            .add(Objects.requireNonNull(acquisition.join(), "presentation acquisition returned null"));
                    for (var acquisition : lease.previewAcquisitions)
                        acquiredPreviews.add(acquisition.join());
                    sources = List.copyOf(acquired);
                    previewSources = List.copyOf(acquiredPreviews);
                } catch (RuntimeException sourceFailure) {
                    active.remove(lease.transferId, lease);
                    releaseManagement(lease);
                    lease.state = LeaseState.FAILED;
                    acquisitionsToRelease = lease.acquisitions;
                    previewsToRelease = lease.previewAcquisitions;
                    notifyFailure = acquisitionFailure(sourceFailure);
                }
            }
            lease.acquisitions = List.of();
            lease.previewAcquisitions = List.of();
        }
        releaseAcquisitions(acquisitionsToRelease);
        releasePreviewAcquisitions(previewsToRelease);
        if (notifyFailure != null) {
            fail(lease.transferId, notifyFailure);
            return;
        }
        if (sources == null)
            return;

        var packets = new ArrayList<CancellablePacket>(plans.size());
        var sourceIndex = 0;
        var previewIndex = 0;
        try {
            for (var plan : plans) {
                CancellablePacket packet;
                if (plan.chunk != null) {
                    packet = presentationPacket(lease.transferId, plan, sources.get(sourceIndex), lease);
                    sourceIndex++;
                } else if (plan.standalonePreview) {
                    var source = previewSources.get(previewIndex).orElse(null);
                    packet = source == null ? emptyPresentationPacket(lease.transferId, plan.member, lease)
                        : presentationPacket(lease.transferId, plan, source, lease);
                    previewIndex++;
                } else {
                    packet = presentationPacket(lease.transferId, plan, lease);
                }
                if (packet == null)
                    throw new IllegalStateException("Presentation source became unavailable");
                packets.add(packet);
            }
        } catch (RuntimeException constructionFailure) {
            closePackets(packets);
            closeBytesLeases(sources.subList(sourceIndex, sources.size()));
            previewSources.subList(previewIndex, previewSources.size())
                .forEach(source -> source.ifPresent(ServerResourceRuntime.BytesLease::close));
            if (retireAcquiring(lease, LeaseState.FAILED))
                fail(lease.transferId, unavailable());
            return;
        }
        if (submit(lease, packets) == SubmitResult.REJECTED)
            fail(lease.transferId, busy());
    }

    private void completeChunkAcquisition(TransferLease lease, List<ChunkPlan> plans, Throwable failure) {
        List<CompletableFuture<ServerResourceRuntime.BytesLease>> acquisitionsToRelease = List.of();
        List<ServerResourceRuntime.BytesLease> sources = null;
        ResourceFailureReason notifyFailure = null;
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING) {
                acquisitionsToRelease = lease.acquisitions;
                lease.acquisitions = List.of();
            } else if (failure != null) {
                active.remove(lease.transferId, lease);
                releaseManagement(lease);
                lease.state = LeaseState.FAILED;
                acquisitionsToRelease = lease.acquisitions;
                lease.acquisitions = List.of();
                notifyFailure = acquisitionFailure(failure);
            } else {
                var acquired = new ArrayList<ServerResourceRuntime.BytesLease>(plans.size());
                try {
                    for (var acquisition : lease.acquisitions) {
                        acquired.add(Objects.requireNonNull(acquisition.join(), "chunk acquisition returned null"));
                    }
                    lease.acquisitions = List.of();
                    sources = List.copyOf(acquired);
                } catch (RuntimeException sourceFailure) {
                    active.remove(lease.transferId, lease);
                    releaseManagement(lease);
                    lease.state = LeaseState.FAILED;
                    acquisitionsToRelease = lease.acquisitions;
                    lease.acquisitions = List.of();
                    notifyFailure = acquisitionFailure(sourceFailure);
                }
            }
        }
        releaseAcquisitions(acquisitionsToRelease);
        if (notifyFailure != null) {
            fail(lease.transferId, notifyFailure);
            return;
        }
        if (sources == null) {
            return;
        }

        var packets = new ArrayList<CancellablePacket>(plans.size());
        try {
            for (var index = 0; index < plans.size(); index++) {
                var plan = plans.get(index);
                packets.add(RangePacket.lease(sources.get(index), plan.fragments, lease::packetClosed,
                    lease::productionFailed));
            }
        } catch (RuntimeException constructionFailure) {
            closePackets(packets);
            closeBytesLeases(sources.subList(packets.size(), sources.size()));
            if (retireAcquiring(lease, LeaseState.FAILED)) {
                fail(lease.transferId, unavailable());
            }
            return;
        }
        if (submit(lease, packets) == SubmitResult.REJECTED) {
            fail(lease.transferId, busy());
        }
    }

    private static void releaseAcquisitions(List<CompletableFuture<ServerResourceRuntime.BytesLease>> acquisitions) {
        for (var acquisition : acquisitions) {
            if (!acquisition.isDone() && acquisition.cancel(false)) {
                continue;
            }
            try {
                var value = acquisition.getNow(null);
                if (value != null) {
                    value.close();
                }
            } catch (RuntimeException ignored) {
                // Failed source acquisition owns no lease.
            }
        }
    }

    private static void
        releasePreviewAcquisitions(List<CompletableFuture<Optional<ServerResourceRuntime.BytesLease>>> acquisitions) {
        for (var acquisition : acquisitions) {
            if (!acquisition.isDone() && acquisition.cancel(false))
                continue;
            try {
                var value = acquisition.getNow(Optional.empty());
                value.ifPresent(ServerResourceRuntime.BytesLease::close);
            } catch (RuntimeException ignored) {
                // Failed source acquisition owns no lease.
            }
        }
    }

    private void releaseAcquisitions(TransferLease lease) {
        final List<CompletableFuture<ServerResourceRuntime.BytesLease>> chunks;
        final List<CompletableFuture<Optional<ServerResourceRuntime.BytesLease>>> previews;
        synchronized (this) {
            chunks = lease.acquisitions;
            previews = lease.previewAcquisitions;
            lease.acquisitions = List.of();
            lease.previewAcquisitions = List.of();
        }
        releaseAcquisitions(chunks);
        releasePreviewAcquisitions(previews);
    }

    private CancellablePacket filePacket(Object sourceLease, Path path, long offset, int size,
        RangePacket.FragmentFactory fragments, TransferLease lease) {
        return RangePacket.file(sourceLease, path, offset, size, fragments, lease::packetClosed,
            lease::productionFailed);
    }

    private SubmitResult submit(TransferLease lease, List<? extends CancellablePacket> packets) {
        List<CancellablePacket> owned = List.copyOf(packets);
        final SubmitResult result;
        synchronized (this) {
            if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING) {
                result = SubmitResult.RETIRED;
            } else {
                lease.packets = owned;
                lease.remaining = owned.size();
                if (lease.managedRequest != null) {
                    lease.physicalRemaining = owned.size();
                    lease.dispatchRelease = lease.managedRequest.budget.retain();
                }
                lease.state = LeaseState.ACTIVE;
                boolean accepted;
                try {
                    accepted = dispatch.enqueue(owner, transport, owned);
                } catch (RuntimeException failure) {
                    accepted = false;
                }
                if (accepted) {
                    result = SubmitResult.ACCEPTED;
                } else {
                    active.remove(lease.transferId, lease);
                    releaseManagement(lease);
                    lease.state = LeaseState.CANCELLED;
                    result = SubmitResult.REJECTED;
                }
            }
        }
        if (result != SubmitResult.ACCEPTED) {
            closePackets(owned);
        }
        return result;
    }

    private void packetClosed(TransferLease lease) {
        synchronized (this) {
            if (lease.state != LeaseState.ACTIVE || active.get(lease.transferId) != lease) {
                return;
            }
            if (--lease.remaining == 0) {
                lease.state = LeaseState.COMPLETED;
                active.remove(lease.transferId, lease);
                releaseManagement(lease);
            }
        }
    }

    private void productionFailed(TransferLease lease, RuntimeException failure) {
        synchronized (this) {
            if (closed || lease.state == LeaseState.CANCELLED || lease.state == LeaseState.FAILED
                || lease.state == LeaseState.ACQUIRING
                || lease.state == LeaseState.ACTIVE && active.get(lease.transferId) != lease) {
                return;
            }
            lease.state = LeaseState.FAILED;
            active.remove(lease.transferId, lease);
            releaseManagement(lease);
        }
        lease.packets.forEach(CancellablePacket::cancel);
        YesSteveModel.LOGGER.warn("Remote asset production failed for transfer={}", lease.transferId, failure);
        fail(lease.transferId, unavailable());
    }

    private synchronized Begin begin(long transferId) {
        if (closed) {
            return Begin.CLOSED;
        }
        if (transferId == 0 || Long.compareUnsigned(transferId, highWater) <= 0) {
            return Begin.INVALID;
        }
        highWater = transferId;
        if (active.size() >= MAX_ACTIVE_TRANSFERS) {
            return Begin.BUSY;
        }
        return Begin.ACCEPTED;
    }

    private synchronized TransferLease register(long transferId) {
        if (closed || active.size() >= MAX_ACTIVE_TRANSFERS) {
            return null;
        }
        var lease = new TransferLease(transferId);
        if (active.putIfAbsent(transferId, lease) != null) {
            throw new IllegalStateException("Distribution transfer id was already active");
        }
        return lease;
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private synchronized boolean retireAcquiring(TransferLease lease, LeaseState terminal) {
        if (active.get(lease.transferId) != lease || lease.state != LeaseState.ACQUIRING) {
            return false;
        }
        active.remove(lease.transferId, lease);
        releaseManagement(lease);
        lease.state = terminal;
        return true;
    }

    private synchronized void enqueueOwnerEvent(OwnerEvent event) {
        if (!closed) {
            var lease = event instanceof ManagementProbed fact ? fact.lease
                : event instanceof UploadFinished fact ? fact.lease : null;
            if (lease != null)
                event = new RetainedManagementFact(event, lease.managedRequest.budget.retain());
            ownerEvents.add(Objects.requireNonNull(event, "event"));
        }
    }

    private boolean readyForDistribution() {
        return owner.active() && owner.hasPublishedCatalog();
    }

    private void fail(long transferId, ResourceFailureReason reason) {
        var message = ResourceTransferFailureProto.newBuilder().setDataTransferId(transferId).setReason(reason);
        try {
            failureSink.accept(message.build());
        } catch (RuntimeException failure) {
            YesSteveModel.LOGGER.warn("Failed to send resource transfer failure id={}",
                Long.toUnsignedString(transferId), failure);
        }
    }

    private static MetadataRequest decodeMetadata(MetadataPrefixRequestProto request) {
        Objects.requireNonNull(request, "request");
        var count = request.containerIds().size();
        if (count < 1 || count > MAX_METADATA_MEMBERS || !fitsFrame(request)) {
            return null;
        }
        var ids = new ArrayList<Hash256>(count);
        Hash256 previous = null;
        try {
            for (var value : request.containerIds()) {
                var current = hash(value);
                if (previous != null && previous.compareTo(current) >= 0) {
                    return null;
                }
                ids.add(current);
                previous = current;
            }
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return request.dataTransferId() == 0 ? null : new MetadataRequest(request.dataTransferId(), List.copyOf(ids));
    }

    private static ModelRequest decodeModel(ModelChunkRequestProto request) {
        Objects.requireNonNull(request, "request");
        var count = request.chunks().size();
        if (count < 1 || count > MAX_ACTION_MEMBERS || !fitsFrame(request)) {
            return null;
        }
        try {
            var modelId = hash(request.modelId());
            var containerId = hash(request.containerId());
            var chunks = new ArrayList<ChunkDescriptor>(count);
            String previous = null;
            long stored = 0;
            long decoded = 0;
            for (var chunk : request.chunks()) {
                var name = normalizedName(chunk.name());
                if (previous != null && compareUtf8(previous, name) >= 0) {
                    return null;
                }
                var storedSize = boundedSize(chunk.storedSize(), AssetContainerConstant.MAX_FILE_SIZE, false);
                var decodedSize = boundedSize(chunk.decodedSize(), MAX_CHUNK_DECODE_BYTES, true);
                stored = Math.addExact(stored, storedSize);
                decoded = Math.addExact(decoded, decodedSize);
                chunks.add(new ChunkDescriptor(name, hash(chunk.expectedHash()), storedSize, decodedSize,
                    Objects.requireNonNull(chunk.encoding())));
                previous = name;
            }
            if (stored > MAX_CHUNK_STORED_BYTES || decoded > MAX_CHUNK_DECODE_BYTES || request.dataTransferId() == 0) {
                return null;
            }
            var purpose = request.purpose();
            if (purpose != ModelChunkPurpose.ORDINARY && purpose != ModelChunkPurpose.GEN_PREVIEW_MAIN)
                return null;
            return new ModelRequest(request.dataTransferId(), modelId, containerId, List.copyOf(chunks), purpose);
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static PresentationRequest decodePresentation(PresentationPageRequestProto request) {
        Objects.requireNonNull(request, "request");
        var count = request.previews().size() + request.icons().size() + request.packCovers().size();
        if (count < 1 || count > MAX_ACTION_MEMBERS || !fitsFrame(request)) {
            return null;
        }
        try {
            var members = new ArrayList<PresentationMember>(count);
            decodePreviews(request.previews(), members);
            decodeIcons(request.icons(), members);
            var previous = -1;
            for (var cover : request.packCovers()) {
                if (cover.slot() < 0 || cover.slot() <= previous) {
                    return null;
                }
                var hierarchy = normalizedHierarchy(cover.hierarchyPath());
                var stored = boundedSize(cover.storedSize(), AssetContainerConstant.MAX_FILE_SIZE, false);
                var decoded = boundedSize(cover.decodedSize(), MAX_PRESENTATION_DECODE_BYTES, true);
                members.add(PresentationMember.cover(cover.slot(), hierarchy, hash(cover.expectedHash()), stored,
                    decoded, nonBlank(cover.encoding())));
                previous = cover.slot();
            }
            long stored = 0;
            long decoded = 0;
            for (var member : members) {
                stored = Math.addExact(stored, member.storedSize);
                decoded = Math.addExact(decoded, member.decodedSize);
            }
            if (stored > MAX_PRESENTATION_STORED_BYTES || decoded > MAX_PRESENTATION_DECODE_BYTES
                || request.dataTransferId() == 0) {
                return null;
            }
            return new PresentationRequest(request.dataTransferId(), List.copyOf(members));
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void decodePreviews(Iterable<PreviewMemberProto> values, List<PresentationMember> output) {
        var previous = -1;
        for (var value : values) {
            if (value.slot() < 0 || value.slot() <= previous) {
                throw new IllegalArgumentException("Presentation slots are not canonical");
            }
            output.add(PresentationMember.model(PresentationKind.PREVIEW, value.slot(), hash(value.modelId()),
                hash(value.containerId()), "", new Hash256(new byte[Hash256.SIZE]), 0, 0, ""));
            previous = value.slot();
        }
    }

    private static void decodeIcons(Iterable<IconMemberProto> values, List<PresentationMember> output) {
        var previous = -1;
        for (var value : values) {
            if (value.slot() < 0 || value.slot() <= previous) {
                throw new IllegalArgumentException("Presentation slots are not canonical");
            }
            output.add(PresentationMember.model(PresentationKind.ICON, value.slot(), hash(value.modelId()),
                hash(value.containerId()), normalizedName(value.name()), hash(value.expectedHash()),
                boundedSize(value.storedSize(), AssetContainerConstant.MAX_FILE_SIZE, false),
                boundedSize(value.decodedSize(), MAX_PRESENTATION_DECODE_BYTES, true), nonBlank(value.encoding())));
            previous = value.slot();
        }
    }

    private static boolean fitsFrame(ProtoMessage<?> request) {
        try (var protobuf = ProtocolBuffer.serialize(request); var attachment = ArrayBuffer.allocate(0);
            var frame = FrameCodec.encode(0, protobuf, attachment)) {
            return frame.size() > 0;
        } catch (IllegalArgumentException oversized) {
            return false;
        }
    }

    private static Hash256 hash(ByteBuffer bytes) {
        return new Hash256(ProtoBytes.copy(bytes));
    }

    private static int boundedSize(long value, long maximum, boolean allowZero) {
        if (value < 0 || !allowZero && value == 0 || value > maximum || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Resource size is outside its typed bound");
        }
        return (int)value;
    }

    private static String normalizedName(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Resource name is not normalized");
        }
        var normalized = new ModelPath(value).value();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException("Resource name is not canonical");
        }
        return value;
    }

    private static String normalizedHierarchy(String value) {
        return com.elfmcys.ysm.model.catalog.source.CatalogLocation.directory(value).value();
    }

    private static String nonBlank(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Encoding is not normalized");
        }
        return value;
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean matches(ChunkDescriptor requested, AssetContainerView.ChunkInfo chunk) {
        return chunk != null && requested.name.equals(chunk.type()) && requested.hash.matches(chunk.hash())
            && requested.storedSize == chunk.size() && requested.decodedSize == chunk.decodeSize()
            && requested.encoding.equals(chunk.encoding());
    }

    private static boolean matches(PresentationMember requested, AssetRef expected) {
        return expected != null && requested.name.equals(expected.name()) && requested.hash.equals(expected.hash())
            && requested.storedSize == expected.storedSize() && requested.decodedSize == expected.decodedSize()
            && requested.encoding.equals(expected.encoding());
    }

    private static boolean matches(PresentationMember requested, AssetContainerView.ChunkInfo chunk) {
        return chunk != null && requested.name.equals(chunk.type()) && requested.hash.matches(chunk.hash())
            && requested.storedSize == chunk.size() && requested.decodedSize == chunk.decodeSize()
            && requested.encoding.equals(chunk.encoding());
    }

    private static ManagedContainer managed(CatalogRecord record) {
        return record.binding().content() instanceof ManagedContainer content ? content : null;
    }

    private static boolean rangeAvailable(Path path, long offset, int size) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Math.addExact(offset, size) <= Files.size(path);
        } catch (IOException | ArithmeticException | SecurityException unavailable) {
            return false;
        }
    }

    private Path packCoverPath(CatalogRoot rootKind, String hierarchy) throws IOException {
        var configuredRoot = roots.root(rootKind).toAbsolutePath().normalize();
        var candidate =
            configuredRoot.resolve(CatalogLocation.directory(hierarchy).entry()).resolve("ysm-pack.png").normalize();
        if (!candidate.startsWith(configuredRoot)) {
            throw new IllegalArgumentException("Pack cover path escapes its catalog root");
        }
        if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        var realCandidate = candidate.toRealPath();
        if (!Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pack cover is not a regular file");
        }
        return realCandidate;
    }

    private static Path catalogRoot(CatalogRoot rootKind) {
        return ModelCatalogSources.sources().stream().filter(source -> source.publicRoot() == rootKind)
            .map(source -> source.path().toAbsolutePath().normalize()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No catalog root for " + rootKind));
    }

    private static void closeBuffers(List<? extends UniBuffer> buffers) {
        for (var buffer : buffers) {
            try {
                buffer.close();
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.error("Failed to close metadata source", failure);
            }
        }
    }

    private static void closeBytesLeases(List<? extends ServerResourceRuntime.BytesLease> leases) {
        for (var lease : leases) {
            try {
                lease.close();
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.error("Failed to close chunk source", failure);
            }
        }
    }

    private static void closePackets(List<? extends CancellablePacket> packets) {
        for (var packet : packets) {
            try {
                packet.close();
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.error("Failed to close unaccepted resource packet", failure);
            }
        }
    }

    private static ResourceFailureReason invalidRequest() {
        return ResourceFailureReason.INVALID_REQUEST;
    }

    private static ResourceFailureReason unauthorized() {
        return ResourceFailureReason.UNAUTHORIZED;
    }

    private static ResourceFailureReason notFound() {
        return ResourceFailureReason.NOT_FOUND;
    }

    private static ResourceFailureReason busy() {
        return ResourceFailureReason.BUSY;
    }

    private static ResourceFailureReason itemTooLarge() {
        return ResourceFailureReason.ITEM_TOO_LARGE;
    }

    private static ResourceFailureReason unavailable() {
        return ResourceFailureReason.UNAVAILABLE;
    }

    @FunctionalInterface
    interface RootResolver {
        Path root(CatalogRoot kind);
    }

    private sealed interface OwnerEvent permits ChunkAcquisitionComplete, PresentationAcquisitionComplete, PreviewAcquisitionComplete, PacketClosed, ProductionFailed, ManagementProbed, UploadFinished, RetainedManagementFact {}

    private record RetainedManagementFact(OwnerEvent event, Runnable release) implements OwnerEvent {
    }

    private record ChunkAcquisitionComplete(TransferLease lease, List<ChunkPlan> plans,
        Throwable failure) implements OwnerEvent {
    }

    private record PresentationAcquisitionComplete(TransferLease lease, List<PresentationPlan> plans,
        ResourceFailureReason failure) implements OwnerEvent {
    }

    private record PreviewAcquisitionComplete(TransferLease lease, List<PresentationPlan> plans, int next,
        long storedBytes, CompletableFuture<Optional<ServerResourceRuntime.BytesLease>> acquisition,
        Throwable failure) implements OwnerEvent {
    }

    private record PacketClosed(TransferLease lease) implements OwnerEvent {
    }

    private record ProductionFailed(TransferLease lease, RuntimeException failure) implements OwnerEvent {
    }

    private final class TransferLease {
        private final long transferId;
        private List<CancellablePacket> packets;
        private List<CompletableFuture<ServerResourceRuntime.BytesLease>> acquisitions;
        private List<CompletableFuture<Optional<ServerResourceRuntime.BytesLease>>> previewAcquisitions;
        private ManagedRequest managedRequest;
        private int remaining;
        private int physicalRemaining;
        private Runnable dispatchRelease;
        private LeaseState state;

        private TransferLease(long transferId) {
            this.transferId = transferId;
            packets = List.of();
            acquisitions = List.of();
            previewAcquisitions = List.of();
            state = LeaseState.ACQUIRING;
        }

        private void packetClosed() {
            synchronized (ServerAssetTransfers.this) {
                if (physicalRemaining > 0 && --physicalRemaining == 0 && dispatchRelease != null) {
                    dispatchRelease.run();
                    dispatchRelease = null;
                }
            }
            enqueueOwnerEvent(new PacketClosed(this));
        }

        private void productionFailed(RuntimeException failure) {
            // Seal sibling dispatch before the server tick commits the terminal outcome.
            packets.forEach(CancellablePacket::cancel);
            enqueueOwnerEvent(new ProductionFailed(this, failure));
        }
    }

    private record MetadataPlan(Hash256 containerId, ModelRepresentation representation) {
    }

    private record ChunkPlan(ManagedContainer content, AssetContainerView.ChunkInfo chunk,
        RangePacket.FragmentFactory fragments) {
    }

    private record PresentationPlan(PresentationMember member, ManagedContainer content,
        AssetContainerView.ChunkInfo chunk, boolean standalonePreview, CatalogRoot coverRootKind) {
        private static PresentationPlan model(PresentationMember member, ManagedContainer content,
            AssetContainerView.ChunkInfo chunk) {
            return new PresentationPlan(member, content, chunk, false, null);
        }

        private static PresentationPlan preview(PresentationMember member, ManagedContainer content) {
            return new PresentationPlan(member, content, null, true, null);
        }

        private static PresentationPlan cover(PresentationMember member, CatalogRoot rootKind) {
            return new PresentationPlan(member, null, null, false, rootKind);
        }
    }

    private record MetadataRequest(long transferId, List<Hash256> containerIds) {
    }

    private record ModelRequest(long transferId, Hash256 modelId, Hash256 containerId, List<ChunkDescriptor> chunks,
        ModelChunkPurpose purpose) {
    }

    private record ChunkDescriptor(String name, Hash256 hash, int storedSize, int decodedSize, String encoding) {
    }

    private record PresentationRequest(long transferId, List<PresentationMember> members) {
    }

    private static final class PresentationMember {
        private final PresentationKind kind;
        private final int slot;
        private final Hash256 modelId;
        private final Hash256 containerId;
        private final String name;
        private final String hierarchy;
        private final Hash256 hash;
        private final int storedSize;
        private final int decodedSize;
        private final String encoding;
        private ResourceFailureReason failure = invalidRequest();

        private PresentationMember(PresentationKind kind, int slot, Hash256 modelId, Hash256 containerId, String name,
            String hierarchy, Hash256 hash, int storedSize, int decodedSize, String encoding) {
            this.kind = kind;
            this.slot = slot;
            this.modelId = modelId;
            this.containerId = containerId;
            this.name = name;
            this.hierarchy = hierarchy;
            this.hash = hash;
            this.storedSize = storedSize;
            this.decodedSize = decodedSize;
            this.encoding = encoding;
        }

        private static PresentationMember model(PresentationKind kind, int slot, Hash256 modelId, Hash256 containerId,
            String name, Hash256 hash, int storedSize, int decodedSize, String encoding) {
            return new PresentationMember(kind, slot, modelId, containerId, name, null, hash, storedSize, decodedSize,
                encoding);
        }

        private static PresentationMember cover(int slot, String hierarchy, Hash256 hash, int storedSize,
            int decodedSize, String encoding) {
            return new PresentationMember(PresentationKind.PACK_COVER, slot, null, null, null, hierarchy, hash,
                storedSize, decodedSize, encoding);
        }
    }

    private enum PresentationKind {
        PREVIEW, ICON, PACK_COVER
    }

    private enum Begin {
        ACCEPTED, BUSY, INVALID, CLOSED
    }

    private enum LeaseState {
        ACQUIRING, ACTIVE, COMPLETED, CANCELLED, FAILED
    }

    private enum SubmitResult {
        ACCEPTED, REJECTED, RETIRED
    }
}
