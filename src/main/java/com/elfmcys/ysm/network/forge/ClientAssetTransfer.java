package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.buffer.BufferStorage;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.proto.network.ModelChunkPurpose;
import com.elfmcys.ysm.proto.network.MissingPreviewQueryProto;
import com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewUploadRequestProto;
import com.elfmcys.ysm.proto.network.PreviewUploadAdmissionProto;
import com.elfmcys.ysm.proto.network.PreviewUploadFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewRepairResultProto;
import com.elfmcys.ysm.proto.network.PreviewRepairStatus;
import net.minecraft.network.Connection;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;
import com.elfmcys.ysm.proto.network.ChunkMemberProto;
import com.elfmcys.ysm.proto.network.IconFragmentProto;
import com.elfmcys.ysm.proto.network.IconMemberProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixFragmentProto;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequestProto;
import com.elfmcys.ysm.proto.network.ModelChunkRequestProto;
import com.elfmcys.ysm.proto.network.PackCoverFragmentProto;
import com.elfmcys.ysm.proto.network.PackCoverMemberProto;
import com.elfmcys.ysm.proto.network.PresentationDelivery;
import com.elfmcys.ysm.proto.network.PresentationPageRequestProto;
import com.elfmcys.ysm.proto.network.PreviewFragmentProto;
import com.elfmcys.ysm.proto.network.PreviewMemberProto;
import com.elfmcys.ysm.proto.network.ResourceFailureReason;
import com.elfmcys.ysm.proto.network.ResourceTransferCancelProto;
import com.elfmcys.ysm.proto.network.ResourceTransferFailureProto;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;
import us.hebi.quickbuf.ProtoMessage;

/** Connection owner for typed model-distribution children and their monotonic ID stream. */
final class ClientAssetTransfer implements RemoteChunkFetcher, RemoteMetadataFetcher, RemotePresentationFetcher {
    static final int MAX_METADATA_MEMBERS = 16_384;
    static final int MAX_ACTION_MEMBERS = 256;
    static final int MAX_FRAGMENT_RECORDS = 16_384;
    static final long MAX_METADATA_BYTES = 128L * 1024 * 1024;
    static final long MAX_CHUNK_STORED_BYTES = 128L * 1024 * 1024;
    static final long MAX_CHUNK_DECODE_BYTES = 512L * 1024 * 1024;
    static final long MAX_PRESENTATION_STORED_BYTES = 128L * 1024 * 1024;
    static final long MAX_PRESENTATION_DECODE_BYTES = 256L * 1024 * 1024;

    private static final Comparator<String> UTF8_ORDER = (left, right) -> Arrays
        .compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));

    private final Sender sender;
    private final ClientModelSession session;
    private final Map<Long, PendingChild> active = new HashMap<>();
    private long nextTransferId;
    private long nextUploadNanos;
    private boolean transferIdExhausted;
    private boolean closed;

    ClientAssetTransfer(Connection connection, ClientModelSession session) {
        this(new Sender() {
            @Override
            public void request(ProtoMessage<?> request) {
                if (!connection.isConnected())
                    throw new IllegalStateException("Connection closed");
                NetworkHandler.sendToServer(connection, request);
            }

            @Override
            public void cancel(ResourceTransferCancelProto cancel) {
                if (connection.isConnected())
                    NetworkHandler.sendToServer(connection, cancel);
            }

            @Override
            public boolean writable() {
                if (!connection.isConnected() || !connection.channel().isWritable())
                    return false;
                var outbound = connection.channel().unsafe().outboundBuffer();
                return outbound == null || outbound.totalPendingWriteBytes() + 30L * 1024 < connection.channel()
                    .config().getWriteBufferHighWaterMark();
            }

            @Override
            public int fragment(PreviewUploadFragmentProto fragment, UniBuffer bytes) {
                return NetworkHandler.sendToServer(connection,
                    com.elfmcys.ysm.network.NetworkPayload.withRaw(fragment, bytes.share()));
            }
        }, session);
    }

    ClientAssetTransfer(Sender sender) {
        this(sender, null);
    }

    ClientAssetTransfer(Sender sender, ClientModelSession session) {
        this(sender, session, 1);
    }

    ClientAssetTransfer(Sender sender, long firstTransferId) {
        this(sender, null, firstTransferId);
    }

    ClientAssetTransfer(Sender sender, ClientModelSession session, long firstTransferId) {
        if (firstTransferId == 0) {
            throw new IllegalArgumentException("Remote transfer id must be nonzero");
        }
        this.sender = Objects.requireNonNull(sender, "sender");
        this.session = session;
        nextTransferId = firstTransferId;
    }

    @Override
    public CompletableFuture<Void> fetchMetadata(List<ModelFileIdentity> identities, MetadataReceiver receiver) {
        try {
            requireActiveSession();
            var action = new MetadataAction(canonicalMetadata(identities), receiver);
            action.startNext();
            return action.result;
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletableFuture<Void> fetch(ModelFileIdentity identity, List<AssetContainerView.ChunkInfo> chunks,
        ChunkReceiver receiver) {
        try {
            requireActiveSession();
            var action = new ModelAction(identity, canonicalChunks(chunks), receiver);
            action.startNext();
            return action.result;
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletableFuture<Void> fetch(List<RemotePresentationFetcher.Member> members,
        PresentationReceiver receiver) {
        try {
            requireActiveSession();
            var requested = new ArrayList<RemotePresentationFetcher.Member>();
            for (var member : canonicalPresentation(members)) {
                ClientModelSession.PreviewObservation absentObservation = null;
                if (session != null && member instanceof RemotePresentationFetcher.Preview preview) {
                    synchronized (session) {
                        if (session.previewAbsent(preview.identity())) {
                            absentObservation = session.observePreview(preview.identity()).orElseThrow();
                        }
                    }
                }
                if (absentObservation != null) {
                    var observation = absentObservation;
                    receiver.accept(member,
                        new RemotePresentationFetcher.Absent(() -> session.previewObservationCurrent(observation)));
                } else {
                    requested.add(member);
                }
            }
            var action = new PresentationAction(List.copyOf(requested), receiver);
            action.startNext();
            return action.result;
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    CompletableFuture<Void> fetchManagement(ModelFileIdentity identity, List<AssetContainerView.ChunkInfo> chunks,
        ChunkReceiver receiver) {
        try {
            requireActiveSession();
            var action =
                new ModelAction(identity, canonicalChunks(chunks), receiver, ModelChunkPurpose.GEN_PREVIEW_MAIN);
            action.startNext();
            return action.result;
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    synchronized CompletableFuture<List<MissingItem>> queryMissingPreviews() {
        try {
            requireActiveSession();
            var child = new QueryChild(allocateId());
            var output = child.result.thenApply(ignored -> List.copyOf(child.entries));
            output.whenComplete((ignored, failure) -> {
                if (output.isCancelled())
                    child.result.cancel(false);
            });
            send(child, MissingPreviewQueryProto.newBuilder().setDataTransferId(child.id).build());
            return output;
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    synchronized CompletableFuture<PreviewRepairStatus> uploadPreview(ModelFileIdentity identity,
        PreviewStore.EncodedPreview preview) {
        try {
            requireActiveSession();
            var child = new UploadChild(allocateId(), preview.acquireBytes());
            var output = child.result.thenApply(ignored -> child.status);
            output.whenComplete((ignored, failure) -> {
                if (output.isCancelled())
                    child.result.cancel(false);
            });
            send(child,
                PreviewUploadRequestProto.newBuilder().setDataTransferId(child.id)
                    .setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
                    .setContainerId(ByteBuffer.wrap(identity.containerId().bytes())).setEncodedSize(child.bytes.size())
                    .build());
            return output;
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    synchronized void acceptMissingPreviews(MissingPreviewFragmentProto message) {
        var child = typed(message.dataTransferId(), QueryChild.class);
        if (child == null)
            return;
        var previous = child.fragments.get(message.sequence());
        if (previous != null) {
            if (!previous.equals(message))
                fail(child, content("Conflicting preview query fragment"), true);
            return;
        }
        if (message.sequence() != child.fragments.size() || message.sequence() >= 65_536
            || child.totalBytes + message.getSerializedSize() > MAX_METADATA_BYTES
            || child.entries.size() + message.entries().size() > MAX_METADATA_MEMBERS
            || message.entries().isEmpty() && !message.finalFragment()) {
            fail(child, content("Incomplete or oversized preview query"), true);
            return;
        }
        try {
            for (var entry : message.entries()) {
                if (entry.modelId().remaining() != Hash256.SIZE || entry.containerId().remaining() != Hash256.SIZE
                    || entry.probeFailure() != ResourceFailureReason.UNSPECIFIED
                        && entry.probeFailure() != ResourceFailureReason.UNAVAILABLE) {
                    throw content("Invalid preview query member");
                }
                var identity = new ModelFileIdentity(new Hash256(ProtoBytes.copy(entry.modelId())),
                    new Hash256(ProtoBytes.copy(entry.containerId())));
                var path = com.elfmcys.ysm.model.catalog.source.CatalogLocation.parse(entry.hierarchyPath()).value();
                if (!path.equals(entry.hierarchyPath())
                    || child.previous != null && compareIdentity(child.previous, identity) >= 0)
                    throw content("Noncanonical preview query");
                child.entries.add(new MissingItem(identity, path, entry.probeFailure()));
                child.previous = identity;
            }
            child.fragments.put(message.sequence(), message);
            child.totalBytes += message.getSerializedSize();
            if (message.finalFragment())
                complete(child);
        } catch (IOException | RuntimeException invalid) {
            fail(child, content("Invalid preview query member", invalid), true);
        }
    }

    private static int compareIdentity(ModelFileIdentity left, ModelFileIdentity right) {
        var model = left.modelId().compareTo(right.modelId());
        return model != 0 ? model : left.containerId().compareTo(right.containerId());
    }

    synchronized void acceptUploadAdmission(PreviewUploadAdmissionProto message) {
        var child = typed(message.dataTransferId(), UploadChild.class);
        if (child != null)
            child.admitted = true;
    }

    synchronized void acceptRepairResult(PreviewRepairResultProto message) {
        var child = active.get(message.dataTransferId());
        if (child == null)
            return;
        var status = message.status();
        if (status == null || status == PreviewRepairStatus.UNSPECIFIED
            || status == PreviewRepairStatus.COMMITTED && (!(child instanceof UploadChild upload) || !upload.sentAll)) {
            fail(child, content("Invalid preview repair terminal"), true);
            return;
        }
        if (child instanceof UploadChild upload) {
            upload.status = status;
            complete(child);
        } else if (child instanceof ModelChild && status != PreviewRepairStatus.COMMITTED) {
            fail(child, new Skipped(status), false);
        } else
            fail(child, content("Preview repair terminal for unrelated request"), true);
    }

    /** Bounded cursor: no worker or client tick waits for transport pressure or rate time. */
    synchronized void tickUpload(long now, long bytesPerSecond) {
        for (var child : List.copyOf(active.values())) {
            if (!(child instanceof UploadChild upload) || !upload.admitted || upload.sentAll)
                continue;
            for (var sent = 0; sent < 4 && now >= nextUploadNanos && sender.writable(); sent++) {
                var size = Math.min(24 * 1024, upload.bytes.size() - upload.offset);
                var fragment = PreviewUploadFragmentProto.newBuilder().setDataTransferId(upload.id)
                    .setOffset(upload.offset).setFinalFragment(upload.offset + size == upload.bytes.size()).build();
                try {
                    var frameBytes = sender.fragment(fragment, upload.bytes.slice(upload.offset, size));
                    upload.offset += size;
                    upload.sentAll = upload.offset == upload.bytes.size();
                    if (bytesPerSecond > 0)
                        nextUploadNanos = now + (long)Math.ceil(frameBytes * 1_000_000_000.0 / bytesPerSecond);
                    if (upload.sentAll)
                        break;
                } catch (RuntimeException failure) {
                    fail(upload, AssetLoadException.access("Preview upload transport failed", failure), true);
                    break;
                }
            }
        }
    }

    record MissingItem(ModelFileIdentity identity, String path, ResourceFailureReason probeFailure) {
    }

    static final class Skipped extends IOException {
        final PreviewRepairStatus status;

        Skipped(PreviewRepairStatus status) {
            super("Preview repair skipped: " + status);
            this.status = status;
        }
    }

    synchronized void acceptMetadata(MetadataPrefixFragmentProto message) {
        var child = typed(message.dataTransferId(), MetadataChild.class);
        if (child == null)
            return;
        if (++child.recordCount > MAX_FRAGMENT_RECORDS || message.sequence() < 0
            || message.containerId().remaining() != Hash256.SIZE || message.payload().remaining() == 0) {
            fail(child, content("Invalid metadata prefix fragment"), true);
            return;
        }
        var containerId = new Hash256(ProtoBytes.copy(message.containerId()));
        var member = child.members.get(containerId);
        if (member == null) {
            fail(child, content("Unknown metadata prefix member"), true);
            return;
        }
        var payload = ProtoBytes.copy(message.payload());
        var fragment = new SequenceFragment(payload, message.finalFragment());
        var previous = member.fragments.get(message.sequence());
        if (previous != null) {
            if (!previous.equals(fragment)) {
                fail(child, content("Conflicting metadata prefix fragment"), true);
            }
            return;
        }
        if (member.finalSequence != null && message.sequence() > member.finalSequence
            || message.finalFragment() && member.finalSequence != null && member.finalSequence != message.sequence()
            || child.totalBytes + payload.length > MAX_METADATA_BYTES) {
            fail(child, content("Metadata prefix coverage exceeds its bounds"), true);
            return;
        }
        member.fragments.put(message.sequence(), fragment);
        child.totalBytes += payload.length;
        if (message.finalFragment())
            member.finalSequence = message.sequence();
        completeMetadataMember(child, member);
    }

    synchronized void acceptChunk(ChunkFragmentProto message, UniBuffer data) {
        var child = typed(message.dataTransferId(), ModelChild.class);
        if (child == null)
            return;
        if (++child.recordCount > MAX_FRAGMENT_RECORDS) {
            fail(child, content("Model chunk fragment count exceeds its bound"), true);
            return;
        }
        var member = child.members.get(message.name());
        if (member == null || !acceptRange(member, message.offset(), message.finalFragment(), data)) {
            fail(child, content("Invalid model chunk range"), true);
            return;
        }
        if (member.complete() && !member.delivered) {
            try (var bytes = member.assemble()) {
                child.receiver.accept(member.chunk, bytes);
                member.delivered = true;
            } catch (IOException | RuntimeException failure) {
                fail(child, content("Rejected completed model chunk", failure), true);
                return;
            }
        }
        completeIfDelivered(child, child.members.values());
    }

    synchronized void acceptPreview(PreviewFragmentProto message, UniBuffer data) {
        acceptPresentation(message.dataTransferId(), PresentationKind.PREVIEW, message.slot(), message.offset(),
            message.finalFragment(), message.delivery(), data);
    }

    synchronized void acceptIcon(IconFragmentProto message, UniBuffer data) {
        acceptPresentation(message.dataTransferId(), PresentationKind.ICON, message.slot(), message.offset(),
            message.finalFragment(), message.delivery(), data);
    }

    synchronized void acceptPackCover(PackCoverFragmentProto message, UniBuffer data) {
        acceptPresentation(message.dataTransferId(), PresentationKind.PACK_COVER, message.slot(), message.offset(),
            message.finalFragment(), message.delivery(), data);
    }

    synchronized void fail(ResourceTransferFailureProto failure) {
        var child = active.get(failure.dataTransferId());
        if (child != null)
            fail(child, rejection(failure.reason()), false);
    }

    synchronized void close() {
        if (closed)
            return;
        closed = true;
        var failure = AssetLoadException.access("Model session closed during asset transfer");
        for (var child : List.copyOf(active.values()))
            fail(child, failure, false);
    }

    private void acceptPresentation(long transferId, PresentationKind kind, int slot, long offset, boolean terminal,
        PresentationDelivery delivery, UniBuffer data) {
        var child = typed(transferId, PresentationChild.class);
        if (child == null)
            return;
        if (++child.recordCount > MAX_FRAGMENT_RECORDS || slot < 0 || delivery == null
            || delivery == com.elfmcys.ysm.proto.network.PresentationDelivery.UNSPECIFIED) {
            fail(child, content("Invalid presentation fragment"), true);
            return;
        }
        var member = child.members.get(new PresentationKey(kind, slot));
        if (member == null) {
            fail(child, content("Unknown presentation member"), true);
            return;
        }
        if (delivery == PresentationDelivery.UNAVAILABLE || delivery == PresentationDelivery.ABSENT) {
            if ((delivery == PresentationDelivery.ABSENT) != (kind == PresentationKind.PREVIEW)) {
                fail(child, content("Delivery is not valid for this presentation kind"), true);
                return;
            }
            if (data.size() != 0 || offset != 0 || !terminal || member.range.received != 0) {
                fail(child, content("Invalid unavailable presentation outcome"), true);
                return;
            }
            if (!member.delivered) {
                try {
                    child.receiver.accept(member.descriptor,
                        delivery == PresentationDelivery.ABSENT
                            ? new RemotePresentationFetcher.Absent(
                                () -> session == null || session.previewObservationCurrent(member.observation))
                            : RemotePresentationFetcher.Unavailable.INSTANCE);
                    if (delivery == PresentationDelivery.ABSENT && session != null) {
                        session.completePreviewAbsent(member.observation);
                    }
                    member.emptyDelivery = delivery;
                    member.delivered = true;
                } catch (IOException | RuntimeException failure) {
                    fail(child, content("Rejected unavailable presentation outcome", failure), true);
                    return;
                }
            } else if (member.emptyDelivery != delivery) {
                fail(child, content("Conflicting presentation outcome"), true);
                return;
            }
        } else {
            var before = member.range.received;
            if (member.emptyDelivery != null || !acceptRange(member.range, offset, terminal, data)) {
                fail(child, content("Invalid presentation data range"), true);
                return;
            }
            child.totalBytes += member.range.received - before;
            if (child.totalBytes > MAX_PRESENTATION_STORED_BYTES) {
                fail(child, content("Presentation data exceeds its aggregate bound"), true);
                return;
            }
            if (member.range.complete() && !member.delivered) {
                try {
                    var bytes = com.elfmcys.ysm.buffer.SharedBuffer.adopt(member.range.assemble());
                    if (kind == PresentationKind.PREVIEW && member.observation == null) {
                        throw content("Preview data has no session observation");
                    }
                    child.receiver.accept(member.descriptor,
                        new RemotePresentationFetcher.Data(bytes, member.observation));
                    member.delivered = true;
                } catch (IOException | RuntimeException failure) {
                    fail(child, content("Rejected completed presentation member", failure), true);
                    return;
                }
            }
        }
        if (child.members.values().stream().allMatch(value -> value.delivered))
            complete(child);
    }

    private void completeMetadataMember(MetadataChild child, MetadataMember member) {
        if (member.delivered || member.finalSequence == null || member.finalSequence >= MAX_FRAGMENT_RECORDS
            || member.fragments.size() != member.finalSequence + 1)
            return;
        var output = new ByteArrayOutputStream();
        for (var sequence = 0; sequence <= member.finalSequence; sequence++) {
            var fragment = member.fragments.get(sequence);
            if (fragment == null)
                return;
            output.writeBytes(fragment.payload);
        }
        try (var bytes = ArrayBuffer.move(output.toByteArray())) {
            child.receiver.accept(member.identity, bytes);
            member.delivered = true;
        } catch (IOException | RuntimeException failure) {
            fail(child, content("Rejected completed metadata prefix", failure), true);
            return;
        }
        if (child.members.values().stream().allMatch(value -> value.delivered))
            complete(child);
    }

    private static boolean acceptRange(RangeMember member, long offset, boolean terminal, UniBuffer data) {
        if (offset < 0 || data.size() <= 0)
            return false;
        final long end;
        try {
            end = Math.addExact(offset, data.size());
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (end > member.expectedSize || member.exactSize && terminal && end != member.expectedSize)
            return false;
        var payload = new byte[data.size()];
        try {
            data.nio().get(payload);
        } finally {
            java.lang.ref.Reference.reachabilityFence(data);
        }
        var previous = member.fragments.get(offset);
        var range = new RangeFragment(payload, terminal);
        if (previous != null)
            return previous.equals(range);
        var lower = member.fragments.lowerEntry(offset);
        if (lower != null && lower.getKey() + lower.getValue().payload.length > offset)
            return false;
        var higher = member.fragments.ceilingEntry(offset);
        if (higher != null && end > higher.getKey())
            return false;
        if (member.finalEnd != null && (end > member.finalEnd || terminal && member.finalEnd != end))
            return false;
        member.fragments.put(offset, range);
        member.received += payload.length;
        if (terminal)
            member.finalEnd = end;
        return true;
    }

    private <T extends PendingChild> T typed(long id, Class<T> type) {
        if (closed)
            return null;
        var child = active.get(id);
        if (child == null)
            return null;
        if (!type.isInstance(child)) {
            fail(child, content("Remote fragment type does not match its request"), true);
            return null;
        }
        return type.cast(child);
    }

    private void completeIfDelivered(PendingChild child, Iterable<? extends Deliverable> members) {
        for (var member : members)
            if (!member.delivered())
                return;
        complete(child);
    }

    private void complete(PendingChild child) {
        if (active.remove(child.id, child)) {
            releaseChild(child);
            child.result.complete(null);
        }
    }

    private void fail(PendingChild child, IOException failure, boolean notifyServer) {
        if (!active.remove(child.id, child))
            return;
        releaseChild(child);
        child.result.completeExceptionally(failure);
        if (notifyServer && child.requestSent) {
            try {
                sender.cancel(ResourceTransferCancelProto.newBuilder().setDataTransferId(child.id).build());
            } catch (RuntimeException ignored) {
                // The local child terminal owns the result; cancel delivery is best effort.
            }
        }
    }

    private static void releaseChild(PendingChild child) {
        if (child instanceof UploadChild upload && upload.bytes != null) {
            upload.bytes.close();
            upload.bytes = null;
        } else if (child instanceof QueryChild query)
            query.fragments.clear();
    }

    private void cancel(PendingChild child) {
        fail(child, AssetLoadException.access("Remote asset request cancelled"), true);
    }

    private void send(PendingChild child, ProtoMessage<?> request) {
        active.put(child.id, child);
        child.result.whenComplete((ignored, failure) -> {
            if (child.result.isCancelled()) {
                synchronized (ClientAssetTransfer.this) {
                    cancel(child);
                }
            }
        });
        child.requestSent = true;
        try {
            sender.request(request);
        } catch (RuntimeException failure) {
            fail(child, AssetLoadException.access("Failed to submit remote asset request", failure), false);
        }
    }

    private long allocateId() throws IOException {
        if (closed)
            throw AssetLoadException.access("Model session is closed");
        if (transferIdExhausted)
            throw AssetLoadException.access("Remote transfer id space is exhausted; reconnect is required");
        var result = nextTransferId;
        if (result == -1L) {
            transferIdExhausted = true;
        } else {
            nextTransferId = result + 1;
        }
        return result;
    }

    private void requireActiveSession() throws IOException {
        if (session != null && session.state() != ClientModelSession.State.ACTIVE) {
            throw AssetLoadException.access("Remote assets require an active model session");
        }
        if (closed)
            throw AssetLoadException.access("Model session is closed");
    }

    private static boolean fitsFrame(ProtoMessage<?> request) {
        var spec = ProtocolMessages.REGISTRY.find(request.getClass()).orElseThrow();
        try (var protobuf = ProtocolBuffer.serialize(request); var attachment = ArrayBuffer.allocate(0);
            var frame = FrameCodec.encode(spec.id(), protobuf, attachment)) {
            return true;
        } catch (IllegalArgumentException oversized) {
            return false;
        }
    }

    private static List<ModelFileIdentity> canonicalMetadata(List<ModelFileIdentity> identities) throws IOException {
        Objects.requireNonNull(identities, "identities");
        if (identities.isEmpty() || identities.size() > MAX_METADATA_MEMBERS) {
            throw content("Metadata action member count is invalid");
        }
        var canonical = new ArrayList<>(List.copyOf(identities));
        canonical.sort(Comparator.comparing(ModelFileIdentity::containerId));
        for (var index = 1; index < canonical.size(); index++) {
            if (canonical.get(index - 1).containerId().equals(canonical.get(index).containerId())) {
                throw content("Metadata action contains duplicate container ids");
            }
        }
        return List.copyOf(canonical);
    }

    private static List<AssetContainerView.ChunkInfo> canonicalChunks(List<AssetContainerView.ChunkInfo> chunks)
        throws IOException {
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty() || chunks.size() > MAX_ACTION_MEMBERS) {
            throw content("Model action member count is invalid");
        }
        var canonical = new ArrayList<>(List.copyOf(chunks));
        canonical.sort(Comparator.comparing(AssetContainerView.ChunkInfo::type, UTF8_ORDER));
        long stored = 0;
        long decoded = 0;
        String previous = null;
        for (var chunk : canonical) {
            if (chunk.type() == null || chunk.type().isBlank() || !chunk.type().equals(chunk.type().trim())
                || chunk.hash() == null || chunk.hash().length != Hash256.SIZE || chunk.size() <= 0
                || chunk.size() > AssetContainerConstant.MAX_FILE_SIZE || chunk.decodeSize() < 0
                || Objects.equals(previous, chunk.type())) {
                throw content("Model action contains an invalid chunk descriptor");
            }
            stored = Math.addExact(stored, chunk.size());
            decoded = Math.addExact(decoded, chunk.decodeSize());
            previous = chunk.type();
        }
        if (stored > MAX_CHUNK_STORED_BYTES || decoded > MAX_CHUNK_DECODE_BYTES) {
            throw content("Model action exceeds its byte bounds");
        }
        return List.copyOf(canonical);
    }

    private static List<RemotePresentationFetcher.Member>
        canonicalPresentation(List<RemotePresentationFetcher.Member> members) throws IOException {
        Objects.requireNonNull(members, "members");
        if (members.isEmpty() || members.size() > MAX_ACTION_MEMBERS) {
            throw content("Presentation action member count is invalid");
        }
        var canonical = new ArrayList<>(List.copyOf(members));
        canonical.sort(Comparator.comparing(ClientAssetTransfer::presentationKind)
            .thenComparingInt(RemotePresentationFetcher.Member::slot));
        long stored = 0;
        long decoded = 0;
        PresentationKey previous = null;
        for (var member : canonical) {
            var key = new PresentationKey(presentationKind(member), member.slot());
            if (member.slot() < 0 || key.equals(previous)) {
                throw content("Presentation action contains an invalid slot");
            }
            var size = presentationSize(member);
            var decodeSize = presentationDecodeSize(member);
            var descriptorValid = member instanceof RemotePresentationFetcher.Preview
                || presentationHash(member).length == Hash256.SIZE && !presentationEncoding(member).isBlank();
            if (size <= 0 || size > AssetContainerConstant.MAX_FILE_SIZE || decodeSize < 0 || !descriptorValid) {
                throw content("Presentation action contains an invalid descriptor");
            }
            stored = Math.addExact(stored, member instanceof RemotePresentationFetcher.Preview ? 0 : size);
            decoded = Math.addExact(decoded, decodeSize);
            previous = key;
        }
        if (stored > MAX_PRESENTATION_STORED_BYTES || decoded > MAX_PRESENTATION_DECODE_BYTES) {
            throw content("Presentation action exceeds its byte bounds");
        }
        return List.copyOf(canonical);
    }

    private static PresentationKind presentationKind(RemotePresentationFetcher.Member member) {
        if (member instanceof RemotePresentationFetcher.Preview)
            return PresentationKind.PREVIEW;
        if (member instanceof RemotePresentationFetcher.Icon)
            return PresentationKind.ICON;
        return PresentationKind.PACK_COVER;
    }

    private static int presentationSize(RemotePresentationFetcher.Member member) {
        if (member instanceof RemotePresentationFetcher.Preview)
            return PreviewStore.MAX_STORED_BYTES;
        if (member instanceof RemotePresentationFetcher.Icon icon)
            return icon.chunk().size();
        return ((RemotePresentationFetcher.PackCover)member).pack().coverSize();
    }

    private static int presentationDecodeSize(RemotePresentationFetcher.Member member) {
        if (member instanceof RemotePresentationFetcher.Preview)
            return 0;
        if (member instanceof RemotePresentationFetcher.Icon icon)
            return icon.chunk().decodeSize();
        return ((RemotePresentationFetcher.PackCover)member).pack().coverSize();
    }

    private static byte[] presentationHash(RemotePresentationFetcher.Member member) {
        if (member instanceof RemotePresentationFetcher.Preview)
            return new byte[Hash256.SIZE];
        if (member instanceof RemotePresentationFetcher.Icon icon)
            return icon.chunk().hash();
        return ((RemotePresentationFetcher.PackCover)member).pack().coverHash().bytes();
    }

    private static String presentationEncoding(RemotePresentationFetcher.Member member) {
        if (member instanceof RemotePresentationFetcher.Preview)
            return "preview";
        if (member instanceof RemotePresentationFetcher.Icon icon)
            return icon.chunk().encoding();
        return ((RemotePresentationFetcher.PackCover)member).pack().coverFormat();
    }

    private static AssetLoadException content(String message) {
        return AssetLoadException.content(message);
    }

    private static AssetLoadException content(String message, Throwable cause) {
        return AssetLoadException.content(message, cause);
    }

    static AssetLoadException rejection(ResourceFailureReason reason) {
        var message = "Server rejected remote asset: " + (reason == null ? "UNSPECIFIED" : reason.getName());
        if (reason == null)
            return content(message);
        return switch (reason) {
            case UNAUTHORIZED, NOT_FOUND, BUSY, UNAVAILABLE -> AssetLoadException.access(message);
            default -> content(message);
        };
    }

    interface Sender {
        void request(ProtoMessage<?> request);

        void cancel(ResourceTransferCancelProto cancel);

        default boolean writable() {
            return true;
        }

        default int fragment(PreviewUploadFragmentProto fragment, UniBuffer bytes) {
            throw new UnsupportedOperationException("Upload transport is unavailable");
        }
    }

    private final class MetadataAction {
        private final List<ModelFileIdentity> members;
        private final MetadataReceiver receiver;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private int next;
        private MetadataChild current;

        private MetadataAction(List<ModelFileIdentity> members, MetadataReceiver receiver) {
            this.members = members;
            this.receiver = Objects.requireNonNull(receiver, "receiver");
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled())
                    synchronized (ClientAssetTransfer.this) {
                        if (current != null)
                            cancel(current);
                    }
            });
        }

        private void startNext() {
            synchronized (ClientAssetTransfer.this) {
                if (result.isDone())
                    return;
                if (next == members.size()) {
                    result.complete(null);
                    return;
                }
                try {
                    var from = next;
                    var end = largestMetadataChild(nextTransferId, members, from);
                    if (end == from)
                        throw content("Metadata member cannot fit one frame");
                    var id = allocateId();
                    current = new MetadataChild(id, members.subList(from, end), receiver);
                    next = end;
                    var child = current;
                    child.result.whenComplete((ignored, failure) -> {
                        if (failure != null)
                            result.completeExceptionally(failure);
                        else
                            startNext();
                    });
                    send(child, metadataRequest(id, members.subList(from, end)));
                } catch (IOException | RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }
        }
    }

    private final class ModelAction {
        private final ModelFileIdentity identity;
        private final List<AssetContainerView.ChunkInfo> members;
        private final ChunkReceiver receiver;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private int next;
        private ModelChild current;
        private final ModelChunkPurpose purpose;

        private ModelAction(ModelFileIdentity identity, List<AssetContainerView.ChunkInfo> members,
            ChunkReceiver receiver) {
            this(identity, members, receiver, ModelChunkPurpose.ORDINARY);
        }

        private ModelAction(ModelFileIdentity identity, List<AssetContainerView.ChunkInfo> members,
            ChunkReceiver receiver, ModelChunkPurpose purpose) {
            this.purpose = purpose;
            this.identity = Objects.requireNonNull(identity, "identity");
            this.members = members;
            this.receiver = Objects.requireNonNull(receiver, "receiver");
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled())
                    synchronized (ClientAssetTransfer.this) {
                        if (current != null)
                            cancel(current);
                    }
            });
        }

        private void startNext() {
            synchronized (ClientAssetTransfer.this) {
                if (result.isDone())
                    return;
                if (next == members.size()) {
                    result.complete(null);
                    return;
                }
                try {
                    var from = next;
                    var end = largestFitting(from, members.size(), limit -> fitsFrame(
                        modelRequest(nextTransferId, identity, members.subList(from, limit), purpose)));
                    if (end == from)
                        throw content("Model member cannot fit one frame");
                    var id = allocateId();
                    current = new ModelChild(id, members.subList(from, end), receiver);
                    next = end;
                    var child = current;
                    child.result.whenComplete((ignored, failure) -> {
                        if (failure != null)
                            result.completeExceptionally(failure);
                        else
                            startNext();
                    });
                    send(child, modelRequest(id, identity, members.subList(from, end), purpose));
                } catch (IOException | RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }
        }
    }

    private final class PresentationAction {
        private final List<RemotePresentationFetcher.Member> members;
        private final PresentationReceiver receiver;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private int next;
        private PresentationChild current;

        private PresentationAction(List<RemotePresentationFetcher.Member> members, PresentationReceiver receiver) {
            this.members = members;
            this.receiver = Objects.requireNonNull(receiver, "receiver");
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled())
                    synchronized (ClientAssetTransfer.this) {
                        if (current != null)
                            cancel(current);
                    }
            });
        }

        private void startNext() {
            synchronized (ClientAssetTransfer.this) {
                if (result.isDone())
                    return;
                if (next == members.size()) {
                    result.complete(null);
                    return;
                }
                try {
                    var from = next;
                    var end = largestPresentationChild(nextTransferId, members, from);
                    if (end == from)
                        throw content("Presentation member cannot fit one frame");
                    var id = allocateId();
                    current = new PresentationChild(id, members.subList(from, end), receiver, session);
                    next = end;
                    var child = current;
                    child.result.whenComplete((ignored, failure) -> {
                        if (failure != null)
                            result.completeExceptionally(failure);
                        else
                            startNext();
                    });
                    send(child, presentationRequest(id, members.subList(from, end)));
                } catch (IOException | RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }
        }
    }

    private static int largestMetadataChild(long id, List<ModelFileIdentity> members, int from) {
        return largestFitting(from, members.size(), end -> fitsFrame(metadataRequest(id, members.subList(from, end))));
    }

    private static int largestPresentationChild(long id, List<RemotePresentationFetcher.Member> members, int from) {
        return largestFitting(from, members.size(),
            end -> fitsFrame(presentationRequest(id, members.subList(from, end))));
    }

    private static int largestFitting(int from, int size, IntPredicate fits) {
        if (fits.test(size))
            return size;
        var low = from + 1;
        var high = size - 1;
        var best = from;
        while (low <= high) {
            var middle = low + (high - low) / 2;
            if (fits.test(middle)) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static MetadataPrefixRequestProto metadataRequest(long id, List<ModelFileIdentity> members) {
        var request = MetadataPrefixRequestProto.newBuilder().setDataTransferId(id);
        members.forEach(member -> request.addContainerIds(ByteBuffer.wrap(member.containerId().bytes())));
        return request.build();
    }

    private static ModelChunkRequestProto modelRequest(long id, ModelFileIdentity identity,
        List<AssetContainerView.ChunkInfo> members) {
        return modelRequest(id, identity, members, ModelChunkPurpose.ORDINARY);
    }

    private static ModelChunkRequestProto modelRequest(long id, ModelFileIdentity identity,
        List<AssetContainerView.ChunkInfo> members, ModelChunkPurpose purpose) {
        var request = ModelChunkRequestProto.newBuilder().setPurpose(purpose).setDataTransferId(id)
            .setModelId(ByteBuffer.wrap(identity.modelId().bytes()))
            .setContainerId(ByteBuffer.wrap(identity.containerId().bytes()));
        members.forEach(chunk -> request.addChunks(
            ChunkMemberProto.newBuilder().setName(chunk.type()).setExpectedHash(ByteBuffer.wrap(chunk.hash()))
                .setStoredSize(chunk.size()).setDecodedSize(chunk.decodeSize()).setEncoding(chunk.encoding()).build()));
        return request.build();
    }

    private static PresentationPageRequestProto presentationRequest(long id,
        List<RemotePresentationFetcher.Member> members) {
        var request = PresentationPageRequestProto.newBuilder().setDataTransferId(id);
        for (var member : members) {
            if (member instanceof RemotePresentationFetcher.Preview preview) {
                request.addPreviews(PreviewMemberProto.newBuilder().setSlot(preview.slot())
                    .setModelId(ByteBuffer.wrap(preview.identity().modelId().bytes()))
                    .setContainerId(ByteBuffer.wrap(preview.identity().containerId().bytes())).build());
            } else if (member instanceof RemotePresentationFetcher.Icon icon) {
                request.addIcons(IconMemberProto.newBuilder().setSlot(icon.slot())
                    .setModelId(ByteBuffer.wrap(icon.identity().modelId().bytes()))
                    .setContainerId(ByteBuffer.wrap(icon.identity().containerId().bytes())).setName(icon.chunk().type())
                    .setExpectedHash(ByteBuffer.wrap(icon.chunk().hash())).setStoredSize(icon.chunk().size())
                    .setDecodedSize(icon.chunk().decodeSize()).setEncoding(icon.chunk().encoding()).build());
            } else {
                var cover = (RemotePresentationFetcher.PackCover)member;
                request.addPackCovers(
                    PackCoverMemberProto.newBuilder().setSlot(cover.slot()).setHierarchyPath(cover.pack().hierarchy())
                        .setExpectedHash(ByteBuffer.wrap(cover.pack().coverHash().bytes()))
                        .setStoredSize(cover.pack().coverSize()).setDecodedSize(cover.pack().coverSize())
                        .setEncoding(cover.pack().coverFormat()).build());
            }
        }
        return request.build();
    }

    private abstract static sealed class PendingChild permits MetadataChild, ModelChild, PresentationChild, QueryChild, UploadChild {
        final long id;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        boolean requestSent;

        private PendingChild(long id) {
            this.id = id;
        }
    }

    private static final class QueryChild extends PendingChild {
        private final Map<Integer, MissingPreviewFragmentProto> fragments = new LinkedHashMap<>();
        private final List<MissingItem> entries = new ArrayList<>();
        private long totalBytes;
        private ModelFileIdentity previous;

        private QueryChild(long id) {
            super(id);
        }
    }

    private static final class UploadChild extends PendingChild {
        private BufferStorage bytes;
        private int offset;
        private boolean admitted;
        private boolean sentAll;
        private PreviewRepairStatus status;

        private UploadChild(long id, BufferStorage bytes) {
            super(id);
            this.bytes = bytes;
        }
    }

    private static final class MetadataChild extends PendingChild {
        private final Map<Hash256, MetadataMember> members = new LinkedHashMap<>();
        private final MetadataReceiver receiver;
        private int recordCount;
        private long totalBytes;

        private MetadataChild(long id, List<ModelFileIdentity> identities, MetadataReceiver receiver) {
            super(id);
            this.receiver = receiver;
            identities.forEach(identity -> members.put(identity.containerId(), new MetadataMember(identity)));
        }
    }

    private static final class ModelChild extends PendingChild {
        private final Map<String, ModelMember> members = new LinkedHashMap<>();
        private final ChunkReceiver receiver;
        private int recordCount;

        private ModelChild(long id, List<AssetContainerView.ChunkInfo> chunks, ChunkReceiver receiver) {
            super(id);
            this.receiver = receiver;
            chunks.forEach(chunk -> members.put(chunk.type(), new ModelMember(chunk)));
        }
    }

    private static final class PresentationChild extends PendingChild {
        private final Map<PresentationKey, PresentationMember> members = new LinkedHashMap<>();
        private final PresentationReceiver receiver;
        private int recordCount;
        private long totalBytes;

        private PresentationChild(long id, List<RemotePresentationFetcher.Member> descriptors,
            PresentationReceiver receiver, ClientModelSession session) {
            super(id);
            this.receiver = receiver;
            descriptors
                .forEach(descriptor -> members.put(new PresentationKey(presentationKind(descriptor), descriptor.slot()),
                    new PresentationMember(descriptor, presentationSize(descriptor),
                        !(descriptor instanceof RemotePresentationFetcher.Preview), session)));
        }
    }

    private interface Deliverable {
        boolean delivered();
    }

    private static final class MetadataMember implements Deliverable {
        private final ModelFileIdentity identity;
        private final TreeMap<Integer, SequenceFragment> fragments = new TreeMap<>();
        private Integer finalSequence;
        private boolean delivered;

        private MetadataMember(ModelFileIdentity identity) {
            this.identity = identity;
        }

        @Override
        public boolean delivered() {
            return delivered;
        }
    }

    private static final class ModelMember extends RangeMember implements Deliverable {
        private final AssetContainerView.ChunkInfo chunk;
        private boolean delivered;

        private ModelMember(AssetContainerView.ChunkInfo chunk) {
            super(chunk.size());
            this.chunk = chunk;
        }

        @Override
        public boolean delivered() {
            return delivered;
        }
    }

    private static final class PresentationMember {
        private final RemotePresentationFetcher.Member descriptor;
        private final RangeMember range;
        private PresentationDelivery emptyDelivery;
        private final ClientModelSession.PreviewObservation observation;
        private boolean delivered;

        private PresentationMember(RemotePresentationFetcher.Member descriptor, long expectedSize, boolean exactSize,
            ClientModelSession session) {
            this.descriptor = descriptor;
            this.range = new RangeMember(expectedSize, exactSize);
            observation = session != null && descriptor instanceof RemotePresentationFetcher.Preview preview
                ? session.observePreview(preview.identity()).orElse(null) : null;
        }
    }

    private static class RangeMember {
        private final long expectedSize;
        private final boolean exactSize;
        private final TreeMap<Long, RangeFragment> fragments = new TreeMap<>();
        private long received;
        private Long finalEnd;

        private RangeMember(long expectedSize) {
            this(expectedSize, true);
        }

        private RangeMember(long expectedSize, boolean exactSize) {
            this.expectedSize = expectedSize;
            this.exactSize = exactSize;
        }

        final boolean complete() {
            if (finalEnd == null || received != finalEnd || exactSize && finalEnd != expectedSize)
                return false;
            long next = 0;
            for (var fragment : fragments.entrySet()) {
                if (fragment.getKey() != next)
                    return false;
                next += fragment.getValue().payload.length;
            }
            return next == finalEnd;
        }

        final BufferStorage assemble() {
            var output = ArrayBuffer.allocate(Math.toIntExact(finalEnd));
            var target = output.nio();
            fragments.values().forEach(fragment -> target.put(fragment.payload));
            target.flip();
            return output;
        }
    }

    private record SequenceFragment(byte[] payload, boolean terminal) {
        @Override
        public boolean equals(Object other) {
            return other instanceof SequenceFragment fragment && terminal == fragment.terminal
                && Arrays.equals(payload, fragment.payload);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(payload) + Boolean.hashCode(terminal);
        }
    }

    private record RangeFragment(byte[] payload, boolean terminal) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RangeFragment fragment && terminal == fragment.terminal
                && Arrays.equals(payload, fragment.payload);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(payload) + Boolean.hashCode(terminal);
        }
    }

    private enum PresentationKind {
        PREVIEW, ICON, PACK_COVER
    }

    private record PresentationKey(PresentationKind kind, int slot) {
    }
}
