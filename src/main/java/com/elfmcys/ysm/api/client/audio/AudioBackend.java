package com.elfmcys.ysm.api.client.audio;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Complete owner for one model-audio playback.
 *
 * <p>
 * The backend is selected once during client runtime construction. An accepted playback owns decoding, output,
 * cancellation, and exactly one terminal result. Chunk access remains restricted to the exact immutable media supplied
 * with the request.
 * </p>
 */
public interface AudioBackend {
    PlayResult play(PlaybackRequest request);

    sealed interface PlayResult permits Accepted, Rejected {}

    record Accepted(Playback playback) implements PlayResult {
        public Accepted {
            Objects.requireNonNull(playback, "playback");
        }
    }

    record Rejected(String reason) implements PlayResult {
        public Rejected {
            if (Objects.requireNonNull(reason, "reason").isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    interface Playback {
        /** Applies one full control snapshot in caller order. */
        void control(Control control);

        /** Seals new output effects before returning. Repeated cancellation is harmless. */
        void cancel(CancelReason reason);

        /** Returns the exact playback's single terminal result. */
        CompletionStage<Terminal> terminal();
    }

    interface ChunkAccess extends AutoCloseable {
        int chunkCount();

        CompletionStage<Chunk> request(int index);

        /** Starts the same media from the beginning after the current cursor reached EOF. */
        ChunkAccess restart();

        @Override
        void close();
    }

    interface Chunk extends AutoCloseable {
        int index();

        /** Borrowed read-only bytes valid until this chunk is closed. */
        ByteBuffer data();

        @Override
        void close();
    }

    record PlaybackRequest(Media media, ChunkAccess chunks, Control initialControl) {
        public PlaybackRequest {
            Objects.requireNonNull(media, "media");
            Objects.requireNonNull(chunks, "chunks");
            Objects.requireNonNull(initialControl, "initialControl");
            if (media.chunkSizes().size() != chunks.chunkCount()) {
                throw new IllegalArgumentException("media and chunk access disagree");
            }
        }
    }

    record Control(float authorVolume, float localGain, float pitch, double x, double y, double z, boolean relative,
        boolean looping, boolean paused) {
        public Control {
            if (!Float.isFinite(authorVolume) || authorVolume < 0 || !Float.isFinite(localGain) || localGain < 0
                || !Float.isFinite(pitch) || pitch <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) {
                throw new IllegalArgumentException("invalid audio control snapshot");
            }
        }
    }

    enum CancelReason {
        AUTHOR_STOP, FORCE_REPLACED, SCOPE_RETIRED, HOST_TERMINATED, BACKEND_FAILURE, RUNTIME_CLOSED
    }

    enum TerminalKind {
        COMPLETED, FAILED, CANCELLED
    }

    record Terminal(TerminalKind kind, String detail, @Nullable Throwable cause) {
        public Terminal {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }

        public static Terminal completed() {
            return new Terminal(TerminalKind.COMPLETED, "completed", null);
        }

        public static Terminal failed(String detail, Throwable cause) {
            return new Terminal(TerminalKind.FAILED, detail, Objects.requireNonNull(cause, "cause"));
        }

        public static Terminal cancelled(CancelReason reason) {
            return new Terminal(TerminalKind.CANCELLED, Objects.requireNonNull(reason, "reason").name(), null);
        }
    }

    /** Immutable media description; returned buffers and arrays are defensive views/copies. */
    final class Media {
        private final byte[] identity;
        private final int channels;
        private final long frames;
        private final int preSkip;
        private final int outputGain;
        private final byte[] firstPage;
        private final List<Integer> chunkSizes;

        public Media(byte[] identity, int channels, long frames, int preSkip, int outputGain, ByteBuffer firstPage,
            List<Integer> chunkSizes) {
            this.identity = Objects.requireNonNull(identity, "identity").clone();
            this.channels = channels;
            this.frames = frames;
            this.preSkip = preSkip;
            this.outputGain = outputGain;
            var page = Objects.requireNonNull(firstPage, "firstPage").duplicate();
            this.firstPage = new byte[page.remaining()];
            page.get(this.firstPage);
            this.chunkSizes = List.copyOf(Objects.requireNonNull(chunkSizes, "chunkSizes"));
            if (this.identity.length == 0 || (channels != 1 && channels != 2) || frames <= 0
                || this.firstPage.length == 0 || this.chunkSizes.isEmpty()
                || this.chunkSizes.stream().anyMatch(size -> size == null || size <= 0)) {
                throw new IllegalArgumentException("invalid audio media description");
            }
        }

        public byte[] identity() {
            return identity.clone();
        }

        public int channels() {
            return channels;
        }

        public int sampleRate() {
            return 48_000;
        }

        public long frames() {
            return frames;
        }

        public int preSkip() {
            return preSkip;
        }

        public int outputGain() {
            return outputGain;
        }

        public ByteBuffer firstPage() {
            return ByteBuffer.wrap(firstPage).asReadOnlyBuffer();
        }

        public List<Integer> chunkSizes() {
            return chunkSizes;
        }
    }
}
