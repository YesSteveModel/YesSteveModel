package com.elfmcys.ysm.format.schema.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ChannelReadPhaseTest {
    @TempDir
    Path root;

    @Test
    void concurrentCursorsReadActualRangesAndBorrowCloseCannotCloseTheOwner() throws Exception {
        var file = root.resolve("input");
        Files.write(file, new byte[] {1, 2, 3, 4});
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var escaped = new AtomicReference<SeekableByteChannel>();
        try (var phase = phase(file, opens, closes)) {
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> phase.read(channel -> {
                    channel.position(0);
                    Thread.yield();
                    var bytes = ByteBuffer.allocate(2);
                    channel.read(bytes);
                    escaped.set(channel);
                    channel.close();
                    return bytes.array();
                }));
                var second = executor.submit(() -> phase.read(channel -> {
                    channel.position(2);
                    Thread.yield();
                    var bytes = ByteBuffer.allocate(2);
                    channel.read(bytes);
                    return bytes.array();
                }));
                assertArrayEquals(new byte[] {1, 2}, first.get(5, TimeUnit.SECONDS));
                assertArrayEquals(new byte[] {3, 4}, second.get(5, TimeUnit.SECONDS));
                assertThrows(ClosedChannelException.class, () -> escaped.get().position(0));
                assertEquals(0, closes.get());
            } finally {
                executor.shutdownNow();
            }
        }
        assertEquals(1, opens.get());
        assertEquals(1, closes.get());
    }

    @Test
    void ownerSealsImmediatelyAndLastActiveBorrowPhysicallyCloses() throws Exception {
        var file = root.resolve("input");
        Files.write(file, new byte[] {7});
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var phase = phase(file, opens, closes);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var active = executor.submit(() -> phase.read(channel -> {
                entered.countDown();
                try {
                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new IOException(failure);
                }
                var bytes = ByteBuffer.allocate(1);
                channel.read(bytes);
                return bytes.array()[0];
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            phase.close();
            assertEquals(0, closes.get());
            assertThrows(ClosedChannelException.class, () -> phase.read(channel -> 0));
            resume.countDown();
            assertEquals((byte)7, active.get(5, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            phase.close();
            assertEquals(1, closes.get());
            try (var reopened = phase(file, opens, closes)) {
                assertEquals(1L, reopened.read(SeekableByteChannel::size));
            }
            assertEquals(2, opens.get());
            assertEquals(2, closes.get());
        } finally {
            resume.countDown();
            phase.close();
            executor.shutdownNow();
        }
    }

    @Test
    void contentCancellationAndResourceFailuresSealAndCloseWithoutReopen() throws Exception {
        var file = root.resolve("input");
        Files.write(file, new byte[] {1});
        for (var failure : new Throwable[] {new IOException("damaged"),
            new java.util.concurrent.CancellationException(), new OutOfMemoryError("injected")}) {
            var opens = new AtomicInteger();
            var closes = new AtomicInteger();
            try (var phase = phase(file, opens, closes)) {
                assertSame(failure, assertThrows(failure.getClass(), () -> phase.read(channel -> {
                    if (failure instanceof IOException io)
                        throw io;
                    if (failure instanceof RuntimeException runtime)
                        throw runtime;
                    throw (Error)failure;
                })));
                assertEquals(1, closes.get());
                assertThrows(ClosedChannelException.class, () -> phase.read(channel -> 0));
            }
            assertEquals(1, opens.get());
            assertEquals(1, closes.get());
        }
    }

    @Test
    void failureAfterPhysicalOpenBeforeScopeDeliveryClosesTheChannel() throws Exception {
        var file = root.resolve("input");
        Files.write(file, new byte[] {1});
        var closes = new AtomicInteger();
        var failure = new OutOfMemoryError("injected admission failure");
        assertSame(failure, assertThrows(OutOfMemoryError.class,
            () -> ChannelReadPhase.open(() -> counting(file, new AtomicInteger(), closes), channel -> {
                throw failure;
            })));
        assertEquals(1, closes.get());
    }

    private static ChannelReadPhase phase(Path file, AtomicInteger opens, AtomicInteger closes) throws IOException {
        return ChannelReadPhase.open(() -> counting(file, opens, closes), channel -> {
        });
    }

    @Test
    void closeFailureIsExplicitAndCannotTurnAReadFailureIntoAnOrdinaryMiss() throws Exception {
        var file = root.resolve("close-failure");
        Files.write(file, new byte[] {1});
        for (boolean failRead : new boolean[] {false, true}) {
            var closes = new AtomicInteger();
            var readFailure = new IOException("payload failure");
            var phase = ChannelReadPhase.open(() -> new FileChunkBatchTest.CountingChannel(FileChannel.open(file),
                new java.util.concurrent.atomic.AtomicLong(), () -> {
                }) {
                @Override
                public void close() throws IOException {
                    super.close();
                    closes.incrementAndGet();
                    throw new IOException("close failed after physical close");
                }
            }, channel -> {
            });
            ChannelReadPhase.CloseFailure failure;
            if (failRead) {
                failure = assertThrows(ChannelReadPhase.CloseFailure.class, () -> phase.read(channel -> {
                    throw readFailure;
                }));
                assertSame(readFailure, failure.getSuppressed()[0]);
            } else {
                assertEquals(1L, phase.read(SeekableByteChannel::size));
                failure = assertThrows(ChannelReadPhase.CloseFailure.class, phase::close);
            }
            assertEquals("close failed after physical close", failure.getCause().getMessage());
            assertEquals(1, closes.get());
            phase.close();
            assertEquals(1, closes.get());
            assertThrows(ClosedChannelException.class, () -> phase.read(channel -> 0));
        }
    }

    @Test
    void borrowConstructionFailureAfterOpenSealsAndClosesWithoutAnOwnerRetry() throws Exception {
        var file = root.resolve("borrow");
        Files.write(file, new byte[] {1});
        var opens = new AtomicInteger();
        var closes = new AtomicInteger();
        var failure = new OutOfMemoryError("injected borrow admission failure");
        try (var phase = ChannelReadPhase.open(() -> counting(file, opens, closes), channel -> {
        }, () -> {
            throw failure;
        })) {
            assertSame(failure, assertThrows(OutOfMemoryError.class, () -> phase.read(channel -> 0)));
            assertThrows(ClosedChannelException.class, () -> phase.read(channel -> 0));
            assertEquals(1, opens.get());
            assertEquals(1, closes.get());
        }
        assertEquals(1, closes.get());
    }

    public static SeekableByteChannel counting(Path file, AtomicInteger opens, AtomicInteger closes)
        throws IOException {
        var channel = FileChannel.open(file);
        opens.incrementAndGet();
        return new FileChunkBatchTest.CountingChannel(channel, new java.util.concurrent.atomic.AtomicLong(), () -> {
        }) {
            @Override
            public void close() throws IOException {
                super.close();
                closes.incrementAndGet();
            }
        };
    }
}
