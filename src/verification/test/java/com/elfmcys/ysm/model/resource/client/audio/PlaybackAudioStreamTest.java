package com.elfmcys.ysm.model.resource.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaybackAudioStreamTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 16, 1, true, false);

    @Test
    void cachedPlaybacksHaveIndependentOneShotCursors() throws Exception {
        var bytes = samples(32_768);
        var pcm = new PcmAudio(bytes, FORMAT);
        var capacity = new AudioRuntimeCapacity(2, 0, 0, bytes.length * 2L);
        var closes = new AtomicInteger();
        var first = new PlaybackAudioStream(pcm, false, capacity, closes::incrementAndGet, ignored -> {
        });
        var second = new PlaybackAudioStream(pcm, false, capacity, closes::incrementAndGet, ignored -> {
        });

        assertArrayEquals(readAll(first, 511), readAll(second, 4095));
        assertEquals(2, closes.get());
        assertEquals(0, capacity.snapshot().decodedBytes());
    }

    @Test
    void cachedLoopCrossesTwoBoundariesWithoutSharingPosition() throws Exception {
        var bytes = samples(8_192);
        var capacity = new AudioRuntimeCapacity(1, 0, 0, bytes.length);
        var stream = new PlaybackAudioStream(new PcmAudio(bytes, FORMAT), true, capacity, () -> {
        }, ignored -> {
        });
        try (stream) {
            var actual = readExactly(stream, bytes.length * 2 + 510, 1023);
            var expected = new byte[actual.length];
            for (var index = 0; index < expected.length; ++index) {
                expected[index] = bytes[index % bytes.length];
            }
            assertArrayEquals(expected, actual);
        }
        assertEquals(0, capacity.snapshot().decodedBytes());
    }

    @Test
    void cachedPcmAdmissionUsesThePhysicalCapacityLedger() throws Exception {
        var bytes = samples(4_096);
        var capacity = new AudioRuntimeCapacity(1, 0, 0, bytes.length - 1L);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
            () -> new PlaybackAudioStream(new PcmAudio(bytes, FORMAT), false, capacity, () -> {
            }, ignored -> {
            }));
        assertTrue(failure.getMessage().contains("capacity"));
        assertEquals(0, capacity.snapshot().decodedBytes());
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "YSM_NATIVE_PATH", matches = ".+")
    @ExtendWith(NativeLibraryExtension.class)
    class PrefixPlayback {
        @TempDir
        Path temp;

        @Test
        void exactPrefixConsumptionAndZeroDemandDoNotCreateADecoder() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
                var prefix = fixture.prefix();
                var workers = new ManualExecutor();
                var capacity = new AudioRuntimeCapacity();
                long creates = AudioPlaybackFixture.nativeCount("nCreateCount");
                long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
                var stream = stream(fixture, prefix, false, workers, Runnable::run, capacity, new AtomicReference<>());
                try (stream) {
                    assertEquals(prefix.retainedBytes(), capacity.snapshot().decodedBytes());
                    assertEquals(0, stream.consumePcm(0));
                    assertEquals(prefix.pcm().size(), stream.consumePcm(prefix.pcm().size()));
                    assertArrayEquals(bytes(prefix.pcm().read(0, prefix.pcm().size())),
                        bytes(stream.read(prefix.pcm().size())));
                    assertEquals(creates, AudioPlaybackFixture.nativeCount("nCreateCount"));
                    assertEquals(restores, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    assertTrue(fixture.reads.isEmpty());
                    assertEquals(0, capacity.snapshot().encodedBytes());

                    assertEquals(2, stream.consumePcm(2));
                    assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    assertEquals(creates, AudioPlaybackFixture.nativeCount("nCreateCount"));
                    assertEquals(java.util.List.of(1), fixture.reads);
                    assertEquals(2, stream.consumePcm(2));
                    assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                }
                workers.runAll();
                assertIdle(capacity);
            }
        }

        @Test
        void delayedTailYieldsAndCancellationClosesItsLateInput() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
                var prefix = fixture.prefix();
                var workers = new ManualExecutor();
                var inputs = new ManualExecutor();
                var capacity = new AudioRuntimeCapacity();
                var stream = stream(fixture, prefix, false, workers, inputs, capacity, new AtomicReference<>());
                assertEquals(prefix.pcm().size(), stream.consumePcm(prefix.pcm().size() + 2));
                assertFalse(stream.prepared().isDone());
                assertTrue(capacity.snapshot().encodedBytes() > 0);
                stream.close();
                inputs.runAll();
                workers.runAll();
                assertTrue(stream.prepared().isCompletedExceptionally());
                assertIdle(capacity);
            }
        }

        @Test
        void pendingLastChunkSurvivesRepeatedConsumptionBeforePreparation() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-origin.ogg")) {
                var expected = fixture.reference();
                var prefix = fixture.prefix();
                assertTrue(fixture.media.frames() < 4 * 48_000);
                for (boolean cached : new boolean[] {false, true}) {
                    var workers = new ManualExecutor();
                    var delayed = new ManualExecutor();
                    var capacity = new AudioRuntimeCapacity();
                    var failure = new AtomicReference<Throwable>();
                    var stream = stream(fixture, cached ? prefix : null, false, workers,
                        delayLastChunk(fixture, delayed), capacity, failure);
                    try (stream) {
                        workers.runAll();
                        assertEquals(1, delayed.tasks.size());
                        assertFalse(stream.prepared().isDone());
                        for (int attempt = 0; attempt < 3; attempt++) {
                            assertEquals(0, stream.consumePcm(8192));
                            workers.runAll();
                            assertNull(failure.get());
                            assertFalse(stream.isClosed());
                        }
                        delayed.runAll();
                        workers.runAll();
                        assertTrue(stream.prepared().isDone());
                        stream.prepared().join();
                        assertArrayEquals(expected, readAll(stream, 8192));
                        assertNull(failure.get());
                    }
                    workers.runAll();
                    assertIdle(capacity);
                }
            }
        }

        @Test
        void pendingLastChunkSurvivesHostRefillsForColdHotAndLoopingPlayback() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/mono-four-plus.ogg")) {
                var expected = fixture.reference();
                var prefix = fixture.prefix();
                for (boolean cached : new boolean[] {false, true}) {
                    for (boolean looping : new boolean[] {false, true}) {
                        var workers = new ManualExecutor();
                        var delayed = new ManualExecutor();
                        var capacity = new AudioRuntimeCapacity();
                        var failure = new AtomicReference<Throwable>();
                        var stream = stream(fixture, cached ? prefix : null, looping, workers,
                            delayLastChunk(fixture, delayed), capacity, failure);
                        try (stream) {
                            workers.runAll();
                            assertTrue(stream.prepared().isDone());
                            stream.prepared().join();
                            var actual = new ByteArrayOutputStream();
                            while (delayed.tasks.isEmpty()) {
                                assertTrue(actual.size() < expected.length);
                                var chunk = stream.read(8192);
                                assertTrue(chunk.hasRemaining());
                                actual.writeBytes(bytes(chunk));
                                workers.runAll();
                                assertNull(failure.get());
                            }
                            // A refill must wait even while the host still has buffered PCM.
                            var chunk = stream.read(2 * 96_000);
                            assertTrue(chunk.hasRemaining());
                            actual.writeBytes(bytes(chunk));
                            workers.runAll();
                            assertNull(failure.get());
                            assertFalse(stream.isClosed());
                            assertEquals(1, delayed.tasks.size());
                            delayed.runAll();
                            workers.runAll();
                            int length = expected.length * (looping ? 2 : 1);
                            while (actual.size() < length) {
                                chunk = stream.read(Math.min(8192, length - actual.size()));
                                assertTrue(chunk.hasRemaining());
                                actual.writeBytes(bytes(chunk));
                                workers.runAll();
                                assertNull(failure.get());
                            }
                            var pcm = actual.toByteArray();
                            assertArrayEquals(expected, Arrays.copyOf(pcm, expected.length));
                            if (looping) {
                                assertArrayEquals(expected, Arrays.copyOfRange(pcm, expected.length, pcm.length));
                            } else {
                                assertFalse(stream.read(8192).hasRemaining());
                                assertTrue(stream.isClosed());
                            }
                        }
                        workers.runAll();
                        assertIdle(capacity);
                    }
                }
            }
        }

        @Test
        void chunkSchedulingFailureReturnsTheUnadoptedEncodedReservation() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
                var failure = new OutOfMemoryError("Injected chunk scheduling failure");
                var reported = new AtomicReference<Throwable>();
                var workers = new ManualExecutor();
                var capacity = new AudioRuntimeCapacity();
                var access = VerifiedChunkAccess.create(fixture.source, fixture.content, null, Runnable::run, task -> {
                    throw failure;
                });
                var stream = new PlaybackAudioStream(fixture.media, access, false, workers, capacity, receipt(),
                    () -> true, (audio, ignored) -> true, () -> {
                    }, reported::set);
                workers.runAll();
                assertTrue(stream.isClosed());
                assertTrue(stream.prepared().isCompletedExceptionally());
                org.junit.jupiter.api.Assertions.assertSame(failure, reported.get());
                assertIdle(capacity);
            }
        }

        @Test
        void workerSchedulingFailureReturnsTheCachedReservation() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
                var prefix = fixture.prefix();
                var failure = new OutOfMemoryError("Injected playback scheduling failure");
                var reported = new AtomicReference<Throwable>();
                var capacity = new AudioRuntimeCapacity();
                final PlaybackAudioStream stream;
                try {
                    stream = stream(fixture, prefix, false, task -> {
                        throw failure;
                    }, Runnable::run, capacity, reported);
                } catch (OutOfMemoryError escaped) {
                    assertIdle(capacity);
                    throw new AssertionError("Scheduling failure escaped without a playback terminal", escaped);
                }
                assertTrue(stream.isClosed());
                assertTrue(stream.prepared().isCompletedExceptionally());
                org.junit.jupiter.api.Assertions.assertSame(failure, reported.get());
                assertIdle(capacity);
            }
        }

        @Test
        void theSameDeficitRestoresWithEitherAnEmptyOrAPartlyFilledReadyQueue() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-long.ogg")) {
                var prefix = fixture.prefix();
                var workers = new ManualExecutor();
                var capacity = new AudioRuntimeCapacity();
                long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
                var empty = stream(fixture, prefix, false, workers, Runnable::run, capacity, new AtomicReference<>());
                var queued = stream(fixture, prefix, false, workers, Runnable::run, capacity, new AtomicReference<>());
                try (empty; queued) {
                    assertEquals(prefix.pcm().size(), empty.consumePcm(prefix.pcm().size()));
                    var drained = bytes(empty.read(prefix.pcm().size()));
                    assertEquals(prefix.pcm().size(), queued.consumePcm(prefix.pcm().size()));
                    assertEquals(restores, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    assertEquals(2, empty.consumePcm(2));
                    assertEquals(2, queued.consumePcm(2));
                    assertEquals(restores + 2, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    var combined = new ByteArrayOutputStream();
                    combined.writeBytes(drained);
                    combined.writeBytes(bytes(empty.read(2)));
                    assertArrayEquals(combined.toByteArray(), bytes(queued.read(prefix.pcm().size() + 2)));
                }
                workers.runAll();
                assertIdle(capacity);
            }
        }

        @Test
        void retentionRejectionStillLetsTheAcceptedLoopReuseItsPrefix() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/stereo-over-five.ogg")) {
                var expected = fixture.reference();
                var capacity = new AudioRuntimeCapacity();
                long creates = AudioPlaybackFixture.nativeCount("nCreateCount");
                long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
                var publications = new AtomicInteger();
                var stream = new PlaybackAudioStream(fixture.media, fixture.access(Runnable::run), true, Runnable::run,
                    capacity, receipt(), () -> true, (audio, ignored) -> {
                        publications.incrementAndGet();
                        return false;
                    }, () -> {
                    }, failure -> {
                        throw new AssertionError(failure);
                    });
                try (stream) {
                    var actual = readExactly(stream, expected.length * 2, 16_383);
                    assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
                    assertArrayEquals(expected, Arrays.copyOfRange(actual, expected.length, actual.length));
                    assertEquals(1, publications.get());
                    assertEquals(creates + 1, AudioPlaybackFixture.nativeCount("nCreateCount"));
                    assertTrue(AudioPlaybackFixture.nativeCount("nRestoreCount") >= restores + 1);
                }
                assertIdle(capacity);
            }
        }

        @Test
        void chunkCountClassifiesSingleLongAndMultipleShortAudio() throws Exception {
            for (var name : new String[] {"single-exact", "single-near-five", "single-long", "opus-origin"}) {
                boolean single = !name.equals("opus-origin");
                try (var fixture = AudioPlaybackFixture.create(temp.resolve(name),
                    (single ? "/audio-playback/" : "/audio-fixtures/") + name + ".ogg")) {
                    var published = new AtomicReference<CachedAudio>();
                    var capacity = new AudioRuntimeCapacity();
                    var stream = new PlaybackAudioStream(fixture.media, fixture.access(Runnable::run), false,
                        Runnable::run, capacity, receipt(), () -> true, (audio, ignored) -> {
                            published.set(audio);
                            return true;
                        }, () -> {
                        }, failure -> {
                            throw new AssertionError(failure);
                        });
                    if (fixture.media.frames() > 5 * 48_000) {
                        assertEquals(null, published.get(), "Complete publication requires a verified EOF");
                    }
                    var actual = readAll(stream, 8191);
                    assertArrayEquals(fixture.reference(), actual);
                    if (single) {
                        assertTrue(fixture.media.frames() >= 4 * 48_000);
                        assertInstanceOf(CachedAudio.Complete.class, published.get());
                    } else {
                        assertTrue(fixture.media.frames() < 4 * 48_000);
                        assertInstanceOf(CachedAudio.Prefix.class, published.get());
                    }
                    assertIdle(capacity);
                }
            }
        }

        @Test
        void aCandidateCannotStarveReadyPcmAndAnUncachedLoopStillCompletes() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-playback/single-long.ogg")) {
                var expected = fixture.reference();
                var capacity = new AudioRuntimeCapacity(1, 1, 1 << 20, 550_000);
                var publications = new AtomicInteger();
                var stream = new PlaybackAudioStream(fixture.media, fixture.access(Runnable::run), true, Runnable::run,
                    capacity, receipt(), () -> true, (audio, ignored) -> {
                        publications.incrementAndGet();
                        return true;
                    }, () -> {
                    }, failure -> {
                        throw new AssertionError(failure);
                    });
                try (stream) {
                    var actual = readExactly(stream, expected.length * 2, 16_383);
                    assertArrayEquals(expected, Arrays.copyOf(actual, expected.length));
                    assertArrayEquals(expected, Arrays.copyOfRange(actual, expected.length, actual.length));
                    assertEquals(0, publications.get());
                }
                assertIdle(capacity);
            }
        }

        @Test
        void monoAndStereoColdHotAndLoopMatchContinuousNativeOutput() throws Exception {
            for (var resource : new String[] {"/audio-playback/mono-long.ogg", "/audio-playback/mono-four-plus.ogg",
                "/audio-playback/mono-over-five.ogg", "/audio-fixtures/opus-long.ogg",
                "/audio-playback/stereo-four-plus.ogg", "/audio-playback/stereo-over-five.ogg"}) {
                try (var fixture =
                    AudioPlaybackFixture.create(temp.resolve(Path.of(resource).getFileName().toString()), resource)) {
                    var expected = fixture.reference();
                    assertEquals(fixture.media.frames() * Short.BYTES, expected.length);
                    var capacity = new AudioRuntimeCapacity();
                    var published = new AtomicReference<CachedAudio>();
                    var cold = new PlaybackAudioStream(fixture.media, fixture.access(Runnable::run), false,
                        Runnable::run, capacity, receipt(), () -> true, (audio, ignored) -> {
                            published.set(audio);
                            return true;
                        }, () -> {
                        }, failure -> {
                            throw new AssertionError(failure);
                        });
                    assertEquals(1, cold.getFormat().getChannels());
                    assertArrayEquals(expected, readAll(cold, 16_383));
                    var prefix = assertInstanceOf(CachedAudio.Prefix.class, published.get());
                    assertEquals(prefix.pcm().size() / Short.BYTES, prefix.checkpoint().emittedFrames());
                    assertTrue(!resource.contains("over-five") || prefix.pcm().size() > 5 * 96_000);
                    var hot =
                        stream(fixture, prefix, false, Runnable::run, Runnable::run, capacity, new AtomicReference<>());
                    assertArrayEquals(expected, readAll(hot, 12_287));
                    var loop =
                        stream(fixture, prefix, true, Runnable::run, Runnable::run, capacity, new AtomicReference<>());
                    try (loop) {
                        var actual = readExactly(loop, expected.length * 2 + 510, 15_361);
                        for (int offset = 0; offset < actual.length; offset += expected.length) {
                            assertArrayEquals(
                                Arrays.copyOf(expected, Math.min(expected.length, actual.length - offset)),
                                Arrays.copyOfRange(actual, offset, Math.min(actual.length, offset + expected.length)));
                        }
                    }
                    assertIdle(capacity);
                }
            }
        }

        @Test
        void oneConsumptionRequestCrossesTheLoopAndDefersNextRestore() throws Exception {
            try (var fixture = AudioPlaybackFixture.create(temp, "/audio-fixtures/opus-origin.ogg")) {
                var prefix = fixture.prefix();
                var expected = fixture.reference();
                var workers = new ManualExecutor();
                var capacity = new AudioRuntimeCapacity();
                long restores = AudioPlaybackFixture.nativeCount("nRestoreCount");
                var stream = stream(fixture, prefix, true, workers, Runnable::run, capacity, new AtomicReference<>());
                try (stream) {
                    int requested = expected.length + prefix.pcm().size();
                    assertEquals(requested, stream.consumePcm(requested));
                    var actual = bytes(stream.read(requested));
                    var oracle = new ByteArrayOutputStream();
                    oracle.writeBytes(expected);
                    oracle.writeBytes(Arrays.copyOf(expected, prefix.pcm().size()));
                    assertArrayEquals(oracle.toByteArray(), actual);
                    assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    assertEquals(0, stream.consumePcm(0));
                    assertEquals(restores + 1, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                    assertEquals(2, stream.consumePcm(2));
                    assertEquals(restores + 2, AudioPlaybackFixture.nativeCount("nRestoreCount"));
                }
                workers.runAll();
                assertIdle(capacity);
            }
        }
    }

    private static PlaybackAudioStream stream(AudioPlaybackFixture fixture, CachedAudio cached, boolean looping,
        Executor workers, Executor inputWorkers, AudioRuntimeCapacity capacity, AtomicReference<Throwable> failure)
        throws Exception {
        return new PlaybackAudioStream(fixture.media, fixture.access(inputWorkers), cached, looping, workers, capacity,
            receipt(), () -> true, (audio, ignored) -> true, () -> {
            }, failure::set);
    }

    private static AudioRetentionCache.Receipt receipt() {
        return new AudioRetentionCache.Receipt(System.nanoTime(), 1);
    }

    private static Executor delayLastChunk(AudioPlaybackFixture fixture, ManualExecutor delayed) {
        int lastRequest = fixture.media.chunkInfos().size() - 1;
        assertTrue(lastRequest > 0);
        var requests = new AtomicInteger();
        return task -> {
            if (requests.incrementAndGet() == lastRequest) {
                delayed.execute(task);
            } else {
                task.run();
            }
        };
    }

    private static void assertIdle(AudioRuntimeCapacity capacity) {
        assertEquals(0, capacity.snapshot().encodedBytes());
        assertEquals(0, capacity.snapshot().decodedBytes());
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }

    private static byte[] readAll(PlaybackAudioStream stream, int size) throws Exception {
        try (stream) {
            var output = new ByteArrayOutputStream();
            for (;;) {
                var chunk = stream.read(size);
                if (!chunk.hasRemaining()) {
                    return output.toByteArray();
                }
                output.writeBytes(bytes(chunk));
            }
        }
    }

    private static byte[] readExactly(PlaybackAudioStream stream, int length, int size) throws Exception {
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

    private static byte[] samples(int size) {
        var result = new byte[size];
        for (var index = 0; index < result.length; ++index) {
            result[index] = (byte)(index * 31);
        }
        return result;
    }
}
