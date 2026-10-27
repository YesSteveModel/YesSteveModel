package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.MissingPreviewEntryProto;
import com.elfmcys.ysm.proto.network.MissingPreviewFragmentProto;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A complete bounded query, dispatched through the ordinary asset pressure owner. */
final class MissingPreviewPacket implements CancellablePacket {
    private List<Part> fragments;
    private final Runnable closeHook;
    private final Consumer<RuntimeException> failure;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean cancelled;

    MissingPreviewPacket(long id, List<MissingPreviewEntryProto> entries, Runnable closeHook,
        Consumer<RuntimeException> failure) {
        if (entries.size() > 16_384)
            throw new IllegalArgumentException("Preview query too large");
        this.closeHook = closeHook;
        this.failure = failure;
        var complete = fragment(id, 0, true, entries);
        var completeBytes = frameBytes(complete);
        if (completeBytes != 0) {
            fragments = List.of(new Part(complete, completeBytes));
            return;
        }
        var parts = new ArrayList<Part>();
        long decodedBytes = 0;
        for (var start = 0; start < entries.size();) {
            var end = start;
            long bytes = 32;
            while (end < entries.size()) {
                var entryBytes = entries.get(end).getSerializedSize() + 6L;
                if (end > start && bytes + entryBytes >= FrameCodec.MAX_BODY_BYTES)
                    break;
                bytes += entryBytes;
                end++;
            }
            var fragment = fragment(id, parts.size(), end == entries.size(), entries.subList(start, end));
            decodedBytes += fragment.getSerializedSize();
            if (decodedBytes > 128L * 1024 * 1024) {
                throw new IllegalArgumentException("Preview query decoded bound exceeded");
            }
            var frameBytes = frameBytes(fragment);
            if (frameBytes == 0)
                throw new IllegalArgumentException("Preview query entry exceeds a frame");
            parts.add(new Part(fragment, frameBytes));
            start = end;
        }
        fragments = List.copyOf(parts);
    }

    private static MissingPreviewFragmentProto fragment(long id, int sequence, boolean last,
        List<MissingPreviewEntryProto> entries) {
        return MissingPreviewFragmentProto.newBuilder().setDataTransferId(id).setSequence(sequence)
            .setFinalFragment(last).addAllEntries(entries).build();
    }

    private static int frameBytes(MissingPreviewFragmentProto fragment) {
        if (fragment.getSerializedSize() > FrameCodec.MAX_DECODED_PROTO_BYTES)
            return 0;
        try (var protobuf = ProtocolBuffer.serialize(fragment); var empty = ArrayBuffer.allocate(0);
            var frame = FrameCodec.encode(ProtocolMessages.MISSING_PREVIEW_FRAGMENT_ID, protobuf, empty)) {
            return frame.size();
        } catch (IllegalArgumentException tooLarge) {
            return 0;
        }
    }

    private record Part(MissingPreviewFragmentProto protobuf, int frameBytes) {
    }

    @Override
    public int fragmentCount() {
        return fragments.size();
    }

    @Override
    public long estimateFrameBytes(int index) {
        return fragments.get(index).frameBytes;
    }

    @Override
    public OutboundFrame buildFragment(int index) {
        try (var protobuf = ProtocolBuffer.serialize(fragments.get(index).protobuf);
            var empty = ArrayBuffer.allocate(0)) {
            return FrameCodec.encode(ProtocolMessages.MISSING_PREVIEW_FRAGMENT_ID, protobuf, empty);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public void productionFailed(RuntimeException cause) {
        failure.accept(cause);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true))
            return;
        cancelled = true;
        fragments = List.of();
        closeHook.run();
    }
}
