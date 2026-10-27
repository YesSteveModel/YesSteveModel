package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.ProtocolBuffer;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.ChunkFragmentProto;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RangePacketTest {
    @TempDir
    Path root;

    @Test
    void bytesAreIsolatedOnceAndEveryCursorRangeIsStable() {
        var contents = new byte[RangePacket.FRAGMENT_BYTES * 2 + 3];
        for (var index = 0; index < contents.length; index++) {
            contents[index] = (byte)index;
        }
        var expected = contents.clone();
        var closes = new AtomicInteger();
        var packet = RangePacket.bytes(contents, RangePacketTest::fragment, closes::incrementAndGet,
            ignored -> fail("stable bytes must not fail"));
        Arrays.fill(contents, (byte)0);

        for (var index = 0; index < packet.fragmentCount(); index++) {
            assertArrayEquals(attachment(packet.buildFragment(index)), attachment(packet.buildFragment(index)));
        }
        var rebuilt = new byte[expected.length];
        var offset = 0;
        for (var index = 0; index < packet.fragmentCount(); index++) {
            var part = attachment(packet.buildFragment(index));
            System.arraycopy(part, 0, rebuilt, offset, part.length);
            offset += part.length;
        }
        assertArrayEquals(expected, rebuilt);

        packet.close();
        packet.close();
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> packet.buildFragment(0));
    }

    @Test
    void fileRetryRebuildsTheSameCursorAndClosesItsLogicalLeaseOnce() throws Exception {
        var file = root.resolve("asset.bin");
        var contents = new byte[RangePacket.FRAGMENT_BYTES * 2 + 3];
        Arrays.fill(contents, (byte)1);
        Files.write(file, contents);
        var closes = new AtomicInteger();
        var failures = new AtomicInteger();
        var packet = filePacket(file, contents.length, closes, failures);
        for (var index = 0; index < packet.fragmentCount(); index++) {
            assertArrayEquals(attachment(packet.buildFragment(index)), attachment(packet.buildFragment(index)));
        }
        var port = new RetryingPort();
        var owner = new ServerModelSession(CatalogSnapshot::empty, false);
        try (var worker = new ResourceDispatchWorker(1, 2, 0)) {
            assertTrue(worker.enqueue(owner, port, List.of(packet)));
            assertTrue(worker.dispatchOnce());
            assertEquals(1, port.attempts.size());
            assertFilled(port.attempts.get(0), (byte)1);
            assertEquals(0, closes.get());

            Thread.sleep(110);
            assertTrue(worker.dispatchOnce());
            assertEquals(2, port.attempts.size());
            assertArrayEquals(port.attempts.get(0), port.attempts.get(1));
            assertEquals(0, closes.get());

            assertTrue(worker.dispatchOnce());
            assertEquals(3, port.attempts.size());
            assertFilled(port.attempts.get(2), (byte)1);
            assertEquals(0, closes.get());

            assertTrue(worker.dispatchOnce());
            assertEquals(4, port.attempts.size());
            assertArrayEquals(new byte[] {1, 1, 1}, port.attempts.get(3));
            assertEquals(1, closes.get());
            assertEquals(0, failures.get());
        }
        packet.close();
        assertEquals(1, closes.get());
    }

    @Test
    void fileRangeReadsFromJarFileSystemWithOffset() throws Exception {
        var archive = root.resolve("assets.jar");
        var prefix = new byte[] {9, 8, 7};
        var expected = new byte[RangePacket.FRAGMENT_BYTES + 3];
        for (var index = 0; index < expected.length; index++) {
            expected[index] = (byte)index;
        }
        var stored = new byte[prefix.length + expected.length + 2];
        System.arraycopy(prefix, 0, stored, 0, prefix.length);
        System.arraycopy(expected, 0, stored, prefix.length, expected.length);
        var closes = new AtomicInteger();
        var failures = new AtomicInteger();

        try (var fileSystem =
            FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            var file = fileSystem.getPath("/assets/ysm/builtin/ysm-pack.png");
            Files.createDirectories(file.getParent());
            Files.write(file, stored);
            var packet = RangePacket.file(fileSystem, file, prefix.length, expected.length, RangePacketTest::fragment,
                closes::incrementAndGet, ignored -> failures.incrementAndGet());

            var rebuilt = new byte[expected.length];
            var offset = 0;
            for (var index = 0; index < packet.fragmentCount(); index++) {
                var part = attachment(packet.buildFragment(index));
                System.arraycopy(part, 0, rebuilt, offset, part.length);
                offset += part.length;
            }
            assertArrayEquals(expected, rebuilt);
            packet.close();
        }

        assertEquals(1, closes.get());
        assertEquals(0, failures.get());
    }

    @Test
    void truncatedFileFailsProductionAndStillClosesTheLogicalLease() throws Exception {
        var file = root.resolve("truncated.bin");
        Files.write(file, new byte[RangePacket.FRAGMENT_BYTES + 1]);
        var closes = new AtomicInteger();
        var failures = new AtomicInteger();
        var packet = filePacket(file, RangePacket.FRAGMENT_BYTES + 1, closes, failures);
        Files.write(file, new byte[1]);
        var owner = new ServerModelSession(CatalogSnapshot::empty, false);
        try (var worker = new ResourceDispatchWorker(1, 2, 0)) {
            assertTrue(worker.enqueue(owner, new RetryingPort(), List.of(packet)));
            assertTrue(worker.dispatchOnce());
            assertEquals(1, closes.get());
            assertEquals(1, failures.get());
            assertEquals(0, worker.queued(owner));
        }
    }

    @Test
    void physicalCloseCallbackRunsOutsideTheDispatchSessionLock() throws Exception {
        var file = root.resolve("close-callback.bin");
        Files.write(file, new byte[] {1});
        var owner = new ServerModelSession(CatalogSnapshot::empty, false);
        var executor = Executors.newSingleThreadExecutor();
        var worker = new ResourceDispatchWorker(1, 2, 0);
        try (worker) {
            var packet = RangePacket.file(file, file, 0, 1, RangePacketTest::fragment, () -> {
                try {
                    assertEquals(0, executor.submit(() -> worker.queued(owner)).get(5, TimeUnit.SECONDS));
                } catch (Exception blocked) {
                    throw new AssertionError("Physical close callback held the dispatch session lock", blocked);
                }
            }, ignored -> fail("successful packet must not report failure"));
            assertTrue(worker.enqueue(owner, new RetryingPort() {
                @Override
                public SendResult trySend(OutboundFrame frame) {
                    return SendResult.SUCCESS;
                }
            }, List.of(packet)));

            assertTrue(worker.dispatchOnce());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeCallbackFailureDoesNotBlockTheNextAcceptedPacket() {
        var owner = new ServerModelSession(CatalogSnapshot::empty, false);
        var secondClosed = new AtomicInteger();
        var sent = new AtomicInteger();
        var first = RangePacket.bytes(new byte[] {1}, RangePacketTest::fragment, () -> {
            throw new IllegalStateException("injected close callback failure");
        }, ignored -> fail("successful packet must not report failure"));
        var second = RangePacket.bytes(new byte[] {2}, RangePacketTest::fragment, secondClosed::incrementAndGet,
            ignored -> fail("successful packet must not report failure"));
        var port = new TransportPort() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public boolean isWritable() {
                return true;
            }

            @Override
            public long highWatermarkBytes() {
                return 64 * 1024;
            }

            @Override
            public long pendingBytes() {
                return 0;
            }

            @Override
            public SendResult trySend(OutboundFrame frame) {
                sent.incrementAndGet();
                return SendResult.SUCCESS;
            }
        };
        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(owner, port, List.of(first, second)));
            assertTrue(worker.dispatchOnce());
            assertEquals(2, sent.get());
            assertEquals(1, secondClosed.get());
        }
    }

    private static RangePacket filePacket(Path file, int size, AtomicInteger closes, AtomicInteger failures) {
        return RangePacket.file(file, file, 0, size, RangePacketTest::fragment, closes::incrementAndGet,
            ignored -> failures.incrementAndGet());
    }

    private static ChunkFragmentProto fragment(long offset, boolean last) {
        return ChunkFragmentProto.newBuilder().setDataTransferId(1).setName("chunk").setOffset(offset)
            .setFinalFragment(last).build();
    }

    private static byte[] attachment(OutboundFrame frame) {
        try (frame; var decoded = FrameCodec.decode(frame.bytes(), id -> id == ProtocolMessages.CHUNK_FRAGMENT_ID);
            var attachment = decoded.attachment().acquireArray()) {
            var copy = new byte[attachment.size()];
            System.arraycopy(attachment.array(), attachment.arrayOffset(), copy, 0, copy.length);
            return copy;
        }
    }

    private static void assertFilled(byte[] bytes, byte expected) {
        assertEquals(RangePacket.FRAGMENT_BYTES, bytes.length);
        for (var value : bytes) {
            assertEquals(expected, value);
        }
    }

    private static class RetryingPort implements TransportPort {
        private final List<byte[]> attempts = new ArrayList<>();

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public long highWatermarkBytes() {
            return 64 * 1024;
        }

        @Override
        public long pendingBytes() {
            return 0;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            try (var decoded = FrameCodec.decode(frame.bytes(), id -> id == ProtocolMessages.CHUNK_FRAGMENT_ID);
                var attachment = decoded.attachment().acquireArray()) {
                var copy = new byte[attachment.size()];
                System.arraycopy(attachment.array(), attachment.arrayOffset(), copy, 0, copy.length);
                attempts.add(copy);
            }
            return attempts.size() == 1 ? SendResult.FAILED : SendResult.SUCCESS;
        }
    }
}
