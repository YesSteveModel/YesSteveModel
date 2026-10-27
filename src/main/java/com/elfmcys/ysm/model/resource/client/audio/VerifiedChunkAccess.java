package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Sequential, exact-media access to verified chunks for one playback attempt. */
public final class VerifiedChunkAccess implements AutoCloseable {
    private final ModelFileIdentity representation;
    private final SoundMedia media;
    private final UniBuffer firstChunk;
    private final ChunkReader reader;
    private final Executor ownerExecutor;
    private int nextIndex;
    private int heldChunks;
    private Interest active;
    private boolean closed;

    private VerifiedChunkAccess(ModelFileIdentity representation, SoundMedia media, UniBuffer firstChunk,
        ChunkReader reader, Executor ownerExecutor) {
        this.representation = representation;
        this.media = media;
        this.firstChunk = firstChunk;
        this.reader = reader;
        this.ownerExecutor = ownerExecutor;
    }

    public static VerifiedChunkAccess create(SoundSource source, ModelContent content, RemoteChunkFetcher fetcher,
        Executor workers, Executor ownerExecutor) throws AssetLoadException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        if (!source.representation().equals(content.representation().identity())) {
            throw AssetLoadException.access("Sound access does not match the current model representation");
        }
        var firstChunk = source.acquireFirstChunk();
        try {
            final ChunkReader reader;
            if (content instanceof RemoteModelContent remote) {
                reader = (cancelled, index) -> {
                    if (cancelled.getAsBoolean()) {
                        return CompletableFuture
                            .failedFuture(new CancellationException("Sound chunk request was cancelled"));
                    }
                    return remote.requestSoundChunk(source.media(), index, fetcher, workers);
                };
            } else {
                reader = (cancelled, index) -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return content.chunks().readPayload(cancelled, source.media().chunkInfos().get(index),
                            BufferType.NATIVE);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                }, workers);
            }
            return new VerifiedChunkAccess(source.representation(), source.media(), firstChunk, reader, ownerExecutor);
        } catch (RuntimeException | Error failure) {
            firstChunk.close();
            throw failure;
        }
    }

    public ModelFileIdentity representation() {
        return representation;
    }

    public SoundMedia media() {
        return media;
    }

    public synchronized Interest request(int index) {
        if (closed) {
            throw new IllegalStateException("Sound chunk access is closed");
        }
        if (index != nextIndex) {
            throw new IllegalArgumentException(
                "Sound chunks must be requested in order: expected " + nextIndex + ", got " + index);
        }
        if (active != null) {
            throw new IllegalStateException("A sound chunk request is already active");
        }
        if (heldChunks >= 2) {
            throw new IllegalStateException("Sound chunk window already retains two chunks");
        }
        var interest = new Interest(this, index);
        active = interest;
        nextIndex++;
        executeOwner(interest::start);
        return interest;
    }

    /** Reopens this exact grant only after its current sequential cursor reached EOF. */
    public synchronized VerifiedChunkAccess restart() {
        if (closed) {
            throw new IllegalStateException("Sound chunk access is closed");
        }
        if (active != null || heldChunks != 0 || nextIndex != media.chunkInfos().size()) {
            throw new IllegalStateException("Sound chunk access can restart only after complete consumption");
        }
        var restartedFirst = firstChunk.acquire();
        closed = true;
        firstChunk.close();
        return new VerifiedChunkAccess(representation, media, restartedFirst, reader, ownerExecutor);
    }

    @Override
    public void close() {
        Interest pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = active;
            active = null;
        }
        if (pending != null) {
            pending.cancel();
        }
        firstChunk.close();
    }

    private void executeOwner(Runnable task) {
        try {
            ownerExecutor.execute(task);
        } catch (RuntimeException failure) {
            taskFailed(failure);
        }
    }

    private void taskFailed(Throwable failure) {
        Interest pending;
        synchronized (this) {
            pending = active;
            active = null;
        }
        if (pending != null) {
            pending.fail(failure);
        }
    }

    private void start(Interest interest) {
        final CompletableFuture<UniBuffer> acquisition;
        synchronized (this) {
            if (closed || active != interest || interest.cancelled.get()) {
                interest.fail(new CancellationException("Sound chunk access was cancelled"));
                return;
            }
            acquisition = interest.index == 0 ? CompletableFuture.completedFuture(firstChunk.acquire())
                : reader.read(interest.cancelled::get, interest.index);
            interest.acquisition = acquisition;
        }
        acquisition.whenComplete((buffer, failure) -> completeOnOwner(interest, buffer, failure));
    }

    private void completeOnOwner(Interest interest, UniBuffer buffer, Throwable failure) {
        try {
            ownerExecutor.execute(() -> complete(interest, buffer, failure));
        } catch (RuntimeException rejected) {
            close(buffer);
            synchronized (this) {
                if (active == interest) {
                    active = null;
                }
            }
            interest.fail(rejected);
        }
    }

    private void complete(Interest interest, UniBuffer buffer, Throwable failure) {
        synchronized (this) {
            if (active == interest) {
                active = null;
            }
            if (closed || interest.cancelled.get()) {
                close(buffer);
                interest.fail(new CancellationException("Sound chunk access was cancelled"));
                return;
            }
            if (failure != null) {
                close(buffer);
                interest.fail(unwrap(failure));
                return;
            }
            if (buffer == null) {
                interest.fail(new IllegalStateException("Sound chunk request completed without a buffer"));
                return;
            }
            heldChunks++;
        }
        var chunk = new Chunk(this, interest.index, representation, media.mediaIdentity(), buffer);
        if (!interest.complete(chunk)) {
            chunk.close();
        }
    }

    private void cancel(Interest interest) {
        CompletableFuture<UniBuffer> acquisition;
        boolean releaseFirst;
        synchronized (this) {
            releaseFirst = !closed;
            closed = true;
            if (active == interest) {
                active = null;
            }
            acquisition = interest.acquisition;
        }
        if (acquisition != null) {
            acquisition.cancel(false);
        }
        if (releaseFirst) {
            firstChunk.close();
        }
    }

    private synchronized void release() {
        if (heldChunks <= 0) {
            throw new IllegalStateException("Sound chunk ownership underflow");
        }
        heldChunks--;
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void close(UniBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    @FunctionalInterface
    private interface ChunkReader {
        CompletableFuture<UniBuffer> read(BooleanSupplier cancelled, int index);
    }

    public static final class Interest {
        private final VerifiedChunkAccess owner;
        private final int index;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final CompletableFuture<Chunk> result = new CompletableFuture<>();
        private volatile CompletableFuture<UniBuffer> acquisition;

        private Interest(VerifiedChunkAccess owner, int index) {
            this.owner = owner;
            this.index = index;
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    cancel();
                }
            });
        }

        private void start() {
            owner.start(this);
        }

        public CompletableFuture<Chunk> result() {
            return result;
        }

        public void cancel() {
            if (terminal.compareAndSet(false, true)) {
                cancelled.set(true);
                owner.cancel(this);
                result.completeExceptionally(new CancellationException("Sound chunk interest was cancelled"));
            }
        }

        private void fail(Throwable failure) {
            if (terminal.compareAndSet(false, true)) {
                result.completeExceptionally(failure);
            }
        }

        private boolean complete(Chunk chunk) {
            return terminal.compareAndSet(false, true) && result.complete(chunk);
        }
    }

    public static final class Chunk implements AutoCloseable {
        private final VerifiedChunkAccess owner;
        private final int index;
        private final ModelFileIdentity representation;
        private final Hash256 mediaIdentity;
        private final UniBuffer buffer;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Chunk(VerifiedChunkAccess owner, int index, ModelFileIdentity representation, Hash256 mediaIdentity,
            UniBuffer buffer) {
            this.owner = owner;
            this.index = index;
            this.representation = representation;
            this.mediaIdentity = mediaIdentity;
            this.buffer = Objects.requireNonNull(buffer, "buffer");
        }

        public int index() {
            return index;
        }

        public ModelFileIdentity representation() {
            return representation;
        }

        public Hash256 mediaIdentity() {
            return mediaIdentity;
        }

        public UniBuffer acquireBuffer() {
            if (closed.get()) {
                throw new IllegalStateException("Sound chunk is closed");
            }
            return buffer.acquire();
        }

        /** Borrowed read-only bytes valid until this chunk is closed. */
        public java.nio.ByteBuffer borrowBuffer() {
            if (closed.get()) {
                throw new IllegalStateException("Sound chunk is closed");
            }
            return buffer.nio().asReadOnlyBuffer();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            buffer.close();
            owner.release();
        }
    }
}
