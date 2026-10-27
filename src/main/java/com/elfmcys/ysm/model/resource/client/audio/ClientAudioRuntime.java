package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.client.audio.AudioBackend;
import com.elfmcys.ysm.client.sound.instance.AudioHost;
import com.elfmcys.ysm.client.sound.instance.AudioPlaybackFactory;
import com.elfmcys.ysm.client.sound.instance.ICanStopSound;
import com.elfmcys.ysm.client.sound.instance.PlaybackControl;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.model.views.SoundMedia;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.util.ResourceDiagnostics;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client owner for backend selection, exact playback identity, and default playback resources. */
public final class ClientAudioRuntime implements AutoCloseable {
    private final Executor workers;
    private final AccessResolver accessResolver;
    private final AudioHost host;
    private final @Nullable AudioBackend externalBackend;
    private final AudioRetentionCache retention;
    private final AudioRuntimeCapacity capacity;
    private final Set<Playback> playbacks = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    public ClientAudioRuntime(Executor workers, AccessResolver accessResolver, AudioHost host,
        @Nullable AudioBackend externalBackend) {
        this(workers, accessResolver, host, externalBackend, new AudioRetentionCache(), new AudioRuntimeCapacity());
    }

    ClientAudioRuntime(Executor workers, AccessResolver accessResolver, AudioHost host,
        @Nullable AudioBackend externalBackend, AudioRetentionCache retention, AudioRuntimeCapacity capacity) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.accessResolver = Objects.requireNonNull(accessResolver, "accessResolver");
        this.host = Objects.requireNonNull(host, "host");
        this.externalBackend = externalBackend;
        this.retention = Objects.requireNonNull(retention, "retention");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    public AudioPlaybackFactory createPlayback(SoundSource source) {
        Objects.requireNonNull(source, "source");
        return control -> accept(source, control);
    }

    public void maintain() {
        retention.maintain();
        ArrayList<Playback> active;
        synchronized (this) {
            active = new ArrayList<>(playbacks);
        }
        boolean paused = host.paused();
        active.forEach(playback -> playback.maintainOnOwner(paused));
    }

    public void stopAll() {
        stopAll(AudioBackend.CancelReason.SCOPE_RETIRED);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        stopAll(AudioBackend.CancelReason.RUNTIME_CLOSED);
        retention.close();
    }

    private @Nullable Playback accept(SoundSource source, PlaybackControl control) {
        final Playback playback;
        synchronized (this) {
            if (closed || !capacity.acquireActive()) {
                return null;
            }
            playback = new Playback(source, control);
            playbacks.add(playback);
            ResourceDiagnostics.log("audio accept: playback={} sound={} frames={} chunks={} capacity={}",
                System.identityHashCode(playback), source.media().name(), source.media().frames(),
                source.media().chunkInfos().size(), capacity.snapshot());
        }
        try {
            host.execute(playback::startOnOwner);
        } catch (RuntimeException failure) {
            playback.rejectScheduling(failure);
        }
        return playback;
    }

    private void stopAll(AudioBackend.CancelReason reason) {
        ArrayList<Playback> active;
        synchronized (this) {
            active = new ArrayList<>(playbacks);
        }
        active.forEach(playback -> playback.setStopped(reason));
    }

    private void unregister(Playback playback) {
        synchronized (this) {
            if (playbacks.remove(playback)) {
                capacity.releaseActive();
            }
        }
    }

    private boolean publishPcm(Playback playback, CachedAudio audio, AudioRetentionCache.Receipt receipt) {
        synchronized (this) {
            synchronized (playback) {
                if (closed || !playback.canPublish()) {
                    return false;
                }
                return retention.publish(playback.key, audio, receipt);
            }
        }
    }

    private final class Playback implements ICanStopSound {
        private final SoundSource source;
        private final PlaybackControl control;
        private final AudioCacheKey key;
        private boolean sealed;
        private boolean terminal;
        private boolean preparing;
        private AudioBackend.CancelReason cancelReason;
        private Throwable streamFailure;
        private PlaybackAudioStream stream;
        private AudioHost.HostPlayback hostPlayback;
        private AudioBackend.Playback externalPlayback;
        private ApiChunkAccess externalAccess;

        private Playback(SoundSource source, PlaybackControl control) {
            this.source = source;
            this.control = Objects.requireNonNull(control, "control");
            key = AudioCacheKey.from(source);
        }

        private void startOnOwner() {
            boolean abandoned;
            synchronized (this) {
                abandoned = sealed || terminal;
            }
            if (abandoned) {
                finishOnOwner(false);
                return;
            }
            final VerifiedChunkAccess access;
            try {
                access = accessResolver.resolve(source);
            } catch (Throwable failure) {
                failOnOwner(failure);
                return;
            }
            if (externalBackend == null) {
                startDefault(access);
            } else {
                startExternal(access);
            }
        }

        private void startDefault(VerifiedChunkAccess access) {
            if (!capacity.acquirePreparing()) {
                access.close();
                failOnOwner(AssetLoadException.access("Audio preparation capacity is exhausted"));
                return;
            }
            boolean abandoned;
            synchronized (this) {
                abandoned = sealed || terminal;
                if (!abandoned) {
                    preparing = true;
                }
            }
            if (abandoned) {
                capacity.releasePreparing();
                access.close();
                finishOnOwner(false);
                return;
            }

            final PlaybackAudioStream opened;
            try {
                var lookup = retention.begin(key);
                ResourceDiagnostics.log("audio prepare: playback={} sound={} pcmCacheHit={} looping={}",
                    System.identityHashCode(this), source.media().name(), lookup.audio() != null, control.isLooping());
                opened = new PlaybackAudioStream(source.media(), access, lookup.audio(), control.isLooping(), workers,
                    capacity, lookup.receipt(), this::canPublish, (audio, receipt) -> publishPcm(this, audio, receipt),
                    this::streamClosed, this::streamFailed);
            } catch (Throwable failure) {
                access.close();
                releasePreparing();
                failOnOwner(failure);
                return;
            }
            boolean accepted;
            synchronized (this) {
                accepted = !sealed && !terminal;
                if (accepted) {
                    stream = opened;
                }
            }
            if (!accepted) {
                opened.close();
                releasePreparing();
                finishOnOwner(false);
                return;
            }
            opened.prepared().whenComplete((ignored, failure) -> {
                try {
                    host.execute(() -> preparedOnOwner(opened, failure));
                } catch (RuntimeException rejected) {
                    preparedOnOwner(opened, rejected);
                }
            });
        }

        private void preparedOnOwner(PlaybackAudioStream preparedStream, Throwable failure) {
            boolean cancelled;
            synchronized (this) {
                if (stream != preparedStream || terminal) {
                    return;
                }
                cancelled = sealed;
            }
            if (cancelled) {
                releasePreparing();
                finishOnOwner(true);
                return;
            }
            if (failure != null) {
                releasePreparing();
                failOnOwner(unwrap(failure));
                return;
            }

            final AudioHost.HostPlayback submitted;
            boolean accepted;
            try {
                // Publication uses the same lock order; a synchronous host callback may reenter the runtime.
                synchronized (ClientAudioRuntime.this) {
                    synchronized (this) {
                        cancelled = closed || sealed || terminal || stream != preparedStream;
                        submitted = cancelled ? null : host.submit(preparedStream, control);
                        accepted = submitted != null && !closed && !sealed && !terminal && stream == preparedStream;
                        if (accepted) {
                            hostPlayback = submitted;
                        }
                    }
                }
            } catch (Throwable submitFailure) {
                releasePreparing();
                failOnOwner(submitFailure);
                return;
            }
            releasePreparing();
            if (cancelled) {
                finishOnOwner(true);
                return;
            }
            if (submitted == null) {
                failOnOwner(AssetLoadException.access("Minecraft rejected the prepared model sound"));
                return;
            }
            if (!accepted) {
                submitted.stopPlayback();
                finishOnOwner(true);
            } else {
                ResourceDiagnostics.log("audio host submit: playback={} sound={} capacity={}",
                    System.identityHashCode(this), source.media().name(), capacity.snapshot());
            }
        }

        private void startExternal(VerifiedChunkAccess access) {
            var apiAccess = new ApiChunkAccess(access);
            final AudioBackend.PlayResult result;
            try {
                result =
                    Objects.requireNonNull(externalBackend.play(new AudioBackend.PlaybackRequest(media(source.media()),
                        apiAccess, control.snapshot(host.paused()))), "Audio backend returned no play result");
            } catch (Throwable failure) {
                apiAccess.close();
                failOnOwner(failure);
                return;
            }
            if (result instanceof AudioBackend.Rejected rejected) {
                apiAccess.close();
                failOnOwner(
                    AssetLoadException.access("External audio backend rejected playback: " + rejected.reason()));
                return;
            }
            var accepted = (AudioBackend.Accepted)result;
            var backendPlayback = accepted.playback();
            AudioBackend.CancelReason rejectedReason = null;
            synchronized (this) {
                if (sealed || terminal) {
                    rejectedReason = cancelReason == null ? AudioBackend.CancelReason.AUTHOR_STOP : cancelReason;
                } else {
                    externalAccess = apiAccess;
                    externalPlayback = backendPlayback;
                }
            }
            if (rejectedReason != null) {
                try {
                    backendPlayback.cancel(rejectedReason);
                } catch (Throwable ignored) {
                }
                apiAccess.close();
                finishOnOwner(false);
                return;
            }
            final java.util.concurrent.CompletionStage<AudioBackend.Terminal> terminalStage;
            try {
                terminalStage =
                    Objects.requireNonNull(backendPlayback.terminal(), "Audio backend returned no terminal stage");
            } catch (Throwable failure) {
                failOnOwner(failure);
                return;
            }
            terminalStage.whenComplete((outcome, failure) -> {
                try {
                    host.execute(() -> externalTerminalOnOwner(backendPlayback, outcome, failure));
                } catch (RuntimeException rejected) {
                    externalTerminalOnOwner(backendPlayback, outcome, rejected);
                }
            });
        }

        private void maintainOnOwner(boolean paused) {
            final AudioBackend.Playback external;
            final AudioHost.HostPlayback submitted;
            synchronized (this) {
                if (sealed || terminal) {
                    return;
                }
                external = externalPlayback;
                submitted = hostPlayback;
            }
            if (control.isRetired()) {
                setStopped(AudioBackend.CancelReason.SCOPE_RETIRED);
                return;
            }
            if (external != null) {
                try {
                    external.control(control.snapshot(paused));
                } catch (Throwable failure) {
                    failOnOwner(failure);
                }
            } else if (submitted != null && !submitted.active()) {
                setStopped(AudioBackend.CancelReason.HOST_TERMINATED);
            }
        }

        private void externalTerminalOnOwner(AudioBackend.Playback expected, AudioBackend.Terminal outcome,
            Throwable failure) {
            synchronized (this) {
                if (externalPlayback != expected || terminal) {
                    return;
                }
                if (failure != null || outcome == null || outcome.kind() == AudioBackend.TerminalKind.FAILED) {
                    streamFailure = failure != null ? unwrap(failure)
                        : outcome == null
                            ? new IllegalStateException("External audio backend completed without a terminal result")
                            : outcome.cause() == null ? new IllegalStateException(outcome.detail()) : outcome.cause();
                }
                sealed = true;
            }
            finishOnOwner(false);
        }

        private void streamFailed(Throwable failure) {
            synchronized (this) {
                if (!terminal) {
                    streamFailure = failure;
                }
            }
        }

        private void streamClosed() {
            final boolean stopHost;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                sealed = true;
                stopHost = streamFailure != null || cancelReason != null;
            }
            try {
                host.execute(() -> finishOnOwner(stopHost));
            } catch (RuntimeException failure) {
                finishOnOwner(stopHost);
            }
        }

        private void failOnOwner(Throwable failure) {
            synchronized (this) {
                if (terminal) {
                    return;
                }
                streamFailure = unwrap(failure);
                sealed = true;
                cancelReason = AudioBackend.CancelReason.BACKEND_FAILURE;
            }
            finishOnOwner(true);
        }

        private void rejectScheduling(Throwable failure) {
            synchronized (this) {
                streamFailure = failure;
                sealed = true;
                cancelReason = AudioBackend.CancelReason.BACKEND_FAILURE;
            }
            finishOnOwner(true);
        }

        @Override
        public void setStopped() {
            setStopped(AudioBackend.CancelReason.AUTHOR_STOP);
        }

        @Override
        public void setStopped(AudioBackend.CancelReason reason) {
            final PlaybackAudioStream ownedStream;
            synchronized (this) {
                if (sealed || terminal) {
                    return;
                }
                sealed = true;
                cancelReason = Objects.requireNonNull(reason, "reason");
                ownedStream = stream;
            }
            if (ownedStream != null) {
                ownedStream.close();
            }
            try {
                host.execute(() -> cancelOnOwner(reason));
            } catch (RuntimeException failure) {
                cancelOnOwner(reason);
            }
        }

        @Override
        public synchronized boolean isPlaybackStopped() {
            return sealed || terminal;
        }

        private void cancelOnOwner(AudioBackend.CancelReason reason) {
            synchronized (this) {
                if (terminal) {
                    return;
                }
                cancelReason = reason;
            }
            finishOnOwner(true);
        }

        private void finishOnOwner(boolean stopHost) {
            final PlaybackAudioStream ownedStream;
            final AudioHost.HostPlayback submitted;
            final AudioBackend.Playback external;
            final ApiChunkAccess chunks;
            final AudioBackend.CancelReason reason;
            final Throwable failure;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                terminal = true;
                sealed = true;
                ownedStream = stream;
                stream = null;
                submitted = hostPlayback;
                hostPlayback = null;
                external = externalPlayback;
                chunks = externalAccess;
                externalAccess = null;
                externalPlayback = null;
                reason = cancelReason == null ? AudioBackend.CancelReason.BACKEND_FAILURE : cancelReason;
                failure = streamFailure;
            }
            releasePreparing();
            if (stopHost && external != null) {
                try {
                    external.cancel(reason);
                } catch (Throwable cancellationFailure) {
                    YesSteveModel.LOGGER.debug("External model audio backend cancel failed: {}", source.media().name(),
                        cancellationFailure);
                }
            }
            if (chunks != null) {
                chunks.close();
            }
            if (ownedStream != null) {
                ownedStream.close();
            }
            if (stopHost && submitted != null) {
                submitted.stopPlayback();
            }
            unregister(this);
            ResourceDiagnostics.log("audio terminal: playback={} sound={} reason={} failure={} capacity={}",
                System.identityHashCode(this), source.media().name(), cancelReason == null ? "completed" : reason,
                failure, capacity.snapshot());
            if (failure != null) {
                YesSteveModel.LOGGER.debug("Model audio playback failed: {}", source.media().name(), failure);
            }
        }

        private void releasePreparing() {
            synchronized (this) {
                if (!preparing) {
                    return;
                }
                preparing = false;
            }
            capacity.releasePreparing();
        }

        private synchronized boolean canPublish() {
            return !sealed && !terminal;
        }
    }

    private static AudioBackend.Media media(SoundMedia media) {
        return new AudioBackend.Media(media.mediaIdentity().bytes(), media.channels(), media.frames(), media.preSkip(),
            media.outputGain(), media.firstPage(), media.chunkInfos().stream().map(chunk -> chunk.size()).toList());
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    public interface AccessResolver {
        VerifiedChunkAccess resolve(SoundSource source) throws AssetLoadException;
    }

    private static final class ApiChunkAccess implements AudioBackend.ChunkAccess {
        private VerifiedChunkAccess delegate;
        private final Set<ApiChunk> chunks = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;

        private ApiChunkAccess(VerifiedChunkAccess delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public synchronized int chunkCount() {
            requireOpen();
            return delegate.media().chunkInfos().size();
        }

        @Override
        public CompletableFuture<AudioBackend.Chunk> request(int index) {
            final VerifiedChunkAccess.Interest interest;
            synchronized (this) {
                requireOpen();
                interest = delegate.request(index);
            }
            return interest.result().thenApply(chunk -> {
                synchronized (this) {
                    if (closed) {
                        chunk.close();
                        throw new java.util.concurrent.CancellationException("Audio chunk access is closed");
                    }
                    var exposed = new ApiChunk(this, chunk);
                    chunks.add(exposed);
                    return exposed;
                }
            });
        }

        @Override
        public synchronized AudioBackend.ChunkAccess restart() {
            requireOpen();
            if (!chunks.isEmpty()) {
                throw new IllegalStateException("All audio chunks must be closed before restart");
            }
            delegate = delegate.restart();
            return this;
        }

        @Override
        public void close() {
            ArrayList<ApiChunk> delivered;
            VerifiedChunkAccess access;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                delivered = new ArrayList<>(chunks);
                chunks.clear();
                access = delegate;
            }
            delivered.forEach(ApiChunk::closeDelegate);
            access.close();
        }

        private synchronized void released(ApiChunk chunk) {
            chunks.remove(chunk);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Audio chunk access is closed");
            }
        }
    }

    private static final class ApiChunk implements AudioBackend.Chunk {
        private final ApiChunkAccess owner;
        private final VerifiedChunkAccess.Chunk delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ApiChunk(ApiChunkAccess owner, VerifiedChunkAccess.Chunk delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public int index() {
            return delegate.index();
        }

        @Override
        public ByteBuffer data() {
            if (closed.get()) {
                throw new IllegalStateException("Audio chunk is closed");
            }
            return delegate.borrowBuffer();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
                owner.released(this);
            }
        }

        private void closeDelegate() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }
}
