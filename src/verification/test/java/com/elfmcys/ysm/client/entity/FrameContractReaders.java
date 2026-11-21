package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.expression.aot.EntityExpressionRuntime;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Combines original-task handoff with real connection teardown supplied by the service fixture. */
public final class FrameContractReaders {
    private FrameContractReaders() {}

    public static void verifyDisconnectAfterInlineAndGuiReaders(Runnable disconnect) throws Exception {
        var queue = new EntityExecutionLifecycleTest.QueuedScheduler();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = new EntityExpressionRuntime(); var guiRuntime = new EntityExpressionRuntime()) {
            var ordinary = new EntityExecutionLifecycle(runtime, () -> {
            }, failure -> fail(failure), queue);
            var gui = new EntityExecutionLifecycle(guiRuntime, () -> {
            }, failure -> fail(failure));
            var expected = Optional.of(ClientModelSession.State.ACTIVE);
            var main = Thread.currentThread();
            ordinary.submit(() -> {
                assertSame(main, Thread.currentThread());
                assertEquals(expected, ClientSessionRuntime.stateInReadWindow());
                return null;
            });
            var task = gui.reserveGuiTask(true, () -> {
                assertEquals(expected, ClientSessionRuntime.stateInReadWindow());
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                assertEquals(expected, ClientSessionRuntime.stateInReadWindow());
                return null;
            });
            var worker = new Thread(task);
            worker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                ordinary.awaitTaskForRender();
                assertTrue(gui.hasTask());
                assertEquals(expected, ClientSessionRuntime.stateInReadWindow());
                release.countDown();
                task.join();
                disconnect.run();
                assertTrue(ClientSessionRuntime.stateInReadWindow().isEmpty());
                ordinary.submit(() -> {
                    assertTrue(ClientSessionRuntime.stateInReadWindow().isEmpty());
                    return null;
                });
                ordinary.awaitTaskForRender();
            } finally {
                release.countDown();
                worker.join(5000);
                assertFalse(worker.isAlive());
                if (gui.hasTask())
                    task.join();
            }
        }
    }
}
