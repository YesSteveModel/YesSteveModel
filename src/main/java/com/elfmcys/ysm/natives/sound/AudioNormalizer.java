package com.elfmcys.ysm.natives.sound;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.NativeObject;
import com.elfmcys.ysm.natives.buffer.BufferArgument;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class AudioNormalizer {
    private AudioNormalizer() {}

    public enum Role {
        RETAINED, TRANSCODED
    }

    public enum Codec {
        OPUS, VORBIS
    }

    public enum FailureKind {
        SOURCE_INVALID, UNSUPPORTED_PROFILE, OPTIONAL_CANDIDATE_FAILURE, ENVIRONMENT_FAILURE, CANCELLED
    }

    public enum Stage {
        INSPECT, DECODE, RESAMPLE, ENCODE, MUX, VALIDATE
    }

    public static Result normalize(NativeBuffer source, Role role, int transcodedSerial, AtomicBoolean cancelled) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(cancelled, "cancelled");
        var input = BufferArgument.packInput(source);
        var adoption = new Result.Adoption();
        try {
            adoption.output = Objects.requireNonNull(
                nNormalize(input.obj(), input.flags(), role.ordinal(), transcodedSerial, cancelled),
                "Native audio normalization returned null");
            return adoption.finish();
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(cancelled);
        }
    }

    public static Result normalize(NativeBuffer source, Role role, int transcodedSerial) {
        return normalize(source, role, transcodedSerial, new AtomicBoolean());
    }

    public static final class Result extends NativeObject {
        private final int failureKind;
        private final int stage;
        private final int statusCode;
        private final String diagnostic;
        private final int codec;
        private final int role;
        private final int channels;
        private final int sampleRate;
        private final long frames;
        private final long audioPacketBytes;
        private final int serialNumber;
        private final int preSkip;
        private final int outputGain;
        private final ByteBuffer firstPage;
        private final ByteBuffer[] chunks;

        private Result(State state, Output output) {
            super(state);
            this.failureKind = output.failureKind;
            this.stage = output.stage;
            this.statusCode = output.statusCode;
            this.diagnostic = Objects.requireNonNull(output.diagnostic, "diagnostic");
            this.codec = output.codec;
            this.role = output.role;
            this.channels = output.channels;
            this.sampleRate = output.sampleRate;
            this.frames = output.frames;
            this.audioPacketBytes = output.audioPacketBytes;
            this.serialNumber = output.serialNumber;
            this.preSkip = output.preSkip;
            this.outputGain = output.outputGain;
            this.firstPage = output.firstPage;
            this.chunks = Objects.requireNonNull(output.chunks, "chunks");
        }

        public boolean isSuccess() {
            return failureKind < 0;
        }

        public FailureKind failureKind() {
            requireFailure();
            return enumValue(FailureKind.values(), failureKind, "failure kind");
        }

        public Stage stage() {
            requireFailure();
            return enumValue(Stage.values(), stage, "failure stage");
        }

        public int statusCode() {
            requireFailure();
            return statusCode;
        }

        public String diagnostic() {
            requireFailure();
            return diagnostic;
        }

        public Codec codec() {
            requireSuccess();
            return enumValue(Codec.values(), codec, "codec");
        }

        public Role role() {
            requireSuccess();
            return enumValue(Role.values(), role, "role");
        }

        public int channels() {
            requireSuccess();
            return channels;
        }

        public int sampleRate() {
            requireSuccess();
            return sampleRate;
        }

        public long frames() {
            requireSuccess();
            return frames;
        }

        public long audioPacketBytes() {
            requireSuccess();
            return audioPacketBytes;
        }

        public int serialNumber() {
            requireSuccess();
            return serialNumber;
        }

        public int preSkip() {
            requireSuccess();
            return preSkip;
        }

        public int outputGain() {
            requireSuccess();
            return outputGain;
        }

        public ByteBuffer firstPage() {
            requireSuccess();
            return firstPage.asReadOnlyBuffer();
        }

        public ByteBuffer[] chunks() {
            requireSuccess();
            var result = new ByteBuffer[chunks.length];
            for (int index = 0; index < chunks.length; ++index) {
                result[index] = chunks[index].asReadOnlyBuffer();
            }
            return result;
        }

        private void requireSuccess() {
            if (!isSuccess()) {
                throw new IllegalStateException("Audio normalization did not succeed");
            }
            get();
        }

        private void requireFailure() {
            if (isSuccess()) {
                throw new IllegalStateException("Audio normalization succeeded");
            }
        }

        private static <T> T enumValue(T[] values, int ordinal, String field) {
            if (ordinal < 0 || ordinal >= values.length) {
                throw new IllegalStateException("Native audio " + field + " is invalid");
            }
            return values[ordinal];
        }

        private static final class Adoption implements Function<State, Result> {
            private Output output;

            @Override
            public Result apply(State state) {
                return new Result(state, output);
            }

            private Result finish() {
                return adopt(output.ownerHandle, this);
            }
        }
    }

    private record Output(int failureKind, int stage, int statusCode, String diagnostic, long ownerHandle, int codec,
        int role, int channels, int sampleRate, long frames, long audioPacketBytes, int serialNumber, int preSkip,
        int outputGain, ByteBuffer firstPage, ByteBuffer[] chunks) {
    }

    private static native Output nNormalize(Object source, long sourceFlags, int role, int transcodedSerial,
        AtomicBoolean cancelled);
}
