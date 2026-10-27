package com.elfmcys.ysm.model.resource.client.audio;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class PcmAudio {
    private final byte[][] segments;
    private final int[] segmentEnds;
    private final int size;
    private final AudioFormat format;

    /** Transfers the backing array; the caller must not mutate it after construction. */
    PcmAudio(byte[] bytes, AudioFormat format) {
        this(List.of(bytes), format);
    }

    /** Transfers the arrays without copying PCM; the caller must stop mutating them, but may reuse the list. */
    PcmAudio(List<byte[]> segments, AudioFormat format) {
        this.format = Objects.requireNonNull(format, "format");
        var retained = new ArrayList<byte[]>();
        int total = 0;
        for (var segment : segments) {
            Objects.requireNonNull(segment, "segment");
            if (segment.length != 0) {
                retained.add(segment);
                total = Math.addExact(total, segment.length);
            }
        }
        this.segments = retained.toArray(byte[][]::new);
        segmentEnds = new int[this.segments.length];
        int end = 0;
        for (int i = 0; i < this.segments.length; i++) {
            end += this.segments[i].length;
            segmentEnds[i] = end;
        }
        size = total;
    }

    AudioFormat format() {
        return format;
    }

    ByteBuffer read(int offset, int length) {
        Objects.checkFromIndexSize(offset, length, size);
        if (length == 0) {
            return ByteBuffer.allocate(0).asReadOnlyBuffer();
        }
        int index = Arrays.binarySearch(segmentEnds, offset + 1);
        if (index < 0) {
            index = -index - 1;
        }
        int localOffset = offset - (index == 0 ? 0 : segmentEnds[index - 1]);
        if (length <= segments[index].length - localOffset) {
            return ByteBuffer.wrap(segments[index], localOffset, length).slice().asReadOnlyBuffer();
        }
        // Only a requested range crossing segments needs a contiguous host buffer.
        var result = ByteBuffer.allocate(length);
        while (result.hasRemaining()) {
            var segment = segments[index++];
            int count = Math.min(result.remaining(), segment.length - localOffset);
            result.put(segment, localOffset, count);
            localOffset = 0;
        }
        return result.flip().asReadOnlyBuffer();
    }

    public int size() {
        return size;
    }
}
