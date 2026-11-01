package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.natives.sound.OpusDecoder;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One default-backend playback owner. Workers decode into bounded ready PCM; host reads never perform decoding,
 * resource I/O, or waits.
 */
final class PlaybackAudioStream implements CustomAudioStream {
    static final int MONO_PCM_BYTES_PER_SECOND = 48_000 * Short.BYTES;
    private static final int DECODE_BYTES = 64 * 1024;
    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0).asReadOnlyBuffer();

    private final boolean looping;
    private final AudioFormat format;
    private final int initialPcmBytes;
    private final int readyHighBytes;
    private final Executor workers;
    private final AudioRuntimeCapacity capacity;
    private final SoundMedia media;
    private final AudioRetentionCache.Receipt receipt;
    private final BooleanSupplier publicationEligible;
    private final PcmPublisher publisher;
    private final Runnable closedCallback;
    private final Consumer<Throwable> failureCallback;
    private final CompletableFuture<Void> prepared = new CompletableFuture<>();
    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();

    private VerifiedChunkAccess access;
    private OpusDecoder decoder;
    private NativeBuffer scratch;
    private NativeBuffer header;
    private ByteBuffer input;
    private VerifiedChunkAccess.Chunk inputChunk;
    private long inputReservation;
    private VerifiedChunkAccess.Interest pendingInterest;
    private long pendingReservation;
    private int nextChunk;
    private boolean inputEnded;

    private ArrayList<byte[]> candidate;
    private long candidateReservation;
    private CachedAudio cachedAudio;
    private int cachedOffset;
    private long cachedReservation;
    private boolean skipFirstChunk;

    private int readyOffset;
    private int readyBytes;
    private long readyReservation;
    private boolean sourceComplete;
    private boolean preparedSignalled;
    private boolean closed;
    private boolean cleanupDone;
    private boolean workerScheduled;
    private boolean workerRunning;
    private boolean workerAgain;

    PlaybackAudioStream(PcmAudio pcm, boolean looping, AudioRuntimeCapacity capacity, Runnable closedCallback,
        Consumer<Throwable> failureCallback) throws IOException {
        this.looping = looping;
        this.format = pcm.format();
        this.initialPcmBytes = secondsOfPcm(4, format.getChannels());
        this.readyHighBytes = secondsOfPcm(5, format.getChannels());
        this.workers = null;
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.media = null;
        this.access = null;
        this.receipt = null;
        this.publicationEligible = null;
        this.publisher = null;
        this.closedCallback = Objects.requireNonNull(closedCallback, "closedCallback");
        this.failureCallback = Objects.requireNonNull(failureCallback, "failureCallback");
        this.cachedAudio = new CachedAudio.Complete(Objects.requireNonNull(pcm, "pcm"));
        if (!capacity.reserveDecoded(pcm.size())) {
            throw AssetLoadException.access("Active cached PCM capacity is exhausted");
        }
        this.cachedReservation = pcm.size();
        this.sourceComplete = true;
        this.preparedSignalled = true;
        prepared.complete(null);
    }

    PlaybackAudioStream(SoundMedia media, VerifiedChunkAccess access, boolean looping, Executor workers,
        AudioRuntimeCapacity capacity, AudioRetentionCache.Receipt receipt, BooleanSupplier publicationEligible,
        PcmPublisher publisher, Runnable closedCallback, Consumer<Throwable> failureCallback) throws IOException {
        this(media, access, null, looping, workers, capacity, receipt, publicationEligible, publisher, closedCallback,
            failureCallback);
    }

    PlaybackAudioStream(SoundMedia media, VerifiedChunkAccess access, @Nullable CachedAudio cached, boolean looping,
        Executor workers, AudioRuntimeCapacity capacity, AudioRetentionCache.Receipt receipt,
        BooleanSupplier publicationEligible, PcmPublisher publisher, Runnable closedCallback,
        Consumer<Throwable> failureCallback) throws IOException {
        this.looping = looping;
        this.format = new AudioFormat(48_000, 16, 1, true, false);
        this.initialPcmBytes = secondsOfPcm(4, 1);
        this.readyHighBytes = secondsOfPcm(5, 1);
        this.workers = cached instanceof CachedAudio.Complete ? null : Objects.requireNonNull(workers, "workers");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.media = Objects.requireNonNull(media, "media");
        this.access = Objects.requireNonNull(access, "access");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.publicationEligible = Objects.requireNonNull(publicationEligible, "publicationEligible");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.closedCallback = Objects.requireNonNull(closedCallback, "closedCallback");
        this.failureCallback = Objects.requireNonNull(failureCallback, "failureCallback");

        if (cached != null) {
            if (!capacity.reserveDecoded(cached.retainedBytes())) {
                throw AssetLoadException.access("Active cached audio capacity is exhausted");
            }
            cachedAudio = cached;
            cachedReservation = cached.retainedBytes();
            if (cached instanceof CachedAudio.Complete) {
                access.close();
                this.access = null;
                sourceComplete = true;
                preparedSignalled = true;
                prepared.complete(null);
                return;
            }
        } else {
            candidate = new ArrayList<>();
        }
        requestWorker();
    }

    CompletableFuture<Void> prepared() {
        return prepared;
    }

    @Override
    public @NotNull AudioFormat getFormat() {
        return format;
    }

    @Override
    public @NotNull ByteBuffer read(int size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (size == 0) {
            return EMPTY;
        }
        int frameSize = format.getFrameSize();
        if (size < frameSize) {
            throw new IllegalArgumentException("size must hold at least one PCM frame");
        }
        int requested = size - size % frameSize;
        byte[] output = null;
        boolean eof = false;
        boolean starved = false;
        synchronized (this) {
            if (closed) {
                return EMPTY;
            }
            if (readyBytes > 0) {
                int length = Math.min(requested, readyBytes);
                output = new byte[length];
                int written = 0;
                while (written < length) {
                    var first = ready.peekFirst();
                    int copied = Math.min(length - written, first.length - readyOffset);
                    System.arraycopy(first, readyOffset, output, written, copied);
                    written += copied;
                    readyOffset += copied;
                    readyBytes -= copied;
                    if (readyOffset == first.length) {
                        ready.removeFirst();
                        readyOffset = 0;
                        readyReservation -= first.length;
                        capacity.releaseDecoded(first.length);
                    }
                }
            } else if (cachedAudio instanceof CachedAudio.Complete complete) {
                var pcm = complete.pcm();
                int available = pcm.size() - cachedOffset;
                if (!looping && available == 0) {
                    eof = true;
                } else {
                    int outputLength = looping ? requested : Math.min(requested, available);
                    output = new byte[outputLength];
                    int written = 0;
                    while (written < output.length) {
                        int copied = Math.min(output.length - written, pcm.size() - cachedOffset);
                        pcm.read(cachedOffset, copied).get(output, written, copied);
                        written += copied;
                        cachedOffset += copied;
                        if (cachedOffset == pcm.size()) {
                            if (looping) {
                                cachedOffset = 0;
                            } else {
                                sourceComplete = true;
                            }
                        }
                    }
                }
            } else if (sourceComplete) {
                eof = true;
            } else {
                starved = true;
            }
        }

        if (output != null) {
            if (readyBelowLowWater()) {
                requestWorker();
            }
            var direct = BufferUtils.createByteBuffer(output.length);
            direct.put(output).flip();
            return direct;
        }
        if (eof) {
            finish(null);
            return EMPTY;
        }
        if (starved) {
            var failure = AssetLoadException.access("Model audio playback starved before a non-blocking host read");
            finish(failure);
            throw failure;
        }
        return EMPTY;
    }

    @Override
    public void close() {
        finish(null);
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    private void requestWorker() {
        Executor executor;
        synchronized (this) {
            if (workers == null || cleanupDone) {
                return;
            }
            if (workerRunning) {
                workerAgain = true;
                return;
            }
            if (workerScheduled) {
                return;
            }
            workerScheduled = true;
            executor = workers;
        }
        try {
            executor.execute(this::runWorker);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                workerScheduled = false;
            }
            finish(failure);
            cleanupResources();
        }
    }

    private void runWorker() {
        synchronized (this) {
            workerScheduled = false;
            if (workerRunning) {
                workerAgain = true;
                return;
            }
            workerRunning = true;
        }
        try {
            if (isClosed()) {
                return;
            }
            workLoop();
        } catch (Throwable failure) {
            finish(unwrap(failure));
        } finally {
            boolean again;
            synchronized (this) {
                workerRunning = false;
                again = workerAgain;
                workerAgain = false;
            }
            if (isClosed()) {
                cleanupResources();
            } else if (again) {
                requestWorker();
            }
        }
    }

    private void workLoop() throws IOException {
        while (!isClosed()) {
            int demand = readySpace();
            if (sourceComplete || cachedAudio instanceof CachedAudio.Complete || demand == 0) {
                return;
            }
            if (consumePcm(demand) < demand) {
                return;
            }
        }
    }

    // A partial supply means input is pending, not EOF. The pipeline owns the next demand.
    int consumePcm(int requestedBytes) throws IOException {
        if (requestedBytes < 0) {
            throw new IllegalArgumentException("PCM demand must not be negative");
        }
        int demand = Math.min(requestedBytes - requestedBytes % Short.BYTES, readySpace());
        int supplied = 0;
        while (supplied < demand && !isClosed() && !sourceComplete) {
            if (cachedAudio instanceof CachedAudio.Prefix prefix && cachedOffset < prefix.pcm().size()) {
                int bytes = Math.min(demand - supplied, prefix.pcm().size() - cachedOffset);
                publishReady(prefix.pcm().read(cachedOffset, bytes), bytes, false);
                cachedOffset += bytes;
                supplied += bytes;
                continue;
            }
            if (!ensureCycle()) {
                return supplied;
            }
            if (skipFirstChunk) {
                if (inputChunk == null && !requestNextChunk()) {
                    return supplied;
                }
                if (isClosed()) {
                    return supplied;
                }
                releaseInput();
                skipFirstChunk = false;
                continue;
            }

            var output = scratch.nio();
            output.clear();
            output.limit(Math.min(output.capacity(), demand - supplied));
            int decoded = decoder.decode(output);
            if (decoded > 0) {
                publishReady(output, decoded, true);
                supplied += decoded;
                continue;
            }
            if (decoded == 0) {
                completeCycle();
                continue;
            }
            if (decoded != OpusDecoder.NEED_INPUT) {
                throw AssetLoadException.content("Invalid Ogg Opus playback stream");
            }

            synchronized (this) {
                // The completion callback publishes input under this monitor; an in-flight request is not EOF.
                if (pendingInterest != null) {
                    return supplied;
                }
            }

            if (input != null && input.hasRemaining()) {
                if (decoder.feed(input) == 0) {
                    throw AssetLoadException.content("Opus playback decoder made no input progress");
                }
                continue;
            }
            if (inputChunk != null && inputChunk.index() == 0 && media.chunkInfos().size() > 1) {
                capturePrefix();
            }
            releaseInput();
            if (nextChunk < media.chunkInfos().size()) {
                if (!requestNextChunk()) {
                    return supplied;
                }
                continue;
            }
            if (!inputEnded) {
                decoder.endInput();
                inputEnded = true;
                continue;
            }
            throw AssetLoadException.content("Opus playback decoder made no progress after end of input");
        }
        return supplied;
    }

    private boolean ensureCycle() {
        if (decoder != null) {
            return !isClosed();
        }
        if (isClosed()) {
            return false;
        }
        decoder = cachedAudio instanceof CachedAudio.Prefix prefix ? prefix.checkpoint().restore()
            : OpusDecoder.create(media.frames());
        if (isClosed()) {
            return false;
        }
        scratch = NativeBuffer.allocate(DECODE_BYTES);
        if (cachedAudio instanceof CachedAudio.Prefix) {
            skipFirstChunk = true;
        } else {
            header = NativeBuffer.copyOf(media.firstPage());
            input = header.nio();
        }
        nextChunk = 0;
        inputEnded = false;
        return !isClosed();
    }

    private boolean requestNextChunk() throws IOException {
        if (pendingInterest != null || inputChunk != null) {
            return input != null;
        }
        int index = nextChunk;
        long bytes = Integer.toUnsignedLong(media.chunkInfos().get(index).size());
        if (!capacity.reserveEncoded(bytes)) {
            throw AssetLoadException.access("Active encoded audio capacity is exhausted");
        }
        final VerifiedChunkAccess.Interest requested;
        try {
            requested = access.request(index);
        } catch (RuntimeException | Error failure) {
            capacity.releaseEncoded(bytes);
            throw failure;
        }
        synchronized (this) {
            if (closed) {
                capacity.releaseEncoded(bytes);
                // A synchronous completion has already transferred chunk ownership to the
                // result, so cancellation alone cannot reclaim an unadopted chunk here.
                requested.result().whenComplete((chunk, failure) -> {
                    if (chunk != null) {
                        chunk.close();
                    }
                });
                requested.cancel();
                return false;
            }
            pendingInterest = requested;
            pendingReservation = bytes;
            nextChunk++;
        }
        requested.result().whenComplete((chunk, failure) -> chunkCompleted(requested, chunk, failure));
        synchronized (this) {
            return pendingInterest == null && inputChunk != null;
        }
    }

    private void chunkCompleted(VerifiedChunkAccess.Interest requested, VerifiedChunkAccess.Chunk chunk,
        Throwable failure) {
        long reservation;
        boolean accepted;
        synchronized (this) {
            if (pendingInterest != requested) {
                reservation = 0;
                accepted = false;
            } else {
                pendingInterest = null;
                reservation = pendingReservation;
                pendingReservation = 0;
                accepted = !closed && failure == null && chunk != null;
                if (accepted) {
                    inputChunk = chunk;
                    inputReservation = reservation;
                    input = chunk.borrowBuffer();
                }
            }
        }
        if (!accepted) {
            if (chunk != null) {
                chunk.close();
            }
            if (reservation != 0) {
                capacity.releaseEncoded(reservation);
            }
            if (failure != null && !isClosed()) {
                finish(unwrap(failure));
                return;
            }
        }
        requestWorker();
    }

    private void publishReady(ByteBuffer output, int decoded, boolean collect) throws IOException {
        if (!capacity.reserveDecoded(decoded)) {
            discardCandidate();
            if (!capacity.reserveDecoded(decoded)) {
                throw AssetLoadException.access("Ready PCM capacity is exhausted");
            }
        }
        byte[] bytes;
        try {
            bytes = new byte[decoded];
            output.position(0).limit(decoded);
            output.get(bytes);
        } catch (RuntimeException | Error failure) {
            capacity.releaseDecoded(decoded);
            throw failure;
        }
        boolean signalPrepared = false;
        synchronized (this) {
            if (closed) {
                capacity.releaseDecoded(decoded);
                return;
            }
            try {
                ready.addLast(bytes);
            } catch (RuntimeException | Error failure) {
                capacity.releaseDecoded(decoded);
                throw failure;
            }
            readyBytes += decoded;
            readyReservation += decoded;
            if (!preparedSignalled && readyBytes >= initialPcmBytes) {
                preparedSignalled = true;
                signalPrepared = true;
            }
        }
        if (signalPrepared) {
            prepared.complete(null);
        }
        if (collect && candidate != null && !isClosed()) {
            if (capacity.reserveDecoded(decoded)) {
                candidateReservation += decoded;
                candidate.add(bytes.clone());
            } else {
                discardCandidate();
            }
        }
    }

    private void capturePrefix() {
        if (candidate == null || isClosed()) {
            return;
        }
        long bytes = decoder.checkpointSize();
        if (!capacity.reserveDecoded(bytes)) {
            discardCandidate();
            return;
        }
        candidateReservation += bytes;
        var checkpoint = decoder.checkpoint();
        var prefix = new CachedAudio.Prefix(new PcmAudio(candidate, format), checkpoint);
        publishCandidate(prefix);
    }

    private void publishCandidate(CachedAudio audio) {
        long reservation = candidateReservation;
        candidate = null;
        candidateReservation = 0;
        try {
            if (!isClosed() && publicationEligible.getAsBoolean()) {
                publisher.publish(audio, receipt);
            }
        } finally {
            boolean retained;
            synchronized (this) {
                retained = looping && !closed;
                if (retained) {
                    // A host read must not advance the new cursor before its initial offset is installed.
                    cachedAudio = audio;
                    cachedReservation = reservation;
                    cachedOffset = audio instanceof CachedAudio.Prefix ? audio.pcm().size() : 0;
                }
            }
            if (!retained) {
                capacity.releaseDecoded(reservation);
            }
        }
    }

    private void discardCandidate() {
        candidate = null;
        capacity.releaseDecoded(candidateReservation);
        candidateReservation = 0;
    }

    private void completeCycle() throws IOException {
        if (!inputEnded || nextChunk != media.chunkInfos().size()) {
            throw AssetLoadException.content("Opus playback ended before all declared chunks were validated");
        }
        releaseInput();
        if (candidate != null) {
            if (candidateReservation != Math.multiplyExact(media.frames(), Short.BYTES)) {
                throw AssetLoadException.content("Decoded PCM does not match the declared frame count");
            }
            publishCandidate(new CachedAudio.Complete(new PcmAudio(candidate, format)));
        }

        closeDecoderCycle();
        if (looping && !(cachedAudio instanceof CachedAudio.Complete) && !isClosed()) {
            access = access.restart();
            cachedOffset = 0;
            return;
        }
        access.close();
        access = null;
        boolean signalPrepared = false;
        synchronized (this) {
            sourceComplete = true;
            if (!preparedSignalled && readyBytes > 0) {
                preparedSignalled = true;
                signalPrepared = true;
            }
        }
        if (signalPrepared) {
            prepared.complete(null);
        }
    }

    private void closeDecoderCycle() {
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
        if (scratch != null) {
            scratch.close();
            scratch = null;
        }
        if (header != null) {
            header.close();
            header = null;
        }
        input = null;
        inputEnded = false;
        skipFirstChunk = false;
    }

    private void releaseInput() {
        input = null;
        if (header != null) {
            header.close();
            header = null;
        }
        if (inputChunk != null) {
            inputChunk.close();
            inputChunk = null;
        }
        if (inputReservation != 0) {
            capacity.releaseEncoded(inputReservation);
            inputReservation = 0;
        }
    }

    private void finish(Throwable failure) {
        boolean notify;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            notify = true;
        }
        if (!prepared.isDone()) {
            prepared.completeExceptionally(
                failure == null ? new CancellationException("Audio playback was closed") : failure);
        }
        if (workers == null) {
            cleanupResources();
        } else {
            requestWorker();
        }
        if (failure != null) {
            failureCallback.accept(failure);
        }
        if (notify) {
            closedCallback.run();
        }
    }

    private void cleanupResources() {
        VerifiedChunkAccess.Interest interest;
        long pendingBytes;
        long queuedBytes;
        long candidateBytes;
        long cachedBytes;
        synchronized (this) {
            if (cleanupDone) {
                return;
            }
            cleanupDone = true;
            interest = pendingInterest;
            pendingInterest = null;
            pendingBytes = pendingReservation;
            pendingReservation = 0;
            queuedBytes = readyReservation;
            readyReservation = 0;
            readyBytes = 0;
            readyOffset = 0;
            ready.clear();
            candidateBytes = candidateReservation;
            candidateReservation = 0;
            candidate = null;
            cachedBytes = cachedReservation;
            cachedReservation = 0;
            cachedAudio = null;
        }
        if (interest != null) {
            interest.cancel();
        }
        if (pendingBytes != 0) {
            capacity.releaseEncoded(pendingBytes);
        }
        releaseInput();
        if (access != null) {
            access.close();
            access = null;
        }
        closeDecoderCycle();
        if (queuedBytes != 0) {
            capacity.releaseDecoded(queuedBytes);
        }
        if (candidateBytes != 0) {
            capacity.releaseDecoded(candidateBytes);
        }
        if (cachedBytes != 0) {
            capacity.releaseDecoded(cachedBytes);
        }
        ResourceDiagnostics.log("audio stream cleanup: stream={} sound={} decoderClosed={} accessClosed={} capacity={}",
            System.identityHashCode(this), media == null ? "cached-pcm" : media.name(), decoder == null, access == null,
            capacity.snapshot());
    }

    private synchronized boolean readyBelowLowWater() {
        return readyBytes < initialPcmBytes && !sourceComplete && !closed;
    }

    private synchronized int readySpace() {
        return Math.max(0, readyHighBytes - readyBytes);
    }

    private static int secondsOfPcm(int seconds, int channels) {
        return Math.multiplyExact(Math.multiplyExact(seconds, MONO_PCM_BYTES_PER_SECOND), channels);
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    interface PcmPublisher {
        boolean publish(CachedAudio audio, AudioRetentionCache.Receipt receipt);
    }
}
