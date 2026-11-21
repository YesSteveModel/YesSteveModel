package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.SharedBuffer;
import com.elfmcys.ysm.api.client.audio.AudioBackend;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.client.sound.instance.AudioHost;
import com.elfmcys.ysm.client.sound.instance.ICanStopSound;
import com.elfmcys.ysm.client.sound.instance.PlaybackControl;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.TargetSoundResources;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.elfmcys.ysm.model.resource.client.audio.AudioPlaybackFixture.nativeCall;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class ClientAudioRuntimeIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void sharedTargetRetirementKeepsAcceptedPlaybacksUntilTheirRuntimeStops() throws Exception {
        for (boolean shutdown : new boolean[] {false, true}) {
            try (
                var fixture = AudioPlaybackFixture.create(temp.resolve("shared-" + shutdown),
                    "/audio-playback/stereo-over-five.ogg");
                var prepared = TargetSoundResources.prepare(() -> false, fixture.content.representation().identity(),
                    java.util.Map.of("tone", fixture.media), fixture.content.chunks());
                var first = prepared.acquire(); var second = prepared.acquire()) {
                var a = first.bind(fixture.media);
                var b = second.bind(fixture.media);
                var host = new FakeHost();
                var capacity = new AudioRuntimeCapacity();
                try (var runtime = new ClientAudioRuntime(Runnable::run,
                    source -> VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, Runnable::run),
                    host, null, new AudioRetentionCache(), capacity)) {
                    var firstPlayback = play(runtime, a, false);
                    var firstStream = host.removeStream();
                    var secondPlayback = play(runtime, b, false);
                    var secondStream = host.removeStream();
                    prepared.close();
                    first.close();
                    {
                        var bytes = b.acquireFirstChunk();
                        assertTrue(bytes.size() > 0);
                    }
                    second.close();
                    assertFalse(firstPlayback.isPlaybackStopped());
                    assertFalse(secondPlayback.isPlaybackStopped());
                    assertEquals(2, firstStream.read(2).remaining());
                    assertEquals(4, secondStream.read(4).remaining());
                    if (shutdown)
                        runtime.close();
                    else
                        runtime.stopAll();
                    assertTrue(firstPlayback.isPlaybackStopped());
                    assertTrue(secondPlayback.isPlaybackStopped());
                    assertEquals(2, host.stops.get());
                    assertFalse(firstStream.read(2).hasRemaining());
                    assertFalse(secondStream.read(2).hasRemaining());
                    assertIdle(capacity);
                }
            }
        }
    }

    @Test
    void authorStopInsideRealRestoreSealsPreparationAndSubmittedPlayback() throws Exception {
        for (boolean submitted : new boolean[] {false, true}) {
            String resource = submitted ? "/audio-playback/stereo-over-five.ogg" : "/audio-fixtures/opus-long.ogg";
            try (var fixture = AudioPlaybackFixture.create(temp.resolve(Boolean.toString(submitted)), resource)) {
                var cached = fixture.prefix();
                var retention = new AudioRetentionCache();
                var key = AudioCacheKey.from(fixture.source);
                assertTrue(retention.publish(key, cached, retention.begin(key).receipt()));
                var capacity = new AudioRuntimeCapacity();
                var host = new FakeHost();
                var workers = Executors.newSingleThreadExecutor();
                long creates = AudioPlaybackFixture.nativeCount("nCreateCount");
                long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
                nativeCall("nPauseNextRestore");
                try (var runtime = new ClientAudioRuntime(workers, source -> fixture.access(Runnable::run), host, null,
                    retention, capacity)) {
                    var playback = play(runtime, fixture.source, false);
                    CustomAudioStream stream = null;
                    if (submitted) {
                        workers.submit(() -> {
                        }).get(5, TimeUnit.SECONDS);
                        stream = host.removeStream();
                        assertEquals(restores, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                        assertEquals(200_000, stream.read(200_000).remaining());
                    }
                    awaitPausedRestore();
                    assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    assertEquals(creates, AudioPlaybackFixture.nativeCount("nCreateCount"));
                    assertTrue(fixture.reads.isEmpty(),
                        "A paused restore must not request even the resident chunk token");
                    playback.setStopped();
                    assertTrue(playback.isPlaybackStopped());
                    nativeCall("nResumeRestore");
                    workers.submit(() -> {
                    }).get(5, TimeUnit.SECONDS);
                    assertEquals(submitted ? 1 : 0, host.submits.get());
                    assertEquals(submitted ? 1 : 0, host.stops.get());
                    if (submitted) {
                        assertFalse(stream.read(2).hasRemaining());
                    }
                    assertTrue(fixture.reads.isEmpty());
                    assertIdle(capacity);
                    assertSame(cached, retention.begin(key).audio());
                } finally {
                    nativeCall("nResumeRestore");
                    workers.shutdown();
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static void awaitPausedRestore() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(boolean)nativeCall("nRestorePaused")) {
            assertTrue(System.nanoTime() < deadline, "The real restore entry did not reach its test gate");
            Thread.sleep(1);
        }
    }

    @Test
    void hotPrefixSurvivesEvictionAndEveryNewTriggerRechecksAccess() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/stereo-over-five.ogg")) {
            var prefix = fixture.prefix();
            var expected = fixture.reference();
            var clock = new AtomicLong();
            var retention = new AudioRetentionCache(prefix.retainedBytes(), 10, clock::get);
            var key = AudioCacheKey.from(fixture.source);
            assertTrue(retention.publish(key, prefix, retention.begin(key).receipt()));
            var authorized = new AtomicBoolean(true);
            var resolutions = new AtomicInteger();
            var capacity = new AudioRuntimeCapacity();
            var host = new FakeHost();
            long creates = AudioPlaybackFixture.nativeCount("nCreateCount");
            long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
            try (var runtime = new ClientAudioRuntime(Runnable::run, source -> {
                resolutions.incrementAndGet();
                if (!authorized.get()) {
                    throw AssetLoadException.access("revoked");
                }
                return fixture.access(Runnable::run);
            }, host, null, retention, capacity)) {
                play(runtime, fixture.source, false);
                var stream = host.removeStream();
                assertEquals(restores, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                assertEquals(creates, AudioPlaybackFixture.nativeCount("nCreateCount"));
                assertTrue(fixture.reads.isEmpty());
                clock.set(10);
                retention.maintain();
                assertEquals(0, retention.retainedBytes());
                assertArrayEquals(expected, readAll(stream));
                assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                authorized.set(false);
                assertTrue(play(runtime, fixture.source, false).isPlaybackStopped());
                assertEquals(2, resolutions.get());
                assertNull(host.pollStream());
                assertIdle(capacity);
            }
        }
    }

    @Test
    void restoreFailuresBeforeAndAfterHostSubmissionHaveOneTerminalAndKeepThePrefix() throws Exception {
        for (boolean submitted : new boolean[] {false, true}) {
            String resource = submitted ? "/audio-playback/stereo-over-five.ogg" : "/audio-fixtures/opus-long.ogg";
            try (var fixture = AudioPlaybackFixture.create(temp.resolve("restore-" + submitted), resource)) {
                var prefix = fixture.prefix();
                var retention = new AudioRetentionCache();
                var key = AudioCacheKey.from(fixture.source);
                assertTrue(retention.publish(key, prefix, retention.begin(key).receipt()));
                var capacity = new AudioRuntimeCapacity();
                var host = new FakeHost();
                long creates = AudioPlaybackFixture.nativeCount("nCreateCount");
                long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
                nativeCall("nFailNextRestore");
                try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                    null, retention, capacity)) {
                    var playback = play(runtime, fixture.source, false);
                    if (submitted) {
                        var stream = host.removeStream();
                        assertFalse(playback.isPlaybackStopped());
                        stream.read(2 * 96_000);
                        assertTrue(stream.isClosed());
                        assertEquals(1, host.stops.get());
                    } else {
                        assertNull(host.pollStream());
                        assertEquals(0, host.submits.get());
                    }
                    assertTrue(playback.isPlaybackStopped());
                    assertEquals(creates, AudioPlaybackFixture.nativeCount("nCreateCount"));
                    assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    var failureField = playback.getClass().getDeclaredField("streamFailure");
                    failureField.setAccessible(true);
                    var failure = assertInstanceOf(Throwable.class, failureField.get(playback));
                    assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("restore"));
                    assertSame(prefix, retention.begin(key).audio());
                    assertIdle(capacity);
                }
            }
        }
    }

    @Test
    void tailFailureDoesNotInvalidateAPreviouslyPublishedPrefix() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/stereo-over-five.ogg")) {
            var retention = new AudioRetentionCache();
            var capacity = new AudioRuntimeCapacity();
            var host = new FakeHost();
            try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                null, retention, capacity)) {
                var playback = play(runtime, fixture.source, false);
                var stream = host.removeStream();
                assertEquals(0, retention.entryCount(), "a prefix longer than the queue needs host consumption");
                fixture.tailFailure = new IOException("fixture tail transport failure");
                stream.read(2 * 96_000);
                assertTrue(playback.isPlaybackStopped());
                assertTrue(stream.isClosed());
                assertInstanceOf(CachedAudio.Prefix.class, retention.begin(AudioCacheKey.from(fixture.source)).audio());
                assertEquals(1, retention.entryCount());
                assertIdle(capacity);
            }
        }
    }

    @Test
    void cancellationBeforeTheHotWorkerRunsDoesNotRestoreOrPublish() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
            var prefix = fixture.prefix();
            var retention = new AudioRetentionCache();
            var key = AudioCacheKey.from(fixture.source);
            assertTrue(retention.publish(key, prefix, retention.begin(key).receipt()));
            var workers = new ManualExecutor();
            var host = new FakeHost();
            var capacity = new AudioRuntimeCapacity();
            long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
            try (var runtime = new ClientAudioRuntime(workers, source -> fixture.access(Runnable::run), host, null,
                retention, capacity)) {
                var playback = play(runtime, fixture.source, false);
                playback.setStopped();
                workers.runAll();
                assertNull(host.pollStream());
                assertEquals(restores, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                assertTrue(fixture.reads.isEmpty());
                assertIdle(capacity);
            }
        }
    }

    private static void assertIdle(AudioRuntimeCapacity capacity) {
        assertEquals(0, capacity.snapshot().active());
        assertEquals(0, capacity.snapshot().preparing());
        assertEquals(0, capacity.snapshot().encodedBytes());
        assertEquals(0, capacity.snapshot().decodedBytes());
    }

    @Test
    void publicationAndStopHaveOneOrderUnderThePlaybackSeal() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/single-exact.ogg")) {
            var publishing = new CountDownLatch(1);
            var resumePublication = new CountDownLatch(1);
            var clockCalls = new AtomicInteger();
            var retention = new AudioRetentionCache(AudioRetentionCache.DEFAULT_BUDGET,
                AudioRetentionCache.DEFAULT_TTL_NANOS, () -> {
                    if (clockCalls.incrementAndGet() == 2) {
                        publishing.countDown();
                        try {
                            if (!resumePublication.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("Publication was not resumed");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    }
                    return 0;
                });
            var workers = Executors.newSingleThreadExecutor();
            var stopper = Executors.newSingleThreadExecutor();
            var capacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(workers, source -> fixture.access(Runnable::run), new FakeHost(),
                null, retention, capacity)) {
                var playback = play(runtime, fixture.source, false);
                assertTrue(publishing.await(10, TimeUnit.SECONDS));
                var stopping = new CountDownLatch(1);
                var stopped = stopper.submit(() -> {
                    stopping.countDown();
                    playback.setStopped();
                });
                assertTrue(stopping.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> stopped.get(100, TimeUnit.MILLISECONDS),
                    "stop must not return in the middle of an already admitted publication");
                resumePublication.countDown();
                stopped.get(10, TimeUnit.SECONDS);
                workers.shutdown();
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                assertTrue(playback.isPlaybackStopped());
                assertEquals(1, retention.entryCount(), "publication linearized before stop sealed the playback");
                assertIdle(capacity);
            } finally {
                resumePublication.countDown();
                workers.shutdownNow();
                stopper.shutdownNow();
                workers.awaitTermination(10, TimeUnit.SECONDS);
                stopper.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void hostSubmissionAndStopHaveOneOrderUnderThePlaybackSeal() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/single-exact.ogg")) {
            var submitting = new CountDownLatch(1);
            var resumeSubmission = new CountDownLatch(1);
            var closedDuringSubmission = new AtomicBoolean();
            var host = new FakeHost();
            var owner = Executors.newSingleThreadExecutor();
            var stopper = Executors.newSingleThreadExecutor();
            host.ownerExecutor = owner;
            host.beforeSubmit = stream -> {
                submitting.countDown();
                try {
                    if (!resumeSubmission.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Host submission was not resumed");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                closedDuringSubmission.set(stream.isClosed());
            };
            var capacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                null, new AudioRetentionCache(), capacity)) {
                var playback = play(runtime, fixture.source, false);
                try {
                    assertTrue(submitting.await(10, TimeUnit.SECONDS));
                    var stopping = new CountDownLatch(1);
                    var stopped = stopper.submit(() -> {
                        stopping.countDown();
                        playback.setStopped();
                    });
                    assertTrue(stopping.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> stopped.get(100, TimeUnit.MILLISECONDS),
                        "stop must not seal a playback while its admitted host submission is running");
                    resumeSubmission.countDown();
                    stopped.get(10, TimeUnit.SECONDS);
                    owner.submit(() -> {
                    }).get(10, TimeUnit.SECONDS);
                    assertFalse(closedDuringSubmission.get());
                    assertTrue(playback.isPlaybackStopped());
                    assertEquals(1, host.submits.get());
                    assertEquals(1, host.stops.get());
                    assertIdle(capacity);
                } finally {
                    resumeSubmission.countDown();
                }
            } finally {
                resumeSubmission.countDown();
                owner.shutdownNow();
                stopper.shutdownNow();
                owner.awaitTermination(10, TimeUnit.SECONDS);
                stopper.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void stopBeforeTheQueuedPreparedCallbackPreventsHostSubmission() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/single-exact.ogg")) {
            var workers = new ManualExecutor();
            var owner = new ManualExecutor();
            var host = new FakeHost();
            host.ownerExecutor = owner;
            var capacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(workers, source -> fixture.access(Runnable::run), host, null,
                new AudioRetentionCache(), capacity)) {
                var playback = play(runtime, fixture.source, false);
                owner.runAll();
                workers.runAll();
                assertEquals(1, capacity.snapshot().preparing());
                assertEquals(0, host.submits.get());
                playback.setStopped();
                owner.runAll();
                workers.runAll();
                assertTrue(playback.isPlaybackStopped());
                assertEquals(0, host.submits.get());
                assertIdle(capacity);
            }
        }
    }

    @Test
    void synchronousStreamCloseDuringHostSubmissionReleasesTheReturnedHandle() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/single-exact.ogg")) {
            var host = new FakeHost();
            host.beforeSubmit = stream -> {
                try {
                    stream.close();
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            };
            var capacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                null, new AudioRetentionCache(), capacity)) {
                var playback = play(runtime, fixture.source, false);
                assertTrue(playback.isPlaybackStopped());
                assertEquals(1, host.submits.get());
                assertEquals(1, host.stops.get());
                assertIdle(capacity);
            }
        }
    }

    @Test
    void shortPcmIsDecodedOnceAndHotReplayStillRechecksExactAccess() throws Exception {
        var content = createContent(builtInAudio("/assets/ysm/builtin/wine_fox/14_momo/sounds/fly_on.ogg"));
        var reads = new AtomicInteger();
        var resolutions = new AtomicInteger();
        var authorized = new AtomicBoolean(true);
        var workers = new CountingExecutor();
        var host = new FakeHost();
        var retention = new AudioRetentionCache();
        var capacity = new AudioRuntimeCapacity();
        try (var resources = readySounds(content, content.chunks());
            var runtime = new ClientAudioRuntime(workers, source -> {
                resolutions.incrementAndGet();
                if (!authorized.get()) {
                    throw AssetLoadException.access("revoked");
                }
                return access(source, new CountingContent(content, reads));
            }, host, null, retention, capacity)) {
            var source = bindTone(content, resources);
            var first = play(runtime, source, false);
            assertFalse(first.isPlaybackStopped());
            var cold = readAll(host.removeStream());
            int coldWorkerRuns = workers.runs.get();
            assertTrue(coldWorkerRuns > 0);
            assertEquals(1, retention.entryCount());

            var second = play(runtime, source, false);
            assertFalse(second.isPlaybackStopped());
            var hot = readAll(host.removeStream());
            assertArrayEquals(cold, hot);
            assertEquals(coldWorkerRuns, workers.runs.get(),
                "a complete short PCM hit must not invoke the decoder worker");
            assertEquals(2, resolutions.get());

            authorized.set(false);
            var rejected = play(runtime, source, false);
            assertTrue(rejected.isPlaybackStopped());
            assertNull(host.pollStream());
            assertEquals(3, resolutions.get());
            assertEquals(0, capacity.snapshot().active());
            assertEquals(0, capacity.snapshot().preparing());
            assertEquals(0, capacity.snapshot().encodedBytes());
            assertEquals(0, capacity.snapshot().decodedBytes());
        } finally {
            content.representation().close();
        }
    }

    @Test
    void longLoopPreparesBeforeTailAndRebuildsTheTimeline() throws Exception {
        var content = createLongContent();
        var reads = new AtomicInteger();
        var host = new FakeHost();
        host.readCounter = reads::get;
        try (var resources = readySounds(content, content.chunks()); var runtime = new ClientAudioRuntime(Runnable::run,
            source -> access(source, new CountingContent(content, reads)), host, null)) {
            var source = bindTone(content, resources);
            var sound = source.media();
            play(runtime, source, true);
            var stream = host.removeStream();
            assertTrue(host.readsAtSubmit < sound.chunkInfos().size() - 1,
                "host submission must not require the long tail");

            int cycleBytes = Math.toIntExact(Math.multiplyExact(sound.frames(), Short.BYTES));
            try (stream) {
                var twoCycles = readExactly(stream, cycleBytes * 2, 16_383);
                assertArrayEquals(java.util.Arrays.copyOfRange(twoCycles, 0, cycleBytes),
                    java.util.Arrays.copyOfRange(twoCycles, cycleBytes, cycleBytes * 2));
            }
            assertTrue(reads.get() >= 2 * (sound.chunkInfos().size() - 1),
                "a long loop must reacquire every non-retained chunk per cycle");
        } finally {
            content.representation().close();
        }
    }

    @Test
    void stopBeforePreparationSealsAllLateWorkerEffects() throws Exception {
        var content = createLongContent();
        var workers = new ManualExecutor();
        var host = new FakeHost();
        var capacity = new AudioRuntimeCapacity();
        try (var resources = readySounds(content, content.chunks()); var runtime = new ClientAudioRuntime(workers,
            source -> access(source, content), host, null, new AudioRetentionCache(), capacity)) {
            var playback = play(runtime, bindTone(content, resources), false);
            assertNull(host.pollStream());

            playback.setStopped(AudioBackend.CancelReason.AUTHOR_STOP);
            workers.runAll();

            assertTrue(playback.isPlaybackStopped());
            assertNull(host.pollStream());
            assertEquals(0, capacity.snapshot().active());
            assertEquals(0, capacity.snapshot().preparing());
            assertEquals(0, capacity.snapshot().encodedBytes());
            assertEquals(0, capacity.snapshot().decodedBytes());
        } finally {
            content.representation().close();
        }
    }

    @Test
    void activeAndPreparationLimitsRejectWithoutCreatingAnUntrackedPlayback() throws Exception {
        var content = createLongContent();
        var workers = new ManualExecutor();
        var host = new FakeHost();
        var capacity = new AudioRuntimeCapacity(1, 1, 1 << 20, 2 << 20);
        try (var resources = readySounds(content, content.chunks()); var runtime = new ClientAudioRuntime(workers,
            source -> access(source, content), host, null, new AudioRetentionCache(), capacity)) {
            var source = bindTone(content, resources);
            var first = runtime.createPlayback(source).play(control(false));
            var rejected = runtime.createPlayback(source).play(control(false));

            assertNotNull(first);
            assertNull(rejected);
            assertEquals(1, capacity.snapshot().active());
            first.setStopped();
            workers.runAll();
            assertEquals(0, capacity.snapshot().active());
            assertNull(host.pollStream());
        } finally {
            content.representation().close();
        }
    }

    @Test
    void externalBackendOwnsTheWholePlaybackAndHasNoSynchronousReverseCallback() throws Exception {
        var content = createContent(builtInAudio("/assets/ysm/builtin/wine_fox/14_momo/sounds/fly_on.ogg"));
        var host = new FakeHost();
        var backend = new FakeBackend();
        var capacity = new AudioRuntimeCapacity();
        try (var resources = readySounds(content, content.chunks()); var runtime = new ClientAudioRuntime(Runnable::run,
            source -> access(source, content), host, backend, new AudioRetentionCache(), capacity)) {
            var control = control(false);
            var playback = runtime.createPlayback(bindTone(content, resources)).play(control);
            assertNotNull(playback);
            assertEquals(1, backend.plays.get());
            assertFalse(backend.terminalRequestedInsidePlay.get());
            assertNull(host.pollStream());
            assertNotNull(backend.request);
            assertEquals(backend.request.media().chunkSizes().size(), backend.request.chunks().chunkCount());

            try (var chunk = backend.request.chunks().request(0).toCompletableFuture().join()) {
                assertEquals(0, chunk.index());
                assertTrue(chunk.data().hasRemaining());
            }
            runtime.maintain();
            assertSame(control.snapshot(false), backend.lastControl);

            playback.setStopped(AudioBackend.CancelReason.FORCE_REPLACED);
            assertEquals(AudioBackend.CancelReason.FORCE_REPLACED, backend.cancelReason);
            assertTrue(playback.isPlaybackStopped());
            assertEquals(0, capacity.snapshot().active());
        } finally {
            content.representation().close();
        }
    }

    @Test
    void externalCancellationKeepsDeliveredChunksAliveUntilTheBorrowerClosesThem() throws Exception {
        for (var reason : new AudioBackend.CancelReason[] {AudioBackend.CancelReason.AUTHOR_STOP,
            AudioBackend.CancelReason.FORCE_REPLACED, AudioBackend.CancelReason.RUNTIME_CLOSED}) {
            try (var fixture =
                AudioPlaybackFixture.create(temp.resolve(reason.name()), "/audio-playback/stereo-over-five.ogg")) {
                var backend = new FakeBackend();
                backend.completeOnCancel = false;
                var borrowed = new CountDownLatch(1);
                var resume = new CountDownLatch(1);
                var closedAtCancel = new AtomicInteger();
                var worker = Executors.newSingleThreadExecutor();
                var work = new AtomicReference<java.util.concurrent.Future<AudioBackend.Chunk>>();
                backend.onPlay = request -> work.set(worker.submit(() -> {
                    try (var first = request.chunks().request(0).toCompletableFuture().join()) {
                        assertEquals(0, first.index());
                    }
                    var chunk = request.chunks().request(1).toCompletableFuture().join();
                    try (chunk) {
                        var bytes = chunk.data();
                        borrowed.countDown();
                        assertTrue(resume.await(5, TimeUnit.SECONDS));
                        assertArrayEquals(fixture.payloads.get(1), bytes(bytes));
                        assertArrayEquals(fixture.payloads.get(1), bytes(chunk.data()));
                    }
                    backend.terminal.complete(AudioBackend.Terminal.cancelled(reason));
                    return chunk;
                }));
                backend.onCancel = request -> {
                    assertClosedAccess(request.chunks());
                    closedAtCancel.incrementAndGet();
                };
                var capacity = new AudioRuntimeCapacity();
                var host = new FakeHost();
                try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                    backend, new AudioRetentionCache(), capacity)) {
                    var playback = play(runtime, fixture.source, false);
                    assertTrue(borrowed.await(5, TimeUnit.SECONDS));
                    if (reason == AudioBackend.CancelReason.RUNTIME_CLOSED) {
                        runtime.close();
                    } else {
                        playback.setStopped(reason);
                    }
                    assertEquals(1, closedAtCancel.get());
                    assertEquals(reason, backend.cancelReason);
                    assertTrue(playback.isPlaybackStopped());
                    assertFalse(backend.terminal.isDone());
                    assertIdle(capacity);
                    assertNull(host.pollStream());
                    resume.countDown();
                    var chunk = work.get().get(5, TimeUnit.SECONDS);
                    assertThrows(IllegalStateException.class, chunk::data);
                    assertEquals(1, backend.cancels.get());
                    assertIdle(capacity);
                } finally {
                    resume.countDown();
                    worker.shutdownNow();
                    assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void externalChunkCallbacksDoNotMakeCancellationWaitForTheBorrower() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/stereo-over-five.ogg")) {
            var owner = new ManualExecutor();
            var backend = new FakeBackend();
            backend.completeOnCancel = false;
            var borrowed = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var deliveries = Executors.newSingleThreadExecutor();
            var stopper = Executors.newSingleThreadExecutor();
            var capacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(Runnable::run,
                source -> VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, owner),
                new FakeHost(), backend, new AudioRetentionCache(), capacity)) {
                var playback = play(runtime, fixture.source, false);
                var chunks = backend.request.chunks();
                var first = chunks.request(0).toCompletableFuture();
                owner.runAll();
                first.join().close();
                var callback = chunks.request(1).thenAccept(chunk -> {
                    try (chunk) {
                        var data = chunk.data();
                        borrowed.countDown();
                        try {
                            assertTrue(resume.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        assertArrayEquals(fixture.payloads.get(1), bytes(data));
                    }
                    backend.terminal.complete(AudioBackend.Terminal.cancelled(AudioBackend.CancelReason.AUTHOR_STOP));
                });
                var delivery = deliveries.submit(owner::runAll);
                assertTrue(borrowed.await(5, TimeUnit.SECONDS));
                stopper.submit((Runnable)playback::setStopped).get(5, TimeUnit.SECONDS);
                assertTrue(playback.isPlaybackStopped());
                assertEquals(1, backend.cancels.get());
                assertClosedAccess(chunks);
                assertFalse(delivery.isDone());
                assertFalse(backend.terminal.isDone());
                assertIdle(capacity);
                resume.countDown();
                delivery.get(5, TimeUnit.SECONDS);
                callback.toCompletableFuture().join();
                assertTrue(backend.terminal.isDone());
                assertIdle(capacity);
            } finally {
                resume.countDown();
                deliveries.shutdownNow();
                stopper.shutdownNow();
                assertTrue(deliveries.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(stopper.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void runtimeCancellationSignalsBackendBeforeFailingPendingReads() throws Exception {
        for (var reason : new AudioBackend.CancelReason[] {AudioBackend.CancelReason.AUTHOR_STOP,
            AudioBackend.CancelReason.RUNTIME_CLOSED}) {
            for (boolean throwsOnCancel : new boolean[] {false, true}) {
                assertPendingFailureObservesBackendCancellation(reason, false, throwsOnCancel);
            }
        }
    }

    @Test
    void externalAcceptanceAfterRuntimeClosureSignalsBackendBeforeFailingPendingReads() throws Exception {
        for (boolean throwsOnCancel : new boolean[] {false, true}) {
            assertPendingFailureObservesBackendCancellation(AudioBackend.CancelReason.RUNTIME_CLOSED, true,
                throwsOnCancel);
        }
    }

    private void assertPendingFailureObservesBackendCancellation(AudioBackend.CancelReason reason,
        boolean closeDuringPlay, boolean throwsOnCancel) throws Exception {
        try (var fixture = AudioPlaybackFixture.create(
            temp.resolve("cancel-pending-" + reason + "-" + closeDuringPlay + "-" + throwsOnCancel),
            "/audio-playback/single-exact.ogg")) {
            var owner = new ManualExecutor();
            var backend = new FakeBackend();
            backend.completeOnCancel = false;
            backend.throwsOnCancel = throwsOnCancel;
            var callbackEntered = new CountDownLatch(1);
            var cancelSignal = new CountDownLatch(1);
            var callbackCompleted = new CompletableFuture<Void>();
            var pending = new AtomicReference<CompletableFuture<AudioBackend.Chunk>>();
            var runtimeRef = new AtomicReference<ClientAudioRuntime>();
            backend.onPlay = request -> {
                var result = request.chunks().request(0).toCompletableFuture();
                pending.set(result);
                result.whenComplete((chunk, failure) -> {
                    callbackEntered.countDown();
                    try {
                        assertNull(chunk);
                        assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
                        assertTrue(cancelSignal.await(5, TimeUnit.SECONDS),
                            "A pending failure callback must be able to await the backend cancellation signal");
                        assertEquals(reason, backend.cancelReason);
                        backend.terminal.complete(AudioBackend.Terminal.cancelled(reason));
                        callbackCompleted.complete(null);
                    } catch (Throwable callbackFailure) {
                        callbackCompleted.completeExceptionally(callbackFailure);
                    }
                });
                if (closeDuringPlay) {
                    runtimeRef.get().close();
                }
            };
            backend.onCancel = request -> {
                assertClosedAccess(request.chunks());
                assertFalse(pending.get().isDone(), "Backend cancellation must precede pending failure notification");
                cancelSignal.countDown();
            };
            var stopper = Executors.newSingleThreadExecutor();
            var capacity = new AudioRuntimeCapacity();
            var host = new FakeHost();
            var runtime = new ClientAudioRuntime(Runnable::run,
                source -> VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, owner), host,
                backend, new AudioRetentionCache(), capacity);
            runtimeRef.set(runtime);
            try {
                var playback = new AtomicReference<ICanStopSound>();
                final Runnable operation;
                if (closeDuringPlay) {
                    operation = () -> playback.set(play(runtime, fixture.source, false));
                } else {
                    playback.set(play(runtime, fixture.source, false));
                    operation = reason == AudioBackend.CancelReason.RUNTIME_CLOSED ? runtime::close
                        : () -> playback.get().setStopped(reason);
                }
                var cancelled = stopper.submit(operation);
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
                cancelled.get(1, TimeUnit.SECONDS);
                callbackCompleted.get(1, TimeUnit.SECONDS);
                assertTrue(playback.get().isPlaybackStopped());
                assertEquals(1, backend.cancels.get());
                assertTrue(pending.get().isCancelled());
                assertTrue(backend.terminal.isDone());
                assertClosedAccess(backend.request.chunks());
                owner.runAll();
                assertTrue(fixture.reads.isEmpty());
                assertIdle(capacity);
                assertNull(host.pollStream());
            } finally {
                cancelSignal.countDown();
                stopper.shutdownNow();
                assertTrue(stopper.awaitTermination(5, TimeUnit.SECONDS));
                runtime.close();
                owner.runAll();
            }
        }
    }

    @Test
    void lateExternalTerminalAndThrowingCancellationLeaveTheNewPlaybackActive() throws Exception {
        for (boolean throwsOnCancel : new boolean[] {false, true}) {
            try (var fixture = AudioPlaybackFixture.create(temp.resolve("late-" + throwsOnCancel),
                "/audio-playback/single-exact.ogg")) {
                var first = new FakeBackend();
                first.completeOnCancel = false;
                first.throwsOnCancel = throwsOnCancel;
                var closedAtCancel = new AtomicInteger();
                first.onCancel = request -> {
                    assertClosedAccess(request.chunks());
                    closedAtCancel.incrementAndGet();
                };
                var second = new FakeBackend();
                var attempts = new AtomicInteger();
                AudioBackend backend = request -> (attempts.getAndIncrement() == 0 ? first : second).play(request);
                var capacity = new AudioRuntimeCapacity();
                var host = new FakeHost();
                try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                    backend, new AudioRetentionCache(), capacity)) {
                    var oldPlayback = play(runtime, fixture.source, false);
                    try (var oldChunk = first.request.chunks().request(0).toCompletableFuture().join()) {
                        oldPlayback.setStopped(AudioBackend.CancelReason.FORCE_REPLACED);
                        assertEquals(1, closedAtCancel.get());
                        assertTrue(oldChunk.data().hasRemaining());
                        assertIdle(capacity);
                        var current = play(runtime, fixture.source, false);
                        assertEquals(1, capacity.snapshot().active());
                        oldChunk.close();
                        assertTrue(first.terminal.complete(AudioBackend.Terminal.cancelled(first.cancelReason)));
                        assertFalse(first.terminal.complete(AudioBackend.Terminal.completed()));
                        oldPlayback.setStopped();
                        assertFalse(current.isPlaybackStopped());
                        assertEquals(1, capacity.snapshot().active());
                        assertEquals(1, first.cancels.get());
                        assertEquals(0, second.cancels.get());
                        try (var currentChunk = second.request.chunks().request(0).toCompletableFuture().join()) {
                            assertTrue(currentChunk.data().hasRemaining());
                        }
                        current.setStopped();
                        assertIdle(capacity);
                        assertNull(host.pollStream());
                    }
                }
            }
        }
    }

    @Test
    void cancellingOrClosingExternalChunkRequestsCancelsPendingInterests() throws Exception {
        for (boolean closeAccess : new boolean[] {false, true}) {
            try (var fixture = AudioPlaybackFixture.create(temp.resolve("pending-" + closeAccess),
                "/audio-playback/single-exact.ogg")) {
                var owner = new ManualExecutor();
                var backend = new FakeBackend();
                try (var runtime = new ClientAudioRuntime(Runnable::run,
                    source -> VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, owner),
                    new FakeHost(), backend)) {
                    play(runtime, fixture.source, false);
                    var chunks = backend.request.chunks();
                    var result = chunks.request(0).toCompletableFuture();
                    assertFalse(result.isDone());
                    if (closeAccess) {
                        chunks.close();
                        assertClosedAccess(chunks);
                    } else {
                        assertTrue(result.cancel(false));
                    }
                    owner.runAll();
                    assertTrue(result.isCancelled());
                    assertThrows(IllegalStateException.class, chunks::restart);
                    assertThrows(IllegalStateException.class, () -> chunks.request(1));
                    assertTrue(fixture.reads.isEmpty());
                }
            }
        }
    }

    @Test
    void undeliveredExternalChunksAreClosedWhenCancellationOrSealingWinsDelivery() throws Exception {
        for (boolean cancelFuture : new boolean[] {false, true}) {
            try (var fixture = AudioPlaybackFixture.create(temp.resolve("delivery-" + cancelFuture),
                "/audio-playback/single-exact.ogg")) {
                assertEquals(1, fixture.media.chunkInfos().size());
                var owner = new ManualExecutor();
                var rawAccess = new AtomicReference<VerifiedChunkAccess>();
                var backend = new FakeBackend();
                var deliveries = Executors.newSingleThreadExecutor();
                try (var runtime = new ClientAudioRuntime(Runnable::run, source -> {
                    var access = VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, owner);
                    rawAccess.set(access);
                    return access;
                }, new FakeHost(), backend)) {
                    play(runtime, fixture.source, false);
                    var chunks = backend.request.chunks();
                    var result = chunks.request(0).toCompletableFuture();
                    // Observe provider completion without adding a production test hook.
                    var acquisition = pendingInterest(rawAccess.get()).result();
                    final VerifiedChunkAccess.Chunk undelivered;
                    final java.util.concurrent.Future<?> delivery;
                    synchronized (chunks) {
                        delivery = deliveries.submit(owner::runAll);
                        undelivered = acquisition.get(5, TimeUnit.SECONDS);
                        if (cancelFuture) {
                            assertTrue(result.cancel(false));
                        } else {
                            chunks.close();
                        }
                    }
                    delivery.get(5, TimeUnit.SECONDS);
                    assertTrue(result.isCancelled());
                    assertThrows(CancellationException.class, result::join);
                    assertThrows(IllegalStateException.class, undelivered::borrowBuffer);
                    if (cancelFuture) {
                        assertSame(chunks, chunks.restart());
                    } else {
                        assertClosedAccess(chunks);
                    }
                } finally {
                    deliveries.shutdownNow();
                    assertTrue(deliveries.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void completedExternalDeliverySurvivesReentrantAccessClosure() throws Exception {
        for (boolean queued : new boolean[] {false, true}) {
            try (var fixture =
                AudioPlaybackFixture.create(temp.resolve("reentrant-" + queued), "/audio-playback/single-exact.ogg")) {
                var owner = new ManualExecutor();
                var backend = new FakeBackend();
                Executor deliveryOwner = queued ? owner : Runnable::run;
                try (var runtime = new ClientAudioRuntime(Runnable::run,
                    source -> VerifiedChunkAccess.create(source, fixture.content, null, Runnable::run, deliveryOwner),
                    new FakeHost(), backend)) {
                    play(runtime, fixture.source, false);
                    var chunks = backend.request.chunks();
                    var result = chunks.request(0).toCompletableFuture();
                    var delivered = result.thenAccept(chunk -> {
                        chunks.close();
                        assertTrue(chunk.data().hasRemaining());
                    });
                    owner.runAll();
                    delivered.join();
                    assertFalse(result.cancel(false));
                    var chunk = result.join();
                    assertArrayEquals(fixture.payloads.get(0), bytes(chunk.data()));
                    assertClosedAccess(chunks);
                    chunk.close();
                    chunk.close();
                    assertThrows(IllegalStateException.class, chunk::data);
                }
            }
        }
    }

    @Test
    void externalRestartUsesTheExistingCursorOwnershipCheckAndNaturalTerminal() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/stereo-over-five.ogg")) {
            var backend = new FakeBackend();
            var capacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run),
                new FakeHost(), backend, new AudioRetentionCache(), capacity)) {
                var playback = play(runtime, fixture.source, true);
                var chunks = backend.request.chunks();
                for (int cycle = 0; cycle < 2; cycle++) {
                    assertThrows(IllegalStateException.class, chunks::restart);
                    for (int index = 0; index < chunks.chunkCount(); index++) {
                        try (var chunk = chunks.request(index).toCompletableFuture().join()) {
                            assertArrayEquals(fixture.payloads.get(index), bytes(chunk.data()));
                            assertThrows(IllegalStateException.class, chunks::restart);
                        }
                    }
                    if (cycle == 0) {
                        assertSame(chunks, chunks.restart());
                    }
                }
                assertTrue(backend.terminal.complete(AudioBackend.Terminal.completed()));
                assertTrue(playback.isPlaybackStopped());
                assertEquals(0, backend.cancels.get());
                assertClosedAccess(chunks);
                assertIdle(capacity);
            }
        }
    }

    @Test
    void externalAcceptanceAfterRuntimeClosureSealsAccessBeforeCancellingTheBackend() throws Exception {
        try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/single-exact.ogg")) {
            var backend = new FakeBackend();
            backend.completeOnCancel = false;
            var runtimeRef = new AtomicReference<ClientAudioRuntime>();
            var ownedChunk = new AtomicReference<AudioBackend.Chunk>();
            var closedAtCancel = new AtomicInteger();
            backend.onPlay = request -> {
                ownedChunk.set(request.chunks().request(0).toCompletableFuture().join());
                runtimeRef.get().close();
            };
            backend.onCancel = request -> {
                assertClosedAccess(request.chunks());
                closedAtCancel.incrementAndGet();
            };
            var capacity = new AudioRuntimeCapacity();
            var host = new FakeHost();
            try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                backend, new AudioRetentionCache(), capacity)) {
                runtimeRef.set(runtime);
                assertTrue(play(runtime, fixture.source, false).isPlaybackStopped());
                try (var chunk = ownedChunk.get()) {
                    assertEquals(1, closedAtCancel.get());
                    assertEquals(AudioBackend.CancelReason.RUNTIME_CLOSED, backend.cancelReason);
                    assertTrue(chunk.data().hasRemaining());
                    assertIdle(capacity);
                    assertNull(host.pollStream());
                }
                backend.terminal.complete(AudioBackend.Terminal.cancelled(backend.cancelReason));
                assertEquals(1, backend.cancels.get());
            }
        }
    }

    @Test
    void externalRejectionAndThrowingPlayCloseTheCapabilityWithoutDefaultFallback() throws Exception {
        for (boolean throwsOnPlay : new boolean[] {false, true}) {
            try (var fixture = AudioPlaybackFixture.create(temp.resolve("rejected-" + throwsOnPlay),
                "/audio-playback/single-exact.ogg")) {
                var requestRef = new AtomicReference<AudioBackend.PlaybackRequest>();
                var ownedChunk = new AtomicReference<AudioBackend.Chunk>();
                var receivedBytes = new AtomicInteger();
                AudioBackend backend = request -> {
                    requestRef.set(request);
                    try (var chunk = request.chunks().request(0).toCompletableFuture().join()) {
                        ownedChunk.set(chunk);
                        receivedBytes.set(chunk.data().remaining());
                    }
                    if (throwsOnPlay) {
                        throw new IllegalStateException("fixture play failure");
                    }
                    return new AudioBackend.Rejected("fixture rejection");
                };
                var capacity = new AudioRuntimeCapacity();
                var host = new FakeHost();
                try (var runtime = new ClientAudioRuntime(Runnable::run, source -> fixture.access(Runnable::run), host,
                    backend, new AudioRetentionCache(), capacity)) {
                    assertTrue(play(runtime, fixture.source, false).isPlaybackStopped());
                    assertClosedAccess(requestRef.get().chunks());
                    assertEquals(fixture.payloads.get(0).length, receivedBytes.get());
                    assertThrows(IllegalStateException.class, ownedChunk.get()::data);
                    assertIdle(capacity);
                    assertNull(host.pollStream());
                    assertEquals(0, host.submits.get());
                }
            }
        }
    }

    private static void assertClosedAccess(AudioBackend.ChunkAccess chunks) {
        assertThrows(IllegalStateException.class, chunks::chunkCount);
        assertThrows(IllegalStateException.class, () -> chunks.request(0));
        assertThrows(IllegalStateException.class, chunks::restart);
    }

    private static VerifiedChunkAccess.Interest pendingInterest(VerifiedChunkAccess access) throws Exception {
        var field = VerifiedChunkAccess.class.getDeclaredField("active");
        field.setAccessible(true);
        return Objects.requireNonNull((VerifiedChunkAccess.Interest)field.get(access));
    }

    @Test
    void backendAndHostRejectionReleaseEveryAcceptedResource() throws Exception {
        var content = createContent(builtInAudio("/assets/ysm/builtin/wine_fox/14_momo/sounds/fly_on.ogg"));
        var sourceContent = new AtomicInteger();
        try (var resources = readySounds(content, content.chunks())) {
            var source = bindTone(content, resources);
            var backendCapacity = new AudioRuntimeCapacity();
            var rejectingBackend = (AudioBackend)request -> {
                sourceContent.incrementAndGet();
                return new AudioBackend.Rejected("fixture rejection");
            };
            try (var runtime = new ClientAudioRuntime(Runnable::run, requested -> access(requested, content),
                new FakeHost(), rejectingBackend, new AudioRetentionCache(), backendCapacity)) {
                assertTrue(play(runtime, source, false).isPlaybackStopped());
                assertEquals(1, sourceContent.get());
                assertEquals(0, backendCapacity.snapshot().active());
            }

            var host = new FakeHost();
            host.reject = true;
            var hostCapacity = new AudioRuntimeCapacity();
            try (var runtime = new ClientAudioRuntime(Runnable::run, requested -> access(requested, content), host,
                null, new AudioRetentionCache(), hostCapacity)) {
                assertTrue(play(runtime, source, false).isPlaybackStopped());
                assertEquals(1, host.submits.get());
                assertEquals(0, hostCapacity.snapshot().active());
                assertEquals(0, hostCapacity.snapshot().preparing());
                assertEquals(0, hostCapacity.snapshot().encodedBytes());
                assertEquals(0, hostCapacity.snapshot().decodedBytes());
            }
        } finally {
            content.representation().close();
        }
    }

    @Test
    void stopAfterSynchronousChunkCompletionReleasesReservations() throws Exception {
        var content = createContent(builtInAudio("/assets/ysm/builtin/wine_fox/14_momo/sounds/fly_on.ogg"));
        var capacity = new AudioRuntimeCapacity();
        var owner = new PauseAfterCompletionExecutor();
        var workers = Executors.newSingleThreadExecutor();
        try (var resources = readySounds(content, content.chunks());
            var runtime = new ClientAudioRuntime(workers,
                source -> VerifiedChunkAccess.create(source, content, null, Runnable::run, owner), new FakeHost(), null,
                new AudioRetentionCache(), capacity)) {
            var playback = play(runtime, bindTone(content, resources), false);
            assertTrue(owner.awaitCompletion());

            playback.setStopped(AudioBackend.CancelReason.AUTHOR_STOP);
            owner.resume();
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            owner.resume();
            workers.shutdownNow();
            content.representation().close();
        }
        assertEquals(0, capacity.snapshot().encodedBytes());
        assertEquals(0, capacity.snapshot().decodedBytes());
    }

    private ManagedContainer createLongContent() throws Exception {
        var audio =
            ClientAudioRuntimeIntegrationTest.class.getResource("/assets/ysm/builtin/wine_fox/07_jk/sounds/minuet.ogg");
        return createContent(Files.readAllBytes(Path.of(Objects.requireNonNull(audio).toURI())));
    }

    private ManagedContainer createContent(byte[] encoded) throws Exception {
        var source = copyRawFixture(temp.resolve("raw-" + System.nanoTime()), encoded);
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        Path container;
        try (var captured = importer.capture(source)) {
            container = importer.convert(captured, Files.createDirectories(temp.resolve("output"))).stagedContainer();
        }
        return ManagedContainer.openDirect(container,
            new CatalogLocation(CatalogRootKind.MODELS, new ModelPath("audio-u4")));
    }

    private static TargetSoundResources readySounds(ModelContent content, ChunkDataSource chunks) throws IOException {
        return TargetSoundResources.prepare(() -> false, content.representation().identity(),
            content.representation().view().getCommon().sounds(), chunks);
    }

    private static SoundSource bindTone(ModelContent content, TargetSoundResources resources) {
        return resources.bind(content.modelFile().getCommon().sounds().get("tone"));
    }

    private static VerifiedChunkAccess access(SoundSource source, ModelContent content) throws AssetLoadException {
        return VerifiedChunkAccess.create(source, content, null, Runnable::run, Runnable::run);
    }

    private static ICanStopSound play(ClientAudioRuntime runtime, SoundSource source, boolean looping) {
        return Objects.requireNonNull(runtime.createPlayback(source).play(control(looping)));
    }

    private static FixedControl control(boolean looping) {
        return new FixedControl(looping, new AtomicBoolean(),
            new AudioBackend.Control(0.75f, 0.5f, 1.25f, 1, 2, 3, false, looping, false));
    }

    private static byte[] readAll(CustomAudioStream stream) throws Exception {
        try (stream) {
            var output = new ByteArrayOutputStream();
            for (;;) {
                var chunk = stream.read(4095);
                if (!chunk.hasRemaining()) {
                    return output.toByteArray();
                }
                output.writeBytes(bytes(chunk));
            }
        }
    }

    private static byte[] readExactly(CustomAudioStream stream, int length, int size) throws Exception {
        var output = new ByteArrayOutputStream(length);
        while (output.size() < length) {
            var chunk = stream.read(Math.min(size, length - output.size()));
            assertTrue(chunk.hasRemaining());
            output.writeBytes(bytes(chunk));
        }
        return output.toByteArray();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static Path copyRawFixture(Path destination, byte[] encoded) throws Exception {
        var manifest = ClientAudioRuntimeIntegrationTest.class.getResource("/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var sounds = Files.createDirectories(destination.resolve("sounds"));
        Files.write(sounds.resolve("tone.ogg"), encoded);
        return destination;
    }

    private static byte[] builtInAudio(String resource) throws Exception {
        var audio = ClientAudioRuntimeIntegrationTest.class.getResource(resource);
        return Files.readAllBytes(Path.of(Objects.requireNonNull(audio).toURI()));
    }

    private record FixedControl(boolean looping, AtomicBoolean retired,
        AudioBackend.Control value) implements PlaybackControl {
        @Override
        public boolean isLooping() {
            return looping;
        }

        @Override
        public boolean isRetired() {
            return retired.get();
        }

        @Override
        public AudioBackend.Control snapshot(boolean paused) {
            if (paused == value.paused()) {
                return value;
            }
            return new AudioBackend.Control(value.authorVolume(), value.localGain(), value.pitch(), value.x(),
                value.y(), value.z(), value.relative(), value.looping(), paused);
        }
    }

    private static final class FakeHost implements AudioHost {
        private final ArrayDeque<CustomAudioStream> streams = new ArrayDeque<>();
        private final AtomicInteger submits = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private boolean reject;
        private int readsAtSubmit;
        private java.util.function.IntSupplier readCounter = () -> 0;
        private Executor ownerExecutor = Runnable::run;
        private java.util.function.Consumer<CustomAudioStream> beforeSubmit = ignored -> {
        };

        @Override
        public void execute(Runnable task) {
            ownerExecutor.execute(task);
        }

        @Override
        public boolean paused() {
            return false;
        }

        @Override
        public HostPlayback submit(CustomAudioStream stream, PlaybackControl control) {
            submits.incrementAndGet();
            beforeSubmit.accept(stream);
            if (reject) {
                return null;
            }
            readsAtSubmit = readCounter.getAsInt();
            streams.add(stream);
            return new HostPlayback() {
                @Override
                public boolean active() {
                    return !stream.isClosed();
                }

                @Override
                public void stopPlayback() {
                    stops.incrementAndGet();
                    try {
                        stream.close();
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
        }

        private CustomAudioStream removeStream() {
            return Objects.requireNonNull(streams.poll());
        }

        private CustomAudioStream pollStream() {
            return streams.poll();
        }
    }

    private static final class CountingExecutor implements Executor {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            runs.incrementAndGet();
            command.run();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }

    private static final class PauseAfterCompletionExecutor implements Executor {
        private final ThreadLocal<Boolean> running = ThreadLocal.withInitial(() -> false);
        private final AtomicBoolean pause = new AtomicBoolean(true);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            if (running.get()) {
                command.run();
                return;
            }
            running.set(true);
            try {
                command.run();
            } finally {
                running.remove();
            }
            if (pause.compareAndSet(true, false)) {
                completed.countDown();
                try {
                    resume.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }
        }

        private boolean awaitCompletion() throws InterruptedException {
            return completed.await(10, TimeUnit.SECONDS);
        }

        private void resume() {
            resume.countDown();
        }
    }

    private static final class FakeBackend implements AudioBackend {
        private final AtomicInteger plays = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final AtomicBoolean inPlay = new AtomicBoolean();
        private final AtomicBoolean terminalRequestedInsidePlay = new AtomicBoolean();
        private final CompletableFuture<Terminal> terminal = new CompletableFuture<>();
        private PlaybackRequest request;
        private Control lastControl;
        private CancelReason cancelReason;
        private boolean completeOnCancel = true;
        private boolean throwsOnCancel;
        private java.util.function.Consumer<PlaybackRequest> onPlay = ignored -> {
        };
        private java.util.function.Consumer<PlaybackRequest> onCancel = ignored -> {
        };

        @Override
        public PlayResult play(PlaybackRequest candidate) {
            inPlay.set(true);
            plays.incrementAndGet();
            request = candidate;
            onPlay.accept(candidate);
            var result = new Accepted(new Playback() {
                @Override
                public void control(Control control) {
                    lastControl = control;
                }

                @Override
                public void cancel(CancelReason reason) {
                    cancelReason = reason;
                    cancels.incrementAndGet();
                    onCancel.accept(candidate);
                    if (throwsOnCancel) {
                        throw new IllegalStateException("fixture cancel failure");
                    }
                    if (completeOnCancel) {
                        terminal.complete(Terminal.cancelled(reason));
                    }
                }

                @Override
                public java.util.concurrent.CompletionStage<Terminal> terminal() {
                    terminalRequestedInsidePlay.set(inPlay.get());
                    return terminal;
                }
            });
            inPlay.set(false);
            return result;
        }
    }

    private record CountingContent(ModelContent delegate, AtomicInteger reads) implements ModelContent {
        @Override
        public ModelRepresentation representation() {
            return delegate.representation();
        }

        @Override
        public ChunkDataSource chunks() {
            return new ChunkDataSource() {
                @Override
                public SharedBuffer readPayload(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                    throws IOException {
                    reads.incrementAndGet();
                    return delegate.chunks().readPayload(chunk, bufferType);
                }

                @Override
                public SharedBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk, BufferType bufferType)
                    throws IOException {
                    return delegate.chunks().readStoredVerified(chunk, bufferType);
                }
            };
        }
    }
}
