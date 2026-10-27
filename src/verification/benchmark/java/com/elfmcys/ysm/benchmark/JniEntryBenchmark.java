package com.elfmcys.ysm.benchmark;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class JniEntryBenchmark {
    private int value = 42;

    @Setup(Level.Trial)
    public void setup() {
        loadLibrary();
        for (int input : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            if (nRawIdentity(input) != input || nEntryIdentity(input) != input)
                throw new IllegalStateException("JNI benchmark identity mismatch");
        }
        nRawVoid();
        nEntryVoid();
    }

    static void loadLibrary() {
        var library = System.getenv("YSM_JNI_BENCHMARK_PATH");
        if (library == null || library.isBlank())
            throw new IllegalStateException("Set YSM_JNI_BENCHMARK_PATH to the absolute JNI benchmark library path");
        System.load(library);
    }

    @Benchmark
    public void rawVoid() {
        nRawVoid();
    }

    @Benchmark
    public void entryVoid() {
        nEntryVoid();
    }

    @Benchmark
    public int rawIdentity() {
        return nRawIdentity(value);
    }

    @Benchmark
    public int entryIdentity() {
        return nEntryIdentity(value);
    }

    private static native void nRawVoid();

    private static native void nEntryVoid();

    private static native int nRawIdentity(int value);

    private static native int nEntryIdentity(int value);
}
