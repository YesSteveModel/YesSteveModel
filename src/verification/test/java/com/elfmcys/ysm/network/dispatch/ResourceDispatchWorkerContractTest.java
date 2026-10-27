package com.elfmcys.ysm.network.dispatch;

import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceDispatchWorkerContractTest {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(1);

    @Test
    void closeReturnsOnlyAfterTheProducerThreadTerminates() throws Exception {
        var worker = new ResourceDispatchWorker(1, 2, 0);
        worker.start();
        var field = ResourceDispatchWorker.class.getDeclaredField("thread");
        field.setAccessible(true);
        var thread = (Thread)field.get(worker);

        worker.close();

        assertFalse(thread.isAlive());
    }

    @Test
    void preservesPlayerFifoAndClosesAcceptedPacketsExactlyOnce() {
        var closed = new AtomicInteger();
        var sent = new ArrayList<Integer>();
        var port = new TestPort(sent);
        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(owner(), port,
                List.of(new TestPacket(1, 2, 64, closed), new TestPacket(2, 1, 64, closed))));
            assertTrue(worker.dispatchOnce());
            assertEquals(List.of(1, 1, 2), sent);
            assertEquals(2, closed.get());
        }
        assertEquals(2, closed.get());
    }

    @Test
    void refreshesSoftAndHardConfigurationBeforeEachRound() {
        var settings = new AtomicReference<>(new ResourceDispatchWorker.Settings(2, 3, 0));
        var player = owner();
        var sent = new ArrayList<Integer>();
        try (var worker = new ResourceDispatchWorker(settings::get)) {
            assertTrue(worker.enqueue(player, new TestPort(sent),
                List.of(new TestPacket(1, 2, 64, new AtomicInteger()), new TestPacket(2, 1, 64, new AtomicInteger()))));
            assertEquals(2, worker.queued(player));
            assertTrue(worker.dispatchOnce());
            assertEquals(List.of(1), sent);

            settings.set(new ResourceDispatchWorker.Settings(3, 5, 0));
            assertEquals(2, worker.queued(player));
            assertTrue(worker.dispatchOnce());
            assertEquals(List.of(1, 1, 2), sent);
        }
    }

    @Test
    void appliesBackpressureBeforeBuildAndRetriesSameCursorOnOpenConnection() throws Exception {
        var packet = new TestPacket(7, 1, 64, new AtomicInteger());
        var port = new TestPort(new ArrayList<>());
        port.writable = false;
        var player = owner();
        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(player, port, List.of(packet)));
            assertTrue(worker.dispatchOnce());
            assertEquals(0, packet.builds.get());

            port.writable = true;
            port.failures = 1;
            assertTrue(worker.dispatchOnce());
            assertEquals(1, packet.builds.get());
            assertEquals(1, worker.queued(player));
            Thread.sleep(110);
            assertTrue(worker.dispatchOnce());
            assertEquals(2, packet.builds.get());
            assertEquals(List.of(7), port.sent);
        }
    }

    @Test
    void rejectsHardOverflowBeforeOwnershipTransferAndDropsRealSizeOverflow() {
        var player = owner();
        var rejectedClose = new AtomicInteger();
        var port = new TestPort(new ArrayList<>());
        try (var worker = new ResourceDispatchWorker(1, 2, 0)) {
            var first = new TestPacket(1, 1, 1, new AtomicInteger());
            var second = new TestPacket(2, 1, 64, new AtomicInteger());
            var rejected = new TestPacket(3, 1, 64, rejectedClose);
            assertTrue(worker.enqueue(player, port, List.of(first, second)));
            assertFalse(worker.enqueue(player, port, List.of(rejected)));
            assertEquals(0, rejectedClose.get());
            rejected.close();

            port.high = 1;
            assertTrue(worker.dispatchOnce());
            assertEquals(1, first.closed.get());
            assertTrue(port.sent.isEmpty());
        }
        assertEquals(1, rejectedClose.get());
    }

    @Test
    void notificationRejectionDoesNotRollbackServerAuthorityBaseline() {
        var first = catalog("first");
        var rejectedState = catalog("rejected");
        var nextState = catalog("next");
        var session = new ServerModelSession(() -> first, false);
        assertTrue(session.activate());
        session.commitCatalog(first);
        var player = session;
        var rejected = new TestPacket(2, 1, 1, new AtomicInteger());

        try (var worker = new ResourceDispatchWorker(1, 2, 0)) {
            assertTrue(worker.enqueue(player, new TestPort(new ArrayList<>()),
                List.of(new TestPacket(1, 1, 1, new AtomicInteger()), new TestPacket(3, 1, 1, new AtomicInteger()))));
            session.commitCatalog(rejectedState);
            assertFalse(worker.enqueue(player, new TestPort(new ArrayList<>()), List.of(rejected)));
            rejected.close();

            assertSame(rejectedState, session.authority().catalog());
            var next = session.commitCatalog(nextState);
            assertSame(rejectedState, next.previous().catalog());
            assertSame(nextState, next.current().catalog());
        }
    }

    @Test
    void roundRobinsBackloggedPlayersAndStopsCancelledOrDisconnectedQueues() {
        var firstPlayer = owner();
        var secondPlayer = owner();
        var sent = new ArrayList<Integer>();
        var first = new TestPacket(1, 2, 64, new AtomicInteger());
        var second = new TestPacket(2, 2, 64, new AtomicInteger());
        try (var worker = new ResourceDispatchWorker(1, 4, 0)) {
            assertTrue(worker.enqueue(firstPlayer, new TestPort(sent), List.of(first)));
            assertTrue(worker.enqueue(secondPlayer, new TestPort(sent), List.of(second)));
            assertTrue(worker.dispatchOnce());
            assertTrue(worker.dispatchOnce());
            assertEquals(List.of(1, 2), sent);

            first.cancelled = true;
            assertTrue(worker.dispatchOnce());
            assertEquals(1, first.closed.get());
            worker.disconnect(secondPlayer);
            assertEquals(1, second.closed.get());
        }
    }

    @Test
    void appliesTheGlobalBandwidthLimitToSuccessfulFrameBytes() {
        var packet = new TestPacket(3, 1, 16 * 1024, new AtomicInteger(), 16 * 1024);
        try (var worker = new ResourceDispatchWorker(1, 2, 32 * 1024)) {
            assertTrue(worker.enqueue(owner(), new TestPort(new ArrayList<>()), List.of(packet)));
            var started = System.nanoTime();
            assertTrue(worker.dispatchOnce());
            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis >= 300, "16 KiB at 32 KiB/s should visibly throttle, elapsed=" + elapsedMillis);
        }
    }

    @Test
    void disconnectLinearizesWithAnInFlightBuildAndClosesThePacketOnce() throws Exception {
        var owner = owner();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sent = new ArrayList<Integer>();
        var packet = new BlockingPacket(entered, release);
        var workers = Executors.newFixedThreadPool(2);
        try (var worker = new ResourceDispatchWorker(1, 4, 0)) {
            assertTrue(worker.enqueue(owner, new TestPort(sent), List.of(packet)));
            var dispatch = workers.submit(worker::dispatchOnce);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var disconnect = workers.submit(() -> worker.disconnect(owner));
            assertThrows(TimeoutException.class, () -> disconnect.get(100, TimeUnit.MILLISECONDS));

            release.countDown();
            assertTrue(dispatch.get(5, TimeUnit.SECONDS));
            disconnect.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(9), sent);
            assertEquals(1, packet.closed.get());
            assertFalse(worker.dispatchOnce());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void productionFailureClosesBeforeReportingAndLeavesLaterPacketsRunnable() {
        var owner = owner();
        var reported = new AtomicInteger();
        var closeCountAtReport = new AtomicInteger();
        var failure = new IllegalStateException("injected production failure");
        var failedRef = new AtomicReference<FailingPacket>();
        var failed = FailingPacket.duringBuild(failure, reportedFailure -> {
            assertSame(failure, reportedFailure);
            closeCountAtReport.set(failedRef.get().closed.get());
            reported.incrementAndGet();
        });
        failedRef.set(failed);
        var sent = new ArrayList<Integer>();
        var next = new TestPacket(6, 1, 64, new AtomicInteger());
        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(owner, new TestPort(sent), List.of(failed, next)));

            assertTrue(worker.dispatchOnce());
            assertEquals(1, failed.closed.get());
            assertEquals(failed.closed.get(), closeCountAtReport.get());
            assertEquals(1, reported.get());
            assertEquals(1, worker.queued(owner));

            assertTrue(worker.dispatchOnce());
            assertEquals(List.of(6), sent);
        }
        assertEquals(1, failed.closed.get());
    }

    @Test
    void productionFailureCallbackRunsOutsideTheSessionLock() throws Exception {
        var owner = owner();
        var executor = Executors.newSingleThreadExecutor();
        var callbackRan = new AtomicInteger();
        var worker = new ResourceDispatchWorker(24, 48, 0);
        try (worker) {
            var failed =
                FailingPacket.duringBuild(new IllegalStateException("injected production failure"), ignored -> {
                    try {
                        var queued = executor.submit(() -> worker.queued(owner));
                        assertEquals(1, queued.get(5, TimeUnit.SECONDS));
                        callbackRan.incrementAndGet();
                    } catch (Exception blocked) {
                        throw new AssertionError("Production callback held the session lock", blocked);
                    }
                });
            assertTrue(worker.enqueue(owner, new TestPort(new ArrayList<>()),
                List.of(failed, new TestPacket(7, 1, 64, new AtomicInteger()))));

            assertTrue(worker.dispatchOnce());
            assertEquals(1, callbackRan.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callbackFailureIsIsolatedAndLaterPacketsStillRun() {
        var owner = owner();
        var sent = new ArrayList<Integer>();
        var failed = FailingPacket.duringBuild(new IllegalStateException("injected production failure"), ignored -> {
            throw new IllegalStateException("injected callback failure");
        });
        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(owner, new TestPort(sent),
                List.of(failed, new TestPacket(8, 1, 64, new AtomicInteger()))));

            assertDoesNotThrow(worker::dispatchOnce);
            assertTrue(worker.dispatchOnce());
            assertEquals(List.of(8), sent);
        }
    }

    @Test
    void fatalPostBuildRejectionReportsProductionFailure() {
        var owner = owner();
        var reported = new AtomicReference<RuntimeException>();
        var failed = FailingPacket.afterBuild(128, reported::set);
        var port = new TestPort(new ArrayList<>());
        port.high = 1;
        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(owner, port, List.of(failed)));

            assertTrue(worker.dispatchOnce());
            assertInstanceOf(IllegalStateException.class, reported.get());
            assertEquals(1, failed.closed.get());
            assertEquals(0, worker.queued(owner));
        }
    }

    @Test
    void retriesSameFragmentWhenPressureRisesAfterBuild() throws Exception {
        var owner = owner();
        var reported = new AtomicReference<RuntimeException>();
        var sent = new ArrayList<Integer>();
        var port = new TestPort(sent);
        port.high = 64;
        var packetRef = new AtomicReference<FailingPacket>();
        var packet = FailingPacket.afterBuild(20, () -> {
            if (packetRef.get().builds.get() == 1) {
                port.pending = 50;
            }
        }, reported::set);
        packetRef.set(packet);

        try (var worker = new ResourceDispatchWorker(24, 48, 0)) {
            assertTrue(worker.enqueue(owner, port, List.of(packet)));

            assertTrue(worker.dispatchOnce());
            assertEquals(1, packet.builds.get());
            assertEquals(1, worker.queued(owner));
            assertEquals(0, packet.closed.get());
            assertNull(reported.get());
            assertFalse(worker.dispatchOnce());

            port.pending = 0;
            Thread.sleep(110);
            assertTrue(worker.dispatchOnce());
            assertEquals(2, packet.builds.get());
            assertEquals(0, worker.queued(owner));
            assertEquals(1, packet.closed.get());
            assertNull(reported.get());
            assertEquals(1, sent.size());
        }
    }

    private static final class TestPacket implements LogicalPacket {
        private final int marker;
        private final int fragments;
        private final long estimate;
        private final AtomicInteger aggregateClosed;
        private final AtomicInteger builds = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final int frameBytes;
        private boolean cancelled;

        private TestPacket(int marker, int fragments, long estimate, AtomicInteger aggregateClosed) {
            this.marker = marker;
            this.fragments = fragments;
            this.estimate = estimate;
            this.aggregateClosed = aggregateClosed;
            this.frameBytes = 1;
        }

        private TestPacket(int marker, int fragments, long estimate, AtomicInteger aggregateClosed, int frameBytes) {
            this.marker = marker;
            this.fragments = fragments;
            this.estimate = estimate;
            this.aggregateClosed = aggregateClosed;
            this.frameBytes = frameBytes;
        }

        @Override
        public int fragmentCount() {
            return fragments;
        }

        @Override
        public long estimateFrameBytes(int index) {
            return estimate;
        }

        @Override
        public OutboundFrame buildFragment(int index) {
            builds.incrementAndGet();
            var bytes = new byte[frameBytes];
            bytes[0] = (byte)marker;
            return FrameCodec.encode(16, bytes, null);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void close() {
            if (closed.getAndIncrement() == 0)
                aggregateClosed.incrementAndGet();
        }
    }

    private static final class BlockingPacket implements LogicalPacket {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger closed = new AtomicInteger();

        private BlockingPacket(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public int fragmentCount() {
            return 1;
        }

        @Override
        public long estimateFrameBytes(int index) {
            return 64;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public OutboundFrame buildFragment(int index) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test build timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test build interrupted", interrupted);
            }
            return FrameCodec.encode(16, new byte[] {9}, null);
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    private static final class FailingPacket implements CancellablePacket {
        private final RuntimeException buildFailure;
        private final int frameBytes;
        private final Runnable buildHook;
        private final Consumer<RuntimeException> failureCallback;
        private final AtomicInteger builds = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private boolean cancelled;

        private FailingPacket(RuntimeException buildFailure, int frameBytes, Runnable buildHook,
            Consumer<RuntimeException> failureCallback) {
            this.buildFailure = buildFailure;
            this.frameBytes = frameBytes;
            this.buildHook = buildHook;
            this.failureCallback = failureCallback;
        }

        private static FailingPacket duringBuild(RuntimeException failure, Consumer<RuntimeException> callback) {
            return new FailingPacket(failure, 0, () -> {
            }, callback);
        }

        private static FailingPacket afterBuild(int frameBytes, Consumer<RuntimeException> callback) {
            return afterBuild(frameBytes, () -> {
            }, callback);
        }

        private static FailingPacket afterBuild(int frameBytes, Runnable buildHook,
            Consumer<RuntimeException> callback) {
            return new FailingPacket(null, frameBytes, buildHook, callback);
        }

        @Override
        public int fragmentCount() {
            return 1;
        }

        @Override
        public long estimateFrameBytes(int index) {
            return 1;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public OutboundFrame buildFragment(int index) {
            builds.incrementAndGet();
            if (buildFailure != null) {
                throw buildFailure;
            }
            buildHook.run();
            return FrameCodec.encode(16, new byte[frameBytes], null);
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void productionFailed(RuntimeException failure) {
            failureCallback.accept(failure);
        }

        @Override
        public void close() {
            cancelled = true;
            closed.incrementAndGet();
        }
    }

    private static CatalogSnapshot catalog(String hierarchy) {
        var pack = new ModelPackDescriptor(CatalogRootKind.CUSTOM,
            "local:custom" + (hierarchy.isEmpty() ? ""
                : "/" + (hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy)),
            hierarchy, "", Map.of(), null, "", 0);
        return new CatalogSnapshot(Map.of(), List.of(pack), ModelScanReport.empty());
    }

    private static ServerModelSession owner() {
        var sessionId = NEXT_SESSION_ID.getAndIncrement();
        return new ServerModelSession(() -> catalog("owner-" + sessionId), false);
    }

    private static final class TestPort implements TransportPort {
        private final List<Integer> sent;
        private boolean open = true;
        private boolean writable = true;
        private long high = 1_000_000;
        private long pending;
        private int failures;

        private TestPort(List<Integer> sent) {
            this.sent = sent;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public long highWatermarkBytes() {
            return high;
        }

        @Override
        public long pendingBytes() {
            return pending;
        }

        @Override
        public SendResult trySend(OutboundFrame frame) {
            if (failures-- > 0)
                return SendResult.FAILED;
            sent.add(Byte.toUnsignedInt(frame.bytes()[1]));
            return SendResult.SUCCESS;
        }
    }
}
