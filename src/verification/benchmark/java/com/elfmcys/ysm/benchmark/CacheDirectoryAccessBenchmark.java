package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.model.storage.CacheDirectoryGuard;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CacheDirectoryAccessBenchmark {
    @Param({"1", "4", "8"})
    public int depth;

    private Path root;
    private Path target;
    private CacheDirectoryGuard guard;
    private CacheDirectoryGuard.Observation observation;
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(1);

    @Setup(Level.Trial)
    public void setup() throws IOException {
        root = Files.createTempDirectory("ysm-directory-benchmark-").toRealPath();
        var parent = root;
        for (int index = 0; index < depth; index++) parent = parent.resolve("level-" + index);
        Files.createDirectories(parent);
        target = Files.write(parent.resolve("chunk"), new byte[] {42});
        guard = CacheDirectoryGuard.open(root);
        observation = guard.observe(target, false);
    }

    @Benchmark
    public int observeAndRead() throws IOException {
        return read(guard.observe(target, false));
    }

    @Benchmark
    public int reopenAndRead() throws IOException {
        return read(observation);
    }

    private int read(CacheDirectoryGuard.Observation observed) throws IOException {
        byteBuffer.clear();
        try (var channel = observed.open(Set.of(StandardOpenOption.READ))) {
            return channel.read(byteBuffer);
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
