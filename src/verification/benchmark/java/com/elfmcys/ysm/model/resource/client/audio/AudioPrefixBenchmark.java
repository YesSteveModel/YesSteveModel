package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.natives.NativeRuntime;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** The JMH score includes activation and cleanup; stage counters isolate the requested PCM operation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class AudioPrefixBenchmark {
    @State(Scope.Thread)
    public static class Input {
        @Param({"single", "ordinary", "shortPrefix", "overFive"})
        public String media;

        @Param({"8192"})
        public int requestedBytes;

        @Param({"cachedOnly", "coldPrepared", "hotPrepared", "firstGap", "tail", "loop", "coldCycle", "hotCycle"})
        public String phase;

        Path temporary;
        AudioPlaybackFixture fixture;
        CachedAudio cached;
        int cycleBytes;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            NativeRuntime.load(System.getenv("YSM_NATIVE_PATH"));
            NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(org.apache.logging.log4j.Level.OFF));
            temporary = Files.createTempDirectory("ysm-audio-prefix-");
            String resource = switch (media) {
                case "single" -> "/audio-playback/single-long.ogg";
                case "ordinary" -> "/audio-playback/stereo-four-plus.ogg";
                case "shortPrefix" -> "/audio-fixtures/opus-long.ogg";
                case "overFive" -> "/audio-playback/stereo-over-five.ogg";
                default -> throw new IllegalArgumentException(media);
            };
            fixture = AudioPlaybackFixture.create(temporary.resolve("fixture"), resource);
            var reference = fixture.reference();
            cycleBytes = reference.length;
            var published = new AtomicReference<CachedAudio>();
            var capacity = new AudioRuntimeCapacity();
            var actual = new ByteArrayOutputStream();
            try (var priming = new PlaybackAudioStream(fixture.media, fixture.access(Runnable::run), false,
                Runnable::run, capacity, new AudioRetentionCache.Receipt(System.nanoTime(), 1), () -> true,
                (audio, receipt) -> {
                    published.set(audio);
                    return true;
                }, () -> {}, failure -> { throw new IllegalStateException("Audio cache priming failed", failure); })) {
                for (;;) {
                    var pcm = priming.read(8192);
                    if (!pcm.hasRemaining()) {
                        break;
                    }
                    var bytes = new byte[pcm.remaining()];
                    pcm.get(bytes);
                    actual.writeBytes(bytes);
                }
            }
            if (!Arrays.equals(reference, actual.toByteArray()) || capacity.snapshot().decodedBytes() != 0
                || capacity.snapshot().encodedBytes() != 0) {
                throw new IllegalStateException("Cold playback priming changed PCM or retained active reservations");
            }
            cached = Objects.requireNonNull(published.get(), "Cold playback did not publish decoded audio");
            fixture.reads.clear();
            if (requestedBytes <= 0 || (requestedBytes & 1) != 0 || requestedBytes > cached.pcm().size()) {
                throw new IllegalArgumentException("requestedBytes must fit the cached PCM and contain whole frames");
            }
            System.out.println("AUDIO_INPUT media=" + media + " phase=" + phase + " requestedBytes="
                + requestedBytes + " frames=" + fixture.media.frames() + " prefixFrames="
                + cached.pcm().size() / Short.BYTES + " checkpointBytes="
                + (cached.retainedBytes() - cached.pcm().size()) + " retainedBytes=" + cached.retainedBytes()
                + " sourceChannels=" + fixture.media.channels());
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            fixture.close();
            var root = temporary.toAbsolutePath().normalize();
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) {
                throw new IllegalStateException("Audio benchmark cleanup escaped the temporary directory");
            }
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long operations;
        public long stageWallNs;
        public long stageCpuNs;
        public long suppliedBytes;
        public long pcmRequests;
        public long observedDecodedPeakBytes;
        public long creates;
        public long restores;
        private long createsBefore;
        private long restoresBefore;

        @Setup(Level.Iteration)
        public void setup() throws Exception {
            createsBefore = AudioPlaybackFixture.nativeCount("nCreateCount");
            restoresBefore = AudioPlaybackFixture.nativeCount("nRestoreCount");
        }

        @TearDown(Level.Iteration)
        public void close() throws Exception {
            creates = AudioPlaybackFixture.nativeCount("nCreateCount") - createsBefore;
            restores = AudioPlaybackFixture.nativeCount("nRestoreCount") - restoresBefore;
        }
    }

    @Benchmark
    public int consume(Input input, Counters counters) throws Exception {
        counters.operations++;
        var workers = new QueuedExecutor();
        var capacity = new AudioRuntimeCapacity();
        boolean coldCycle = input.phase.equals("coldCycle");
        boolean cold = input.phase.equals("coldPrepared") || coldCycle;
        var cyclePublication = coldCycle ? new AtomicReference<CachedAudio>() : null;
        PlaybackAudioStream.PcmPublisher publisher = cyclePublication == null ? (audio, receipt) -> true
            : (audio, receipt) -> {
                cyclePublication.set(audio);
                return true;
            };
        var cpu = ManagementFactory.getThreadMXBean();
        long startWall = System.nanoTime();
        long startCpu = cpu.getCurrentThreadCpuTime();
        var stream = new PlaybackAudioStream(input.fixture.media, input.fixture.access(Runnable::run),
            cold ? null : input.cached, input.phase.equals("loop"), workers, capacity,
            new AudioRetentionCache.Receipt(startWall, 1), () -> true, publisher, () -> {},
            failure -> { throw new IllegalStateException("Audio benchmark playback failed", failure); });
        try (stream) {
            // These snapshots can miss reservations inside a consume call.
            var observedDecoded = new long[] {capacity.snapshot().decodedBytes()};
            if (coldCycle || input.phase.equals("hotCycle")) {
                int supplied = 0;
                for (;;) {
                    counters.pcmRequests++;
                    int batch;
                    if (!cold && input.cached instanceof CachedAudio.Complete) {
                        batch = stream.read(input.requestedBytes).remaining();
                    } else {
                        batch = stream.consumePcm(input.requestedBytes);
                        observedDecoded[0] = Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
                        int delivered = stream.read(batch == 0 ? 2 : batch).remaining();
                        if (delivered != batch) {
                            throw new IllegalStateException("Cycle PCM was not delivered in order");
                        }
                    }
                    observedDecoded[0] = Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
                    if (batch == 0) {
                        break;
                    }
                    supplied += batch;
                }
                if (supplied != input.cycleBytes || !stream.isClosed()
                    || coldCycle && cyclePublication.get() == null) {
                    throw new IllegalStateException("Cycle did not reach a verified EOF with complete PCM");
                }
                counters.stageWallNs += System.nanoTime() - startWall;
                counters.stageCpuNs += cpu.getCurrentThreadCpuTime() - startCpu;
                counters.suppliedBytes += supplied;
                counters.observedDecodedPeakBytes += observedDecoded[0];
                return supplied;
            }
            if (input.phase.endsWith("Prepared")) {
                final long requestWall = startWall;
                final long requestCpu = startCpu;
                stream.prepared().thenRun(() -> {
                    counters.stageWallNs += System.nanoTime() - requestWall;
                    counters.stageCpuNs += cpu.getCurrentThreadCpuTime() - requestCpu;
                    observedDecoded[0] = Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
                });
                workers.runAll();
                stream.prepared().join();
                counters.observedDecodedPeakBytes += Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
                return stream.read(2).remaining();
            }
            if (input.phase.equals("firstGap") || input.phase.equals("tail")) {
                consumeAndDrain(stream, input.cached.pcm().size());
            }
            if (input.phase.equals("tail") && input.cached instanceof CachedAudio.Prefix) {
                consumeAndDrain(stream, 2);
            }
            if (input.phase.equals("loop")) {
                consumeAndDrain(stream, input.cycleBytes);
            }
            observedDecoded[0] = Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
            startWall = System.nanoTime();
            startCpu = cpu.getCurrentThreadCpuTime();
            int supplied;
            if (input.phase.equals("tail") && input.cached instanceof CachedAudio.Prefix) {
                supplied = 0;
                for (;;) {
                    counters.pcmRequests++;
                    int batch = stream.consumePcm(input.requestedBytes);
                    observedDecoded[0] = Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
                    if (batch == 0) {
                        if (stream.read(2).hasRemaining()) {
                            throw new IllegalStateException("Tail EOF left unread PCM");
                        }
                        break;
                    }
                    if (stream.read(batch).remaining() != batch) {
                        throw new IllegalStateException("Tail PCM was not delivered in order");
                    }
                    supplied += batch;
                }
                int remainingTail = input.cycleBytes - input.cached.pcm().size() - 2;
                if (supplied != remainingTail) {
                    throw new IllegalStateException("Tail PCM did not match the declared frame count");
                }
            } else if (input.cached instanceof CachedAudio.Complete) {
                counters.pcmRequests++;
                supplied = stream.read(input.requestedBytes).remaining();
            } else {
                counters.pcmRequests++;
                supplied = stream.consumePcm(input.requestedBytes);
                observedDecoded[0] = Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
                stream.read(supplied);
            }
            counters.stageWallNs += System.nanoTime() - startWall;
            counters.stageCpuNs += cpu.getCurrentThreadCpuTime() - startCpu;
            counters.suppliedBytes += supplied;
            counters.observedDecodedPeakBytes += Math.max(observedDecoded[0], capacity.snapshot().decodedBytes());
            return supplied;
        } finally {
            workers.runAll();
            input.fixture.reads.clear();
            if (capacity.snapshot().decodedBytes() != 0 || capacity.snapshot().encodedBytes() != 0) {
                throw new IllegalStateException("Audio benchmark retained an active reservation");
            }
        }
    }

    private static void consumeAndDrain(PlaybackAudioStream stream, int bytes) throws Exception {
        int remaining = bytes;
        while (remaining > 0) {
            int count = Math.min(remaining, 64 * 1024);
            int supplied = stream.consumePcm(count);
            var output = stream.read(count);
            if (output.remaining() == 0 || supplied == 0 && !stream.prepared().isDone()) {
                throw new IllegalStateException("Resident audio fixture did not satisfy PCM demand");
            }
            remaining -= output.remaining();
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}
