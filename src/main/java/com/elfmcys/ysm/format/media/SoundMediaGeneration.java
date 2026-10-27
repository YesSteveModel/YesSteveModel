package com.elfmcys.ysm.format.media;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.sound.AudioNormalizer;
import com.elfmcys.ysm.proto.mixel.common.SoundProto;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Owns the one-shot R/T decision for one source audio identity. */
public final class SoundMediaGeneration {
    private static final BigInteger PACKET_RATE_SCALE = BigInteger.valueOf(8L * 48_000L);
    private static final BigInteger TARGET_BIT_RATE = BigInteger.valueOf(70_000L);

    private SoundMediaGeneration() {}

    public static Selected normalize(NativeBuffer source, int transcodedSerial, CandidateSizer sizer)
        throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sizer, "sizer");
        var inspection = SupportedAudioProbe.inspect(source.nio());
        if (!inspection.playable()) {
            throw new IOException("Invalid source audio: " + inspection.disposition() + ": " + inspection.diagnostic());
        }
        if (inspection.media().encoding() == SupportedAudioProbe.Encoding.OGG_VORBIS) {
            return required(source, transcodedSerial);
        }

        var retained = AudioNormalizer.normalize(source, AudioNormalizer.Role.RETAINED, transcodedSerial);
        if (!retained.isSuccess()) {
            throw failure("retained candidate", retained);
        }
        if (!exceedsTargetRate(retained)) {
            return selected(retained);
        }

        var transcoded = AudioNormalizer.normalize(source, AudioNormalizer.Role.TRANSCODED, transcodedSerial);
        if (!transcoded.isSuccess()) {
            if (transcoded.failureKind() == AudioNormalizer.FailureKind.OPTIONAL_CANDIDATE_FAILURE) {
                transcoded.close();
                return selected(retained);
            }
            retained.close();
            throw failure("optional transcoded candidate", transcoded);
        }
        try {
            var retainedView = new Candidate(retained);
            var transcodedView = new Candidate(transcoded);
            long retainedLength = sizer.serializedLength(retainedView);
            long transcodedLength = sizer.serializedLength(transcodedView);
            if (transcodedLength < retainedLength) {
                retained.close();
                return selected(transcoded);
            }
            transcoded.close();
            return selected(retained);
        } catch (IOException | RuntimeException | Error failure) {
            retained.close();
            transcoded.close();
            throw failure;
        }
    }

    private static Selected required(NativeBuffer source, int transcodedSerial) throws IOException {
        var transcoded = AudioNormalizer.normalize(source, AudioNormalizer.Role.TRANSCODED, transcodedSerial);
        if (!transcoded.isSuccess()) {
            throw failure("required Vorbis transcoding", transcoded);
        }
        return selected(transcoded);
    }

    private static Selected selected(AudioNormalizer.Result result) {
        try {
            return new Selected(result);
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private static boolean exceedsTargetRate(AudioNormalizer.Result retained) {
        return exceedsTargetRate(retained.audioPacketBytes(), retained.frames());
    }

    static boolean exceedsTargetRate(long audioPacketBytes, long frames) {
        if (audioPacketBytes < 0 || frames <= 0) {
            throw new IllegalArgumentException("Invalid audio rate facts");
        }
        return BigInteger.valueOf(audioPacketBytes).multiply(PACKET_RATE_SCALE)
            .compareTo(BigInteger.valueOf(frames).multiply(TARGET_BIT_RATE)) > 0;
    }

    private static IOException failure(String operation, AudioNormalizer.Result result) {
        try {
            return new IOException("Audio " + operation + " failed: kind=" + result.failureKind() + ", stage="
                + result.stage() + ", status=" + result.statusCode() + ": " + result.diagnostic());
        } finally {
            result.close();
        }
    }

    @FunctionalInterface
    public interface CandidateSizer {
        long serializedLength(Candidate candidate) throws IOException;
    }

    public static final class Candidate {
        private final AudioNormalizer.Result result;

        private Candidate(AudioNormalizer.Result result) {
            this.result = result;
            if (result.codec() != AudioNormalizer.Codec.OPUS || result.sampleRate() != 48_000
                || result.chunks().length == 0) {
                throw new IllegalStateException("Native normalization returned an invalid storage candidate");
            }
        }

        public SoundProto sound(String name, int firstChunkId) {
            Objects.requireNonNull(name, "name");
            var chunks = result.chunks();
            var builder = SoundProto.newBuilder().setName(name).setEncoding("OGG_OPUS").setChannels(result.channels())
                .setSampleRate(48_000).setSamples(result.frames()).setFirstPage(result.firstPage());
            for (var index = 0; index < chunks.length; ++index) {
                builder.addChunkIds(Math.addExact(firstChunkId, index));
            }
            return builder.build();
        }

        public ByteBuffer[] chunks() {
            return result.chunks();
        }

        public AudioNormalizer.Role role() {
            return result.role();
        }

        public long audioPacketBytes() {
            return result.audioPacketBytes();
        }
    }

    public static final class Selected implements AutoCloseable {
        private final AudioNormalizer.Result result;
        private final Candidate candidate;

        private Selected(AudioNormalizer.Result result) {
            this.result = result;
            candidate = new Candidate(result);
        }

        public Candidate candidate() {
            return candidate;
        }

        @Override
        public void close() {
            result.close();
        }
    }
}
