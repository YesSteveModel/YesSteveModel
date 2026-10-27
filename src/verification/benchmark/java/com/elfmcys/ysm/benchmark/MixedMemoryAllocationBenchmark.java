package com.elfmcys.ysm.benchmark;

import org.lwjgl.system.MemoryUtil;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class MixedMemoryAllocationBenchmark {
    private static final int PAIRS = 1024;
    private static final int SLOTS = 64;
    private static final long LIVE_BYTE_LIMIT = 256L << 20;

    @Param({"MALLOC", "MIMALLOC", "DIRECT", "LWJGL"})
    public String allocator;

    @Param({"42", "20261019"})
    public long seed;

    private AllocationMode mode;
    private Operation[] trace;
    private final long[] addresses = new long[SLOTS];
    private final ByteBuffer[] buffers = new ByteBuffer[SLOTS];

    private record Operation(int slot, int bytes) {}

    @Setup(Level.Trial)
    public void setup() {
        JniEntryBenchmark.loadLibrary();
        mode = AllocationMode.valueOf(allocator);
        trace = generate(seed, PAIRS);
        System.out.println("Allocation trace seed=" + seed + ", hash=" + Arrays.hashCode(trace));
        System.out.println("LWJGL allocator: " + MemoryUtil.getAllocator(true).getClass().getName());
        replay(true);
    }

    @Benchmark
    @OperationsPerInvocation(PAIRS)
    public long mixedAllocateFree() {
        return replay(false);
    }

    private long replay(boolean verify) {
        int[] sizes = verify ? new int[SLOTS] : null;
        byte[] markers = verify ? new byte[SLOTS] : null;
        long checksum = 0;
        try {
            for (int i = 0; i < trace.length; ++i) {
                var operation = trace[i];
                int slot = operation.slot;
                if (operation.bytes != 0) {
                    long address = mode.allocate(operation.bytes, buffers, slot);
                    addresses[slot] = address;
                    checksum ^= address;
                    if (verify) {
                        sizes[slot] = operation.bytes;
                        markers[slot] = (byte) i;
                        MemoryAllocationBenchmark.verify(address, sizes[slot], markers[slot]);
                    }
                } else {
                    if (verify)
                        MemoryAllocationBenchmark.check(addresses[slot], sizes[slot], markers[slot]);
                    mode.free(addresses[slot], buffers, slot);
                    addresses[slot] = 0;
                }
            }
        } finally {
            // A failed verification must not keep native allocations alive.
            for (int slot = 0; slot < SLOTS; ++slot) {
                if (addresses[slot] != 0) {
                    mode.free(addresses[slot], buffers, slot);
                    addresses[slot] = 0;
                }
            }
        }
        return checksum;
    }

    private static Operation[] generate(long seed, int pairs) {
        var random = new SplittableRandom(seed);
        var result = new ArrayList<Operation>(pairs * 2);
        int[] liveSlots = new int[SLOTS];
        int[] sizes = new int[SLOTS];
        int live = 0;
        long liveBytes = 0;
        int allocated = 0;
        while (allocated < pairs || live != 0) {
            boolean allocate = allocated < pairs && live < SLOTS && (live == 0 || random.nextInt(100) < 60);
            if (allocate) {
                int choice = random.nextInt(100);
                int bytes = choice < 60 ? 64 : choice < 90 ? 4096 : choice < 99 ? 1048576 : 134217728;
                while (liveBytes + bytes > LIVE_BYTE_LIMIT) {
                    int index = random.nextInt(live);
                    int slot = liveSlots[index];
                    result.add(new Operation(slot, 0));
                    liveBytes -= sizes[slot];
                    sizes[slot] = 0;
                    liveSlots[index] = liveSlots[--live];
                }
                int slot = 0;
                while (sizes[slot] != 0)
                    ++slot;
                sizes[slot] = bytes;
                liveSlots[live++] = slot;
                liveBytes += bytes;
                result.add(new Operation(slot, bytes));
                ++allocated;
            } else {
                int index = random.nextInt(live);
                int slot = liveSlots[index];
                result.add(new Operation(slot, 0));
                liveBytes -= sizes[slot];
                sizes[slot] = 0;
                liveSlots[index] = liveSlots[--live];
            }
        }
        if (result.size() != pairs * 2 || liveBytes != 0)
            throw new IllegalStateException("Allocation trace did not drain");
        return result.toArray(Operation[]::new);
    }

    public static void main(String[] args) {
        int seeds = args.length == 0 ? 32 : Integer.parseInt(args[0]);
        if (seeds <= 0)
            throw new IllegalArgumentException("Seed count must be positive");
        JniEntryBenchmark.loadLibrary();
        for (var mode : AllocationMode.values()) {
            for (int seed = 0; seed < seeds; ++seed) {
                var workload = new MixedMemoryAllocationBenchmark();
                workload.mode = mode;
                workload.trace = generate(seed, 128);
                workload.replay(true);
            }
        }
        System.out.println("Verified " + seeds + " seeds for each allocation mode");
    }
}
