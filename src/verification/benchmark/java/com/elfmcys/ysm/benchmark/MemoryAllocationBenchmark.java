package com.elfmcys.ysm.benchmark;

import com.elfmcys.ysm.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class MemoryAllocationBenchmark {
    @Param({"64", "4096", "1048576", "134217728"})
    public int bytes;

    @Setup(Level.Trial)
    public void setup() {
        if (bytes <= 0)
            throw new IllegalArgumentException("Allocation size must be positive");
        JniEntryBenchmark.loadLibrary();
        System.out.println("LWJGL allocator: " + MemoryUtil.getAllocator(true).getClass().getName());
        for (var allocator : AllocationMode.values()) {
            var buffers = new ByteBuffer[1];
            long address = allocator.allocate(bytes, buffers, 0);
            try {
                verify(address, bytes, (byte) 0x5A);
            } finally {
                allocator.free(address, buffers, 0);
            }
        }
    }

    @Benchmark
    public long mallocFree() {
        long address = requireAddress(nMalloc(bytes));
        nFree(address);
        return address;
    }

    @Benchmark
    public long mimallocFree() {
        long address = requireAddress(nMiMalloc(bytes));
        nMiFree(address);
        return address;
    }

    @Benchmark
    public long directBufferClean() {
        var buffer = ByteBuffer.allocateDirect(bytes);
        long address = MemoryUtil.memAddress(buffer);
        UnsafeUtil.getUnsafe().invokeCleaner(buffer);
        return address;
    }

    @Benchmark
    public long lwjglAllocFree() {
        var buffer = MemoryUtil.memAlloc(bytes);
        long address = MemoryUtil.memAddress(buffer);
        MemoryUtil.memFree(buffer);
        return address;
    }

    static long requireAddress(long address) {
        if (address == 0)
            throw new OutOfMemoryError("Native benchmark allocation failed");
        return address;
    }

    static void verify(long address, int bytes, byte marker) {
        requireAddress(address);
        var unsafe = UnsafeUtil.getUnsafe();
        unsafe.putByte(address, marker);
        unsafe.putByte(address + bytes - 1L, (byte) ~marker);
        check(address, bytes, marker);
    }

    static void check(long address, int bytes, byte marker) {
        var unsafe = UnsafeUtil.getUnsafe();
        if (unsafe.getByte(address) != marker || unsafe.getByte(address + bytes - 1L) != (byte) ~marker)
            throw new IllegalStateException("Live allocation contents changed");
    }

    static native long nMalloc(int bytes);
    static native void nFree(long address);
    static native long nMiMalloc(int bytes);
    static native void nMiFree(long address);
}

enum AllocationMode {
    MALLOC {
        @Override long allocate(int bytes, ByteBuffer[] buffers, int slot) {
            return MemoryAllocationBenchmark.requireAddress(MemoryAllocationBenchmark.nMalloc(bytes));
        }
        @Override void free(long address, ByteBuffer[] buffers, int slot) {
            MemoryAllocationBenchmark.nFree(address);
        }
    },
    MIMALLOC {
        @Override long allocate(int bytes, ByteBuffer[] buffers, int slot) {
            return MemoryAllocationBenchmark.requireAddress(MemoryAllocationBenchmark.nMiMalloc(bytes));
        }
        @Override void free(long address, ByteBuffer[] buffers, int slot) {
            MemoryAllocationBenchmark.nMiFree(address);
        }
    },
    DIRECT {
        @Override long allocate(int bytes, ByteBuffer[] buffers, int slot) {
            var buffer = ByteBuffer.allocateDirect(bytes);
            buffers[slot] = buffer;
            return MemoryUtil.memAddress(buffer);
        }
        @Override void free(long address, ByteBuffer[] buffers, int slot) {
            UnsafeUtil.getUnsafe().invokeCleaner(buffers[slot]);
            buffers[slot] = null;
        }
    },
    LWJGL {
        @Override long allocate(int bytes, ByteBuffer[] buffers, int slot) {
            var buffer = MemoryUtil.memAlloc(bytes);
            buffers[slot] = buffer;
            return MemoryUtil.memAddress(buffer);
        }
        @Override void free(long address, ByteBuffer[] buffers, int slot) {
            MemoryUtil.memFree(buffers[slot]);
            buffers[slot] = null;
        }
    };

    abstract long allocate(int bytes, ByteBuffer[] buffers, int slot);
    abstract void free(long address, ByteBuffer[] buffers, int slot);
}
